package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekOcrToolTest {
    @Test
    fun standaloneKeyTakesPriorityAndUsesFirstConfiguredKey() {
        val settings = Settings(
            deepSeekOcrApiKey = " sk-standalone, sk-secondary ",
            providers = listOf(ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-provider")),
        )

        assertEquals("sk-standalone", settings.resolveDeepSeekOcrApiKey())
    }

    @Test
    fun inheritedKeyRequiresEnabledOfficialEndpoint() {
        val settings = Settings(providers = listOf(
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com.example.org", apiKey = "sk-proxy"),
            ProviderSetting.OpenAI(baseUrl = "http://api.deepseek.com", apiKey = "sk-insecure"),
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com:8443", apiKey = "sk-alternate-port"),
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com", apiKey = "sk-disabled", enabled = false),
            ProviderSetting.Claude(baseUrl = "https://api.deepseek.com/anthropic", apiKey = "sk-provider sk-secondary"),
        ))

        assertEquals("sk-provider", settings.resolveDeepSeekOcrApiKey())
        assertNull(settings.copy(providers = settings.providers.dropLast(1)).resolveDeepSeekOcrApiKey())
    }
}
