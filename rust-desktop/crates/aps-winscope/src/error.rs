//! Error and bad-request types carried to the viewer in the reference shapes.

use std::fmt;

/// A viewer-facing bad request: rendered as the reference 400 body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BadRequestError {
    pub message: String,
}

/// A device-side failure: rendered as the reference 500 body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceError {
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WinscopeError {
    BadRequest(BadRequestError),
    Device(DeviceError),
    /// The adb executable could not be located or spawned.
    Transport(String),
}

impl WinscopeError {
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::BadRequest(BadRequestError {
            message: message.into(),
        })
    }

    pub fn device(message: impl Into<String>) -> Self {
        Self::Device(DeviceError {
            message: message.into(),
        })
    }

    pub fn transport(message: impl Into<String>) -> Self {
        Self::Transport(message.into())
    }

    pub fn message(&self) -> &str {
        match self {
            Self::BadRequest(error) => &error.message,
            Self::Device(error) => &error.message,
            Self::Transport(message) => message,
        }
    }
}

impl fmt::Display for WinscopeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for WinscopeError {}
