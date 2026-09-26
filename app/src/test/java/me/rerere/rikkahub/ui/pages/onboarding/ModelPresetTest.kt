package me.rerere.rikkahub.ui.pages.onboarding

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向导"配置模型"那一步真正要保证的事。
 *
 * 核心只有一条, 但它非常具体: **配完之后 chatModelId 必须真的指向那个新建的模型**。
 * 少这一步, 界面看着"配好了", 一进聊天仍然弹"请先选择模型" —— 而用户根本不知道
 * 问题出在引导流程里。
 */
class ModelPresetTest {

    private fun emptySettings() = Settings(providers = emptyList())

    @Test
    fun createsProviderAndPointsTheChatModelAtIt() {
        val result = applyModelPreset(emptySettings(), ModelPreset.DEEPSEEK, apiKey = "sk-test")

        assertEquals(1, result.providers.size)
        val provider = result.providers.first()
        assertEquals("DeepSeek", provider.name)
        assertEquals(1, provider.models.size)
        val model = provider.models.first()
        assertEquals("deepseek-chat", model.modelId)
        assertEquals("chatModelId 必须指向刚建的模型", model.id, result.chatModelId)
        assertEquals("快速模型也要指过去, 否则标题/建议/workflow 没有模型可用", model.id, result.fastModelId)
    }

    @Test
    fun fillsInTheApiKeyAndBaseUrl() {
        val result = applyModelPreset(emptySettings(), ModelPreset.DEEPSEEK, apiKey = "sk-test")
        val provider = result.providers.first() as ProviderSetting.OpenAI
        assertEquals("sk-test", provider.apiKey)
        assertEquals("https://api.deepseek.com/v1", provider.baseUrl)
        assertTrue("配好了就该是启用状态", provider.enabled)
    }

    @Test
    fun reusesAnExistingProviderOfTheSameName() {
        // 默认设置里本来就有 OpenAI / Gemini。再加一个同名的, 用户会在设置页看到两个,
        // 不知道哪个才是自己在用的。
        val existing = ProviderSetting.OpenAI(
            name = "DeepSeek",
            baseUrl = "https://old.example/v1",
            apiKey = "",
            enabled = false,
        )
        val settings = Settings(providers = listOf(existing))
        val result = applyModelPreset(settings, ModelPreset.DEEPSEEK, apiKey = "sk-new")

        assertEquals("不该多出一个提供商", 1, result.providers.size)
        val provider = result.providers.first() as ProviderSetting.OpenAI
        assertEquals("sk-new", provider.apiKey)
        assertTrue(provider.enabled)
        assertEquals("模型要加到这个已存在的提供商上", 1, provider.models.size)
    }

    @Test
    fun customModelIdOverridesThePresetDefault() {
        val result = applyModelPreset(
            emptySettings(),
            ModelPreset.DEEPSEEK,
            apiKey = "sk-test",
            modelId = "deepseek-reasoner",
        )
        assertEquals("deepseek-reasoner", result.providers.first().models.first().modelId)
    }

    @Test
    fun blankModelIdFallsBackToThePreset() {
        val result = applyModelPreset(
            emptySettings(),
            ModelPreset.OPENAI,
            apiKey = "sk-test",
            modelId = "   ",
        )
        assertEquals("gpt-4o-mini", result.providers.first().models.first().modelId)
    }

    @Test
    fun geminiPresetCreatesAGoogleProvider() {
        val result = applyModelPreset(emptySettings(), ModelPreset.GEMINI, apiKey = "key")
        assertTrue(
            "Gemini 必须落成 Google 类型, 否则请求会打到 OpenAI 协议上",
            result.providers.first() is ProviderSetting.Google,
        )
        assertEquals("gemini-2.0-flash", result.providers.first().models.first().modelId)
    }

    @Test
    fun customPresetUsesTheBaseUrlTheUserTyped() {
        val result = applyModelPreset(
            emptySettings(),
            ModelPreset.CUSTOM,
            apiKey = "sk-test",
            baseUrl = "https://my.gateway/v1",
            modelId = "my-model",
        )
        val provider = result.providers.first() as ProviderSetting.OpenAI
        assertEquals("https://my.gateway/v1", provider.baseUrl)
        assertEquals("my-model", provider.models.first().modelId)
    }

    @Test
    fun previewProviderCarriesTheKeySoTheTestIsMeaningful() {
        // "测试连接"用的就是这个 —— Key 没带上的话它会永远成功, 等于没测
        val preview = previewProvider(ModelPreset.DEEPSEEK, apiKey = "sk-x")
        assertTrue(preview is ProviderSetting.OpenAI)
        assertEquals("sk-x", (preview as ProviderSetting.OpenAI).apiKey)
        assertNotNull(preview.baseUrl)
    }
}
