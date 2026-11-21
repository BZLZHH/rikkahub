package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class DeepSeekModelOptions(
    val webTools: Boolean = true,
    val safetyCheck: Boolean = true,
    val conversationTitle: Boolean = true,
    val userMetadata: Boolean = false,
    val location: String = "",
    val fileInput: DeepSeekFileInput = DeepSeekFileInput.LOCAL,
)

@Serializable
enum class DeepSeekFileInput { LOCAL, TEXT, RICH_LAYOUT }

val Model.deepSeekToolsEnabled: Boolean
    get() = BuiltInTools.DeepSeek in tools

fun ProviderSetting.supportsDeepSeekFlash(model: Model): Boolean {
    if (model.type != ModelType.CHAT || model.modelId.lowercase() !in DEEPSEEK_FLASH_IDS) return false
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Claude -> baseUrl
        else -> return false
    }.toHttpUrlOrNull() ?: return false
    return baseUrl.isHttps && baseUrl.host == "api.deepseek.com" && baseUrl.port == 443 &&
        baseUrl.username.isEmpty() && baseUrl.password.isEmpty()
}

/** Native file IDs and tool history need to stay on the same account across requests. */
fun String.primaryDeepSeekApiKey(): String = split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty()

private val DEEPSEEK_FLASH_IDS = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
