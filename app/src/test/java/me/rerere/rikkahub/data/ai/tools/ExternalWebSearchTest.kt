package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWebSearchTest {
    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `native Flash search takes priority only on the official provider`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(modelId = "deepseek-flash", tools = setOf(BuiltInTools.DeepSeek))
        val official = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1")

        assertFalse(shouldUseExternalWebSearch(assistant, model, official))
        assertTrue(shouldUseExternalWebSearch(assistant, model.copy(deepSeek = model.deepSeek.copy(webTools = false)), official))
        assertTrue(shouldUseExternalWebSearch(assistant, model, official.copy(baseUrl = "https://example.com")))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}
