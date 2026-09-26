package me.rerere.rikkahub.ui.pages.onboarding

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * 向导里的模型预设。
 *
 * 只放"第一次打开就必须选一个"的那几个: 每个都能用**一个 API Key** 跑起来, 并且预填一个
 * 确实存在的模型 id。其余上百个提供商留给设置页 —— 引导流程里给一堆选择只会让人卡住。
 */
enum class ModelPreset(
    val label: String,
    val hint: String,
    val baseUrl: String,
    val modelId: String,
    val google: Boolean = false,
) {
    DEEPSEEK(
        label = "DeepSeek",
        hint = "国内直连 · 便宜",
        baseUrl = "https://api.deepseek.com/v1",
        modelId = "deepseek-chat",
    ),
    GEMINI(
        label = "Google Gemini",
        hint = "有免费额度",
        baseUrl = "",
        modelId = "gemini-2.0-flash",
        google = true,
    ),
    OPENAI(
        label = "OpenAI",
        hint = "需要能直连",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-4o-mini",
    ),
    CUSTOM(
        label = "自定义",
        hint = "任意 OpenAI 兼容服务",
        baseUrl = "",
        modelId = "",
    ),
}

/** 按预设拼出一个"还没落库"的提供商, 供**测试连接**用（此时还没有 id 也无所谓）。 */
internal fun previewProvider(
    preset: ModelPreset,
    apiKey: String,
    baseUrl: String = preset.baseUrl,
): ProviderSetting = if (preset.google) {
    ProviderSetting.Google(id = Uuid.random(), name = preset.label, apiKey = apiKey, enabled = true)
} else {
    ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = preset.label,
        baseUrl = baseUrl.trim().ifBlank { preset.baseUrl },
        apiKey = apiKey,
        enabled = true,
    )
}

/**
 * 把"选了哪个预设 + 填了什么"落成一个可用的设置。
 *
 * 抽成纯函数是为了能单测 —— 这里要保证的事很具体:
 * **配完之后 chatModelId 必须真的指向那个新建的模型**。否则界面看着"配好了",
 * 一进聊天仍然弹"请先选择模型"。
 *
 * 同名提供商已存在时**就地更新**而不是再加一个: 默认设置里本来就有 OpenAI 与 Gemini,
 * 再加一个同名的, 用户会在设置页看到两个 OpenAI, 不知道哪个是自己在用的。
 */
internal fun applyModelPreset(
    settings: Settings,
    preset: ModelPreset,
    apiKey: String,
    baseUrl: String = preset.baseUrl,
    modelId: String = preset.modelId,
): Settings {
    val finalModelId = modelId.trim().ifBlank { preset.modelId }.ifBlank { "default" }
    val existing = settings.providers.find { it.name == preset.label }

    val model = Model(
        id = Uuid.random(),
        modelId = finalModelId,
        displayName = finalModelId,
        // 搜索是内置工具, 不依赖 provider 支持 function calling, 所以默认给上
        tools = setOf(BuiltInTools.Search),
    )

    val provider = when {
        existing != null -> {
            val updated = when (existing) {
                is ProviderSetting.OpenAI -> existing.copy(
                    apiKey = apiKey,
                    baseUrl = baseUrl.trim().ifBlank { existing.baseUrl },
                    enabled = true,
                )

                is ProviderSetting.Google -> existing.copy(apiKey = apiKey, enabled = true)
                else -> existing
            }
            updated.addModel(model)
        }

        preset.google -> ProviderSetting.Google(
            id = Uuid.random(),
            name = preset.label,
            apiKey = apiKey,
            enabled = true,
        ).addModel(model)

        else -> ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = preset.label,
            baseUrl = baseUrl.trim().ifBlank { preset.baseUrl },
            apiKey = apiKey,
            enabled = true,
        ).addModel(model)
    }

    val providers = if (existing != null) {
        settings.providers.map { if (it.id == existing.id) provider else it }
    } else {
        settings.providers + provider
    }

    return settings.copy(
        providers = providers,
        chatModelId = model.id,
        // 快速模型也跟着指过去: 标题生成 / 建议 / workflow 的默认子代理都用它,
        // 不指的话那些功能会静默地"没有模型可用"。
        fastModelId = model.id,
    )
}
