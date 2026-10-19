/*
 * Copyright (C) 2026 Android Performance Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("LongMethod", "ReturnCount", "LoopWithTooManyJumpStatements", "UnusedParameter", "UnusedPrivateProperty")

package com.androidperformancestudio.reversenet.dns

import java.nio.ByteBuffer

/** Parsed A record (IPv4 address as int). */
public data class DnsARecord(
    val addressInt: Int,
)

/** Parsed AAAA record (16 raw bytes). */
public data class DnsAaaaRecord(
    val bytes: ByteArray,
)

/** A parsed DNS response relevant to the relay tap: question name + answer records. */
public data class DnsResponse(
    val questionName: String,
    val aRecords: List<DnsARecord>,
    val aaaaRecords: List<DnsAaaaRecord>,
    /** Re-encode of the response with AAAA records removed; null if nothing was stripped. */
    val strippedPayload: ByteArray?,
    /** True when at least one AAAA record was removed. */
    val aaaaStripped: Boolean,
)

/** Minimal DNS message parser limited to relay tap needs (A / AAAA / CNAME answers). */
public object DnsMessage {
    private const val HEADER_LENGTH = 12
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_CNAME = 5
    private const val POINTER_MASK = 0xc0

    /** True if the payload looks like a DNS response (QR bit set). */
    public fun isResponse(payload: ByteBuffer): Boolean {
        if (payload.remaining() < HEADER_LENGTH) return false
        val flags = payload.getShort(payload.position() + 2).toInt() and 0xffff
        return (flags and 0x8000) != 0
    }

    /**
     * Parse a DNS response and optionally strip AAAA records.
     *
     * @param payload packet payload starting at the DNS header (position/limit window)
     * @param stripAaaa when true, AAAA records are removed from the returned payload
     */
    public fun parse(payload: ByteBuffer, stripAaaa: Boolean): DnsResponse? {
        if (payload.remaining() < HEADER_LENGTH) return null
        val bytes = ByteArray(payload.remaining())
        payload.duplicate().get(bytes)
        return parseBytes(bytes, 0, bytes.size, stripAaaa)
    }

    public fun parseBytes(bytes: ByteArray, offset: Int, length: Int, stripAaaa: Boolean): DnsResponse? {
        if (length < HEADER_LENGTH) return null

        val qdCount = ((bytes[offset + 4].toInt() and 0xff) shl 8) or (bytes[offset + 5].toInt() and 0xff)
        val anCount = ((bytes[offset + 6].toInt() and 0xff) shl 8) or (bytes[offset + 7].toInt() and 0xff)
        var pos = offset + HEADER_LENGTH

        // Skip questions; capture the first question name
        var questionName = ""
        for (q in 0 until qdCount) {
            val nameResult = readName(bytes, pos, offset)
            pos = nameResult.second
            if (q == 0) questionName = nameResult.first
            pos += 4 // qtype + qclass
        }

        val aRecords = ArrayList<DnsARecord>()
        val aaaaRecords = ArrayList<DnsAaaaRecord>()

        data class Answer(
            val name: String,
            val type: Int,
            val recordStart: Int,
            val recordLength: Int,
            val rdataStart: Int,
            val rdLength: Int,
        )

        val answers = ArrayList<Answer>()
        for (ignored in 0 until anCount) {
            if (pos + 10 > offset + length) return null
            val nameStart = pos
            val nameResult = readName(bytes, pos, offset)
            pos = nameResult.second
            if (pos + 10 > offset + length) return null
            val type = ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff)
            val rdLength = ((bytes[pos + 8].toInt() and 0xff) shl 8) or (bytes[pos + 9].toInt() and 0xff)
            val recordStart = nameStart
            pos += 10
            if (pos + rdLength > offset + length) return null
            val recordLength = pos + rdLength - recordStart
            answers.add(Answer(nameResult.first, type, recordStart, recordLength, pos, rdLength))
            pos += rdLength
        }

        for (answer in answers) {
            when (answer.type) {
                TYPE_A -> if (answer.rdLength == 4) {
                    val ip = ((bytes[answer.rdataStart].toInt() and 0xff) shl 24) or
                        ((bytes[answer.rdataStart + 1].toInt() and 0xff) shl 16) or
                        ((bytes[answer.rdataStart + 2].toInt() and 0xff) shl 8) or
                        (bytes[answer.rdataStart + 3].toInt() and 0xff)
                    aRecords.add(DnsARecord(ip))
                }
                TYPE_AAAA -> if (answer.rdLength == 16) {
                    val raw = bytes.copyOfRange(answer.rdataStart, answer.rdataStart + 16)
                    aaaaRecords.add(DnsAaaaRecord(raw))
                }
                TYPE_CNAME -> Unit
            }
        }

        var strippedPayload: ByteArray? = null
        var aaaaStripped = false
        if (stripAaaa && aaaaRecords.isNotEmpty()) {
            val kept = answers.filter { it.type != TYPE_AAAA }
            val out = java.io.ByteArrayOutputStream(length)
            // Header: copy bytes 0..6 (ID through QDCOUNT), rewrite ANCOUNT, copy the rest of the header
            out.write(bytes, offset, 6)
            out.write((kept.size shr 8) and 0xff)
            out.write(kept.size and 0xff)
            out.write(bytes, offset + 8, HEADER_LENGTH - 8)
            // Questions: everything from end of header to the first answer record
            val questionsEnd = answers.firstOrNull()?.recordStart ?: (offset + length)
            out.write(bytes, offset + HEADER_LENGTH, questionsEnd - (offset + HEADER_LENGTH))
            // Kept answer records verbatim. Compression pointers inside record names point into the
            // header/question region, which is copied unchanged, so kept records remain decodable.
            for (answer in kept) {
                out.write(bytes, answer.recordStart, answer.recordLength)
            }
            strippedPayload = out.toByteArray()
            aaaaStripped = true
        }

        return DnsResponse(
            questionName = questionName,
            aRecords = aRecords,
            aaaaRecords = aaaaRecords,
            strippedPayload = strippedPayload,
            aaaaStripped = aaaaStripped,
        )
    }

    /** Read a possibly-compressed domain name. Returns (name, position after name). */
    private fun readName(bytes: ByteArray, start: Int, messageOffset: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var jumped = false
        var endPos = -1
        var jumps = 0
        while (true) {
            if (pos >= bytes.size) return "" to (if (endPos == -1) pos else endPos)
            val len = bytes[pos].toInt() and 0xff
            if (len == 0) {
                if (!jumped) endPos = pos + 1
                break
            }
            if ((len and POINTER_MASK) == POINTER_MASK) {
                if (pos + 1 >= bytes.size) break
                val target = ((len and 0x3f) shl 8) or (bytes[pos + 1].toInt() and 0xff)
                if (!jumped) endPos = pos + 2
                jumped = true
                pos = target
                jumps++
                if (jumps > 64) break
                continue
            }
            if (pos + 1 + len > bytes.size) break
            labels.add(String(bytes, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return (if (labels.isEmpty()) "" else labels.joinToString(".")) to (if (endPos == -1) pos else endPos)
    }
}
