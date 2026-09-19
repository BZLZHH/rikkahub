package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CronExpressionTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun next(expr: String, from: String): Long? =
        CronExpression.parse(expr).nextFireAt(Instant.parse(from).toEpochMilli(), zone)

    @Test
    fun everyMinute() {
        val next = next("* * * * *", "2026-01-01T00:00:30Z")
        assertEquals(Instant.parse("2026-01-01T00:01:00Z").toEpochMilli(), next)
    }

    @Test
    fun dailyAtNineInShanghai() {
        // 上海 2026-01-01 09:00 == 2026-01-01T01:00:00Z
        val next = next("0 9 * * *", "2026-01-01T00:30:00Z")
        assertEquals(Instant.parse("2026-01-01T01:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun stepExpression() {
        val next = next("*/15 * * * *", "2026-01-01T00:16:00Z")
        assertEquals(Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun weekdayOnlySkipsWeekend() {
        // 2026-01-03 是周六, 下一个周一 09:00(上海) == 2026-01-05T01:00:00Z
        val next = next("0 9 * * 1", "2026-01-03T00:00:00Z")
        assertEquals(Instant.parse("2026-01-05T01:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun sundayAcceptsZeroAndSeven() {
        val withZero = next("0 0 * * 0", "2026-01-01T00:00:00Z")
        val withSeven = next("0 0 * * 7", "2026-01-01T00:00:00Z")
        assertEquals(withZero, withSeven)
        // 2026-01-04 是周日(上海) == 2026-01-03T16:00:00Z
        assertEquals(Instant.parse("2026-01-03T16:00:00Z").toEpochMilli(), withZero)
    }

    @Test
    fun invalidExpressionsAreRejected() {
        assertNotNull(CronExpression.validate("* * * *"))
        assertNotNull(CronExpression.validate("60 * * * *"))
        assertNotNull(CronExpression.validate("* * 32 * *"))
        assertNotNull(CronExpression.validate("*/0 * * * *"))
        assertNull(CronExpression.validate("0 3 * * 1-5"))
    }

    @Test
    fun unreachableScheduleReturnsNull() {
        assertNull(next("0 0 30 2 *", "2026-01-01T00:00:00Z"))
    }

    @Test
    fun leapDayIsFoundWithinSearchWindow() {
        val next = next("0 0 29 2 *", "2026-01-01T00:00:00Z")
        assertNotNull(next)
        assertTrue(next!! > Instant.parse("2027-01-01T00:00:00Z").toEpochMilli())
    }
}
