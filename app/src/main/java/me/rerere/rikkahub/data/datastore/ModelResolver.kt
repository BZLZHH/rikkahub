package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 把"嘴里说出来的模型"解析成真正的 [Model]。
 *
 * 为什么要这么一层: 模型有三套"名字" —— 本地 UUID（精确, 但模型根本猜不到）、
 * wire id（`deepseek-chat`, 模型可能记得住）、显示名（`DeepSeek V3`, 最像人话）。
 * 只让模型填 UUID 等于逼它瞎编; 只认显示名又会因为改个名就失效。
 * 所以这里按"从精确到宽松"依次尝试, 并且**在宽松匹配上要求唯一**, 免得悄悄选错一个模型。
 */
sealed interface ModelResolution {
    data class Resolved(val model: Model, val provider: ProviderSetting) : ModelResolution

    /** 没解析出来。带上候选清单, 好让调用方直接告诉模型"可选的其实有这些"。 */
    data class NotFound(val spec: String, val candidates: List<String>) : ModelResolution
}

/**
 * 解析顺序（先精确后宽松）:
 * 1. 本地 UUID;
 * 2. `provider/model`（也接受 `:` 或 `|` 分隔）—— provider 用名字或 UUID 匹配, model 用 wire id 或显示名;
 * 3. 全库范围内 wire id 精确匹配;
 * 4. 全库范围内显示名精确匹配（忽略大小写）;
 * 5. 全库范围内**唯一**的子串匹配（忽略大小写）。
 *
 * @return null 表示 [spec] 为空（调用方该用默认值）
 */
fun Settings.resolveModelSpec(spec: String?): ModelResolution? {
    val raw = spec?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val pairs = providers.flatMap { provider -> provider.models.map { provider to it } }

    // 1) 本地 UUID
    runCatching { Uuid.parse(raw) }.getOrNull()?.let { uuid ->
        pairs.firstOrNull { it.second.id == uuid }?.let {
            return ModelResolution.Resolved(it.second, it.first)
        }
    }

    // 2) provider/model
    val separator = raw.indexOfFirst { it == '/' || it == ':' || it == '|' }
    if (separator > 0) {
        val providerPart = raw.substring(0, separator).trim()
        val modelPart = raw.substring(separator + 1).trim()
        if (providerPart.isNotEmpty() && modelPart.isNotEmpty()) {
            val provider = providers.firstOrNull { it.matchesProvider(providerPart) }
            if (provider != null) {
                provider.models.firstOrNull { it.matchesModel(modelPart) }?.let {
                    return ModelResolution.Resolved(it, provider)
                }
            }
        }
    }

    // 3) wire id 精确
    pairs.firstOrNull { it.second.modelId.equals(raw, ignoreCase = true) }?.let {
        return ModelResolution.Resolved(it.second, it.first)
    }

    // 4) 显示名精确
    pairs.firstOrNull { it.second.displayName.equals(raw, ignoreCase = true) }?.let {
        return ModelResolution.Resolved(it.second, it.first)
    }

    // 5) 唯一子串。**必须唯一** —— 匹配到多个就当作没匹配上, 并让调用方看到候选,
    //    否则"deepseek"这种输入会静默挑中其中一个。
    val loose = pairs.filter {
        it.second.displayName.contains(raw, ignoreCase = true) ||
            it.second.modelId.contains(raw, ignoreCase = true)
    }
    if (loose.size == 1) return ModelResolution.Resolved(loose[0].second, loose[0].first)

    return ModelResolution.NotFound(raw, describeModels())
}

/** 当前配置里所有可用的模型, 形如 `DeepSeek / deepseek-flash`。给报错信息与提示词用。 */
fun Settings.describeModels(): List<String> = providers.flatMap { provider ->
    provider.models.map { provider.name + " / " + it.displayName.ifBlank { it.modelId } }
}

private fun ProviderSetting.matchesProvider(part: String): Boolean =
    name.equals(part, ignoreCase = true) || id.toString().equals(part, ignoreCase = true)

private fun Model.matchesModel(part: String): Boolean =
    modelId.equals(part, ignoreCase = true) ||
        displayName.equals(part, ignoreCase = true) ||
        id.toString().equals(part, ignoreCase = true)
