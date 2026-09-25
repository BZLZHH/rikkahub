package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 模型名的解析。
 *
 * 这东西的价值全在**边界**上: 精确匹配谁都会写, 难的是"宽松匹配必须唯一" ——
 * 一旦允许多个候选里随便挑一个, 用户就会发现"我说 deepseek, 它却用了另一个 deepseek"。
 */
class ModelResolverTest {

    private fun model(wire: String, display: String, id: Uuid = Uuid.random()) =
        Model(modelId = wire, displayName = display, id = id)

    private val deepseekFlash = model("deepseek-chat", "DeepSeek Flash")
    private val deepseekPro = model("deepseek-reasoner", "DeepSeek Pro")
    private val openaiMini = model("gpt-4o-mini", "GPT-4o mini")

    private val settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(name = "DeepSeek", models = listOf(deepseekFlash, deepseekPro)),
            ProviderSetting.OpenAI(name = "OpenAI", models = listOf(openaiMini)),
        ),
    )

    private fun resolved(spec: String?): Model? =
        (settings.resolveModelSpec(spec) as? ModelResolution.Resolved)?.model

    @Test
    fun blankSpecMeansUseTheDefault() {
        assertNull("空字符串应当交给调用方去用默认值", settings.resolveModelSpec(null))
        assertNull(settings.resolveModelSpec("   "))
    }

    @Test
    fun localUuidWins() {
        val id = deepseekPro.id
        assertEquals(deepseekPro, resolved(id.toString()))
    }

    @Test
    fun providerSlashModelWorksWithNamesOrWireIds() {
        assertEquals(deepseekFlash, resolved("DeepSeek/deepseek-chat"))
        assertEquals(deepseekFlash, resolved("deepseek/DeepSeek Flash"))
        assertEquals(openaiMini, resolved("OpenAI:gpt-4o-mini"))
        assertEquals(openaiMini, resolved("openai|GPT-4o mini"))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(deepseekFlash, resolved("deepseek/DEEPSEEK-CHAT"))
        assertEquals(deepseekFlash, resolved("deepseek flash"))
    }

    @Test
    fun wireIdAndDisplayNameMatchAcrossProviders() {
        assertEquals(deepseekPro, resolved("deepseek-reasoner"))
        assertEquals(openaiMini, resolved("GPT-4o mini"))
    }

    @Test
    fun uniqueSubstringIsAccepted() {
        assertEquals(openaiMini, resolved("4o-mini"))
    }

    @Test
    fun ambiguousSubstringIsRefusedRatherThanGuessed() {
        // "deepseek" 同时命中 Flash 与 Pro —— 必须拒绝, 而不是随便挑一个
        val result = settings.resolveModelSpec("deepseek")
        assertTrue("模糊匹配到多个时应当拒绝: " + result, result is ModelResolution.NotFound)
        val notFound = result as ModelResolution.NotFound
        assertTrue("要把候选列出来: " + notFound.candidates, notFound.candidates.size >= 3)
        assertTrue(
            "候选要写成人看得懂的样子: " + notFound.candidates,
            notFound.candidates.any { it.startsWith("DeepSeek / ") },
        )
    }

    @Test
    fun unknownNameReportsCandidates() {
        val result = settings.resolveModelSpec("gpt-5-turbo")
        assertTrue(result is ModelResolution.NotFound)
        assertEquals(3, (result as ModelResolution.NotFound).candidates.size)
    }

    @Test
    fun providerWithoutModelFallsThrough() {
        // "OpenAI/gpt-4o" 里 provider 存在但模型不存在 —— 不能因此就认了
        assertTrue(settings.resolveModelSpec("OpenAI/gpt-4o") is ModelResolution.NotFound)
    }
}
