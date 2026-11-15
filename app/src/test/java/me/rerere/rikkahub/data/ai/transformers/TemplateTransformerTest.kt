package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class TemplateTransformerTest {
    @Test
    fun isolatedChildDoesNotLoadPrivateParentTemplate() {
        val parent = Assistant(messageTemplate = "secret: {{ message }} :secret")
        val loader = RecordingLoader(parent.messageTemplate)
        val engine = PebbleEngine.Builder().loader(loader).autoEscaping(false).build()
        val child = parent.copy(messageTemplate = "{{ message }}")
        val task = "Inspect <task> without changing it"

        val result = renderMessagesWithTemplate(engine, child, listOf(UIMessage.user(task)))

        assertEquals(task, result.single().toText())
        assertFalse(result.single().toText().contains("secret"))
        assertEquals(0, loader.reads)
    }

    @Test
    fun capturedTemplateStillWorksWhenParentProfileIsDeleted() {
        val effective = Assistant(messageTemplate = "{{ message }}")
        val loader = RecordingLoader(null)
        val engine = PebbleEngine.Builder().loader(loader).autoEscaping(false).build()
        val result = renderMessagesWithTemplate(engine, effective, listOf(UIMessage.user("task")))
        assertEquals("task", result.single().toText())
        assertEquals(0, loader.reads)
    }

    @Test
    fun effectiveChatTemplateKeepsVariablesAndSyntheticMessageBehavior() {
        val assistant = Assistant(messageTemplate = "{{ role }}:{{ message }}|{{ date }}|{{ time }}")
        val loader = RecordingLoader(assistant.messageTemplate)
        val engine = PebbleEngine.Builder().loader(loader).autoEscaping(false).build()
        val system = UIMessage.system("system").copy(isSynthetic = true)
        val messages = listOf(system, UIMessage.user("<task>"))
        val first = renderMessagesWithTemplate(engine, assistant, messages)
        val second = renderMessagesWithTemplate(engine, assistant, messages)
        assertEquals(system, first[0])
        assertTrue(first[1].toText().startsWith("user:<task>|"))
        assertEquals(3, first[1].toText().split('|').size)
        assertEquals(first, second)
        assertEquals(0, loader.reads)
    }
}

private class RecordingLoader(private val source: String?) : Loader<String> {
    var reads = 0
    override fun getReader(cacheKey: String?): Reader? {
        reads++
        return source?.let(::StringReader)
    }
    override fun setCharset(charset: String?) = Unit
    override fun setPrefix(prefix: String?) = Unit
    override fun setSuffix(suffix: String?) = Unit
    override fun resolveRelativePath(relativePath: String?, anchorPath: String?): String? = relativePath
    override fun createCacheKey(templateName: String?): String? = templateName
    override fun resourceExists(templateName: String?): Boolean = source != null
}
