package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具实现共用的 JSON 小辅助。
 *
 * 与 JobTools 里那几个 private 版本同名但不同可见性 —— 这里刻意用不同名字（多了 Json 后缀）
 * 以免将来有人把两者合并时出现歧义。
 *
 * 统一约定（整个工具层一致）:
 * - 成功: 返回**一个 JSON 对象**作为文本, 便于模型解析;
 * - 失败: 返回 {"error": {"code", "message"}}, 让模型能分辨"参数错"与"内部错"。
 */

internal fun jsonText(json: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))

internal fun jsonError(code: String, message: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            }.toString()
        )
    )

/** 包一层: 内部异常也变成结构化错误, 而不是把栈丢给模型。 */
internal suspend fun runToolSafely(block: suspend () -> List<UIMessagePart>): List<UIMessagePart> =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        jsonError("internal_error", e.message ?: e::class.java.simpleName)
    }

internal fun JsonObject.strJson(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intJson(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.boolJson(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.stringListJson(name: String): List<String> {
    val array = this[name] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
