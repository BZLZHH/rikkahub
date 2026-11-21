package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal fun List<UIMessage>.deepSeekReminders(location: String, locale: Locale): Map<String, String> {
    val language = when (locale.language) {
        "zh" -> when (locale.country) {
            "HK" -> "中文香港"
            "TW", "MO" -> "中文繁體"
            else -> if (locale.script == "Hant") "中文繁體" else "中文"
        }
        "ja" -> "日本語"
        "ko" -> "한국어"
        "ru" -> "русский"
        "de" -> "Deutsch"
        "ar" -> "العربية"
        else -> "English"
    }
    val weekdayLocale = if (language == "English") Locale.ENGLISH else locale
    val safeLocation = location.lineSequence().joinToString(" ") { it.trim() }.trim()
    return filter { it.role == MessageRole.USER && !it.isSynthetic && !it.isNoticeOnly }.associate { message ->
        val date = message.createdAt.date
        val weekday = LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
            .dayOfWeek.getDisplayName(TextStyle.FULL, weekdayLocale)
        message.id.toString() to "$date,$weekday,$safeLocation,App,$language"
    }
}
