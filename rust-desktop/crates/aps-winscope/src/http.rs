//! Minimal blocking HTTP/1.1 plumbing shared by the proxy and the viewer host:
//! loopback-only servers with bounded request reads and `Connection: close`
//! responses, mirroring the loopback service discipline of `firefox_viewer.rs`.

use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

const MAX_REQUEST_BYTES: usize = 16 * 1024;
const MAX_BODY_BYTES: u64 = 64 * 1024 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(30);
/// Large static assets (the 33 MB index.html, 11 MB wasm) are streamed in chunks.
const WRITE_CHUNK: usize = 64 * 1024;

/// A parsed request. Only what the two servers need is represented.
#[derive(Debug)]
pub struct Request {
    pub method: String,
    /// Percent-decoded path segments with trailing slashes collapsed.
    pub segments: Vec<String>,
    /// Raw path as received (the viewer host needs it for static resolution).
    pub raw_path: String,
    pub headers: BTreeMap<String, String>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(&name.to_ascii_lowercase()).map(String::as_str)
    }
}

#[derive(Debug, Clone)]
pub struct Response {
    pub status: u16,
    /// Content-Type value.
    pub content_type: String,
    pub headers: Vec<(String, String)>,
    pub body: Body,
}

#[derive(Debug, Clone)]
pub enum Body {
    Empty,
    Bytes(Vec<u8>),
    /// Path of a file streamed in chunks (viewer closure assets).
    File(std::path::PathBuf),
}

impl Response {
    pub fn bytes(status: u16, content_type: &str, body: impl Into<Vec<u8>>) -> Self {
        Self {
            status,
            content_type: content_type.to_owned(),
            headers: Vec::new(),
            body: Body::Bytes(body.into()),
        }
    }

    pub fn empty(status: u16, content_type: &str) -> Self {
        Self {
            status,
            content_type: content_type.to_owned(),
            headers: Vec::new(),
            body: Body::Empty,
        }
    }

    pub fn with_header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.to_owned(), value.to_owned()));
        self
    }

    pub fn file(status: u16, content_type: &str, path: std::path::PathBuf) -> Self {
        Self {
            status,
            content_type: content_type.to_owned(),
            headers: Vec::new(),
            body: Body::File(path),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseError {
    Malformed(&'static str),
    TooLarge,
    Io,
}

/// Reads and parses one HTTP/1.1 request. Keeps the connection closing semantics
/// simple: the caller answers and closes (`Connection: close` is always sent).
pub fn read_request(stream: &mut TcpStream) -> Result<Request, ParseError> {
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let mut reader = BufReader::new(stream.try_clone().map_err(|_| ParseError::Io)?);
    let mut request_line = String::new();
    match reader.read_line(&mut request_line) {
        Ok(0) => return Err(ParseError::Io),
        Ok(_) => {}
        Err(_) => return Err(ParseError::Io),
    }
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default().to_ascii_uppercase();
    let target = parts.next().unwrap_or_default().to_owned();
    if method.is_empty() || target.is_empty() {
        return Err(ParseError::Malformed("request line"));
    }
    let mut headers = BTreeMap::new();
    let mut total = request_line.len();
    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) => break,
            Ok(read) => {
                total += read;
                if total > MAX_REQUEST_BYTES {
                    return Err(ParseError::TooLarge);
                }
            }
            Err(_) => return Err(ParseError::Io),
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_owned());
        }
    }
    let content_length = headers
        .get("content-length")
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(0);
    if content_length > MAX_BODY_BYTES {
        return Err(ParseError::TooLarge);
    }
    let mut body = vec![0u8; content_length as usize];
    if content_length > 0 {
        reader
            .read_exact(&mut body)
            .map_err(|_| ParseError::Malformed("body"))?;
    }
    let raw_path = target.split('?').next().unwrap_or(&target).to_owned();
    let segments: Vec<String> = raw_path
        .trim_matches('/')
        .split('/')
        .filter(|segment| !segment.is_empty())
        .map(percent_decode)
        .collect();
    Ok(Request {
        method,
        segments,
        raw_path,
        headers,
        body,
    })
}

/// Writes the response and closes semantics: `Connection: close` is always set.
pub fn write_response(stream: &mut TcpStream, response: &Response) {
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let mut head = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nCache-Control: no-cache, no-store, must-revalidate\r\nConnection: close\r\n",
        response.status,
        reason_phrase(response.status),
        response.content_type
    );
    for (name, value) in &response.headers {
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    let ok = match &response.body {
        Body::Empty => {
            head.push_str("Content-Length: 0\r\n\r\n");
            stream.write_all(head.as_bytes()).is_ok()
        }
        Body::Bytes(bytes) => {
            head.push_str(&format!("Content-Length: {}\r\n\r\n", bytes.len()));
            stream.write_all(head.as_bytes()).is_ok() && stream.write_all(bytes).is_ok()
        }
        Body::File(path) => match std::fs::File::open(path) {
            Ok(mut file) => {
                let length = file.metadata().map(|metadata| metadata.len()).unwrap_or(0);
                head.push_str(&format!("Content-Length: {length}\r\n\r\n"));
                if stream.write_all(head.as_bytes()).is_err() {
                    false
                } else {
                    let mut buffer = vec![0u8; WRITE_CHUNK];
                    loop {
                        let read = file.read(&mut buffer).unwrap_or(0);
                        if read == 0 {
                            break true;
                        }
                        if stream.write_all(&buffer[..read]).is_err() {
                            break false;
                        }
                    }
                }
            }
            Err(_) => false,
        },
    };
    if ok {
        let _ = stream.flush();
    }
}

fn reason_phrase(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        500 => "Internal Server Error",
        _ => "OK",
    }
}

/// Decodes %XX escapes; invalid sequences stay literal (the viewer sends plain
/// ASCII paths and serials).
pub fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 3 <= bytes.len() {
            let hex = &bytes[index + 1..index + 3];
            if let Ok(value) = u8::from_str_radix(&String::from_utf8_lossy(hex), 16) {
                decoded.push(value);
                index += 3;
                continue;
            }
        }
        decoded.push(bytes[index]);
        index += 1;
    }
    String::from_utf8_lossy(&decoded).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percent_decode_handles_escapes_and_keeps_invalid() {
        assert_eq!(percent_decode("/data/misc/wmtrace"), "/data/misc/wmtrace");
        assert_eq!(percent_decode("a%20b"), "a b");
        assert_eq!(percent_decode("100%"), "100%");
        assert_eq!(percent_decode("%zz"), "%zz");
        assert_eq!(percent_decode("%e4%b8%ad"), "中");
    }

    #[test]
    fn reason_phrases_cover_the_used_statuses() {
        assert_eq!(reason_phrase(200), "OK");
        assert_eq!(reason_phrase(400), "Bad Request");
        assert_eq!(reason_phrase(403), "Forbidden");
        assert_eq!(reason_phrase(404), "Not Found");
        assert_eq!(reason_phrase(500), "Internal Server Error");
        assert_eq!(reason_phrase(418), "OK");
    }

    #[test]
    fn responses_carry_headers_and_bodies() {
        let response = Response::bytes(200, "text/json", "{}")
            .with_header("Winscope-Proxy-Version", "4.0.8");
        assert_eq!(response.status, 200);
        assert_eq!(response.headers.len(), 1);
        assert!(matches!(response.body, Body::Bytes(_)));
        let response = Response::empty(200, "text/txt");
        assert!(matches!(response.body, Body::Empty));
    }
}
