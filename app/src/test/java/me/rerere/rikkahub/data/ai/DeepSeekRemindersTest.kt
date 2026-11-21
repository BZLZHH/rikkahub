package me.rerere.rikkahub.data.ai

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeepSeekRemindersTest {
    @Test
    fun buildsAnAppMarkerOnlyForRealUserMessages() {
        val user = UIMessage.user("hello").copy(createdAt = LocalDateTime(2026, 11, 21, 9, 0))
        val messages = listOf(UIMessage.user("runtime").copy(isSynthetic = true), user, UIMessage.assistant("reply"))
        assertEquals(
            mapOf(user.id.toString() to "2026-11-21,Saturday,,App,English"),
            messages.deepSeekReminders("", Locale.ENGLISH),
        )
        assertEquals(
            mapOf(user.id.toString() to "2026-11-21,星期六,台北,App,中文繁體"),
            messages.deepSeekReminders(" 台北 ", Locale.TAIWAN),
        )
    }
}
