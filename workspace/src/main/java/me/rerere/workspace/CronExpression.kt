package me.rerere.workspace

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 极简 5 字段 cron（分 时 日 月 周）。
 *
 * 支持: 星号、单值、a-b、a-b/n、星号/n、a/n、逗号列表。
 * 周字段 0 与 7 均表示周日; 日与周同时被限定时采用标准 cron 的"或"语义。
 * 不支持秒字段与 @daily 之类的宏。
 *
 * DST 说明: 夏令时切换导致本地时间不存在/重复时, 交由 java.time 归一化处理。
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val dayOfMonthRestricted: Boolean,
    private val dayOfWeekRestricted: Boolean,
) {
    fun nextFireAt(afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val startDay = Instant.ofEpochMilli(afterMillis).atZone(zone).truncatedTo(ChronoUnit.DAYS)
        val sortedHours = hours.sorted()
        val sortedMinutes = minutes.sorted()
        for (offset in 0..MAX_SEARCH_DAYS) {
            val day = startDay.plusDays(offset.toLong())
            if (day.monthValue !in months) continue
            if (!matchesDay(day)) continue
            for (hour in sortedHours) {
                for (minute in sortedMinutes) {
                    val candidate = day.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    val millis = candidate.toInstant().toEpochMilli()
                    if (millis > afterMillis) return millis
                }
            }
        }
        return null
    }

    private fun matchesDay(day: ZonedDateTime): Boolean {
        val domOk = day.dayOfMonth in daysOfMonth
        // DayOfWeek: MONDAY=1 ... SUNDAY=7 -> cron 的 0 表示周日
        val dowOk = (day.dayOfWeek.value % 7) in daysOfWeek
        return when {
            dayOfMonthRestricted && dayOfWeekRestricted -> domOk || dowOk
            dayOfMonthRestricted -> domOk
            dayOfWeekRestricted -> dowOk
            else -> true
        }
    }

    companion object {
        /** 足以覆盖 2 月 29 日这类最长间隔 */
        private const val MAX_SEARCH_DAYS = 1_461

        private val NAMED_FIELDS = listOf("minute", "hour", "day-of-month", "month", "day-of-week")

        fun parse(expression: String): CronExpression {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == 5) {
                "Cron expression must have 5 fields (minute hour day-of-month month day-of-week), got ${fields.size}: '$expression'"
            }
            val minutes = parseField(fields[0], 0, 59, NAMED_FIELDS[0])
            val hours = parseField(fields[1], 0, 23, NAMED_FIELDS[1])
            val daysOfMonth = parseField(fields[2], 1, 31, NAMED_FIELDS[2])
            val months = parseField(fields[3], 1, 12, NAMED_FIELDS[3])
            val daysOfWeek = parseField(fields[4], 0, 7, NAMED_FIELDS[4])
                .map { if (it == 7) 0 else it }
                .toSet()
            return CronExpression(
                minutes = minutes,
                hours = hours,
                daysOfMonth = daysOfMonth,
                months = months,
                daysOfWeek = daysOfWeek,
                dayOfMonthRestricted = fields[2] != "*",
                dayOfWeekRestricted = fields[4] != "*",
            )
        }

        /** 解析失败返回错误信息, 成功返回 null (便于工具层做结构化报错)。 */
        fun validate(expression: String): String? = runCatching { parse(expression) }
            .exceptionOrNull()
            ?.message

        private fun parseField(field: String, min: Int, max: Int, name: String): Set<Int> {
            val result = sortedSetOf<Int>()
            field.split(',').forEach { part ->
                require(part.isNotBlank()) { "Invalid $name field: '$field'" }
                val stepSplit = part.split('/')
                require(stepSplit.size <= 2) { "Invalid $name field: '$part'" }
                val hasStep = stepSplit.size == 2
                val step = if (hasStep) {
                    stepSplit[1].toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("Invalid step in $name field: '$part'")
                } else {
                    1
                }
                val range = stepSplit[0]
                val rangeStart: Int
                val rangeEnd: Int
                when {
                    range == "*" -> {
                        rangeStart = min
                        rangeEnd = max
                    }

                    '-' in range -> {
                        val bounds = range.split('-')
                        require(bounds.size == 2) { "Invalid range in $name field: '$part'" }
                        rangeStart = bounds[0].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid value in $name field: '$part'")
                        rangeEnd = bounds[1].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid value in $name field: '$part'")
                    }

                    else -> {
                        val single = range.toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid value in $name field: '$part'")
                        // 单值就是单值; 只有 a/n 这种带步进的写法才表示 a..max
                        rangeStart = single
                        rangeEnd = if (hasStep) max else single
                    }
                }
                require(rangeStart in min..max && rangeEnd in min..max) {
                    "$name field out of range ($min..$max): '$part'"
                }
                require(rangeStart <= rangeEnd) { "Invalid range in $name field: '$part'" }
                var value = rangeStart
                while (value <= rangeEnd) {
                    result += value
                    value += step
                }
            }
            require(result.isNotEmpty()) { "$name field is empty: '$field'" }
            return result
        }
    }
}
