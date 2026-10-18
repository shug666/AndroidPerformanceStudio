package com.androidperformancestudio.perfetto.presentation

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureTimestampFormatTest {
    @Test
    fun `capture timestamp renders as local date and time`() {
        assertEquals(
            "2026-08-01 16:07:59",
            formatCaptureTimestamp(Instant.parse("2026-08-01T08:07:59.643394Z"), ZoneId.of("Asia/Shanghai")),
        )
    }
}
