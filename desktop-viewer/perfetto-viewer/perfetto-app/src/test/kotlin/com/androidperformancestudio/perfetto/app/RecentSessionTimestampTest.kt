package com.androidperformancestudio.perfetto.app

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSessionTimestampTest {
    @Test
    fun `recent session timestamp renders as local date and time`() {
        val zone = ZoneId.of("Asia/Shanghai")

        assertEquals(
            "2026-08-01 16:07:59",
            formatRecentSessionTimestamp(Instant.parse("2026-08-01T08:07:59.643394Z"), zone),
        )
    }
}
