package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 结构化输出: 提示词附 schema + Kotlin 侧校验 + 不合规重试。
 *
 * 刻意**只实现 JSON Schema 的一个子集**（type / required / properties / items / enum /
 * additionalProperties）。理由: 这道闸门的作用只是"把散文挡在外面", 完整实现
 * （$ref、oneOf、条件 schema、patternProperties ...）代码量会大好几倍, 而对"给子代理
 * 附一个形状要求"这个用法几乎没有边际收益。
 *
 * **不认识的关键字一律忽略** —— 宁可放过, 不可误判: 误判会让一份本来合规的输出被
 * 反复重试, 白烧 token 还拖长运行时间。
 */
object WorkflowStructuredOutput {

    /**
     * 校验 schema 自身是否自相矛盾。返回 null 表示没问题。
     *
     * 上游会在**启动子代理之前**做这一步: 能证明矛盾的直接失败, 别让子代理白跑一趟。
     * 这里只做能一眼看穿的那类: required 里的键被 additionalProperties: false 排除掉了。
     */
    fun checkSchema(schema: JsonObject): String? {
        val additionalForbidden = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        if (!additionalForbidden) return null
        val propertyNames = (schema["properties"] as? JsonObject)?.keys.orEmpty()
        val required = stringList(schema["required"])
        val impossible = required.filter { it !in propertyNames }
        if (impossible.isEmpty()) return null
        return "schema contradicts itself: required key(s) " + impossible.joinToString(", ") +
            " are ruled out by additionalProperties: false"
    }

    /** 校验一个值是否符合 schema; 返回 null 表示通过, 否则是给人看的原因。 */
    fun validate(schema: JsonObject, value: JsonElement): String? {
        val expectedTypes = stringList(schema["type"])
        if (expectedTypes.isNotEmpty() && expectedTypes.none { matchesType(value, it) }) {
            return "expected " + expectedTypes.joinToString(" | ") + " but got " + describeType(value)
        }

        val allowed = schema["enum"] as? JsonArray
        if (allowed != null && allowed.none { it == value }) {
            return "value " + value.toString() + " is not one of " + allowed.toString()
        }

        if (value is JsonObject) {
            val missing = stringList(schema["required"]).filter { !value.containsKey(it) }
            if (missing.isNotEmpty()) return "missing required key(s): " + missing.joinToString(", ")

            val properties = schema["properties"] as? JsonObject
            if (properties != null) {
                for ((key, sub) in properties) {
                    val child = value[key] ?: continue
                    val subSchema = sub as? JsonObject ?: continue
                    validate(subSchema, child)?.let { return "at ." + key + ": " + it }
                }
                val additionalForbidden = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
                if (additionalForbidden) {
                    val extra = value.keys - properties.keys
                    if (extra.isNotEmpty()) return "unexpected key(s): " + extra.joinToString(", ")
                }
            }
        }

        if (value is JsonArray) {
            val items = schema["items"] as? JsonObject
            if (items != null) {
                value.forEachIndexed { index, item ->
                    validate(items, item)?.let { return "at [" + index + "]: " + it }
                }
            }
        }
        return null
    }

    /**
     * 从模型的输出里抠出 JSON。
     *
     * 容忍三种常见走样: 前后有解释文字、包在 markdown 围栏里、两者兼有。
     * 不这么做的话, 一个"多说了句 Here you go"的合规结果会被判成不合规并重试,
     * 而重试往往还是同样的走样 —— 白烧五次。
     */
    fun parseLoosely(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        parse(trimmed)?.let { return it }

        val unfenced = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        parse(unfenced)?.let { return it }

        val start = unfenced.indexOfFirst { it == '{' || it == '[' }
        val end = unfenced.indexOfLast { it == '}' || it == ']' }
        if (start >= 0 && end > start) {
            parse(unfenced.substring(start, end + 1))?.let { return it }
        }
        return null
    }

    /** 第一次调用: 把 schema 直接附在提示词后面。 */
    fun structuredPrompt(prompt: String, schemaJson: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine("输出要求: 只输出一个 JSON 值。不要解释文字, 不要 markdown 代码围栏。")
        appendLine("它必须满足下面的 JSON Schema:")
        append(schemaJson)
    }

    /** 第 N 次重试: 把上一次**具体**哪里不合规告诉它, 否则它会原样再犯一遍。 */
    fun correctivePrompt(prompt: String, schemaJson: String, failure: String): String = buildString {
        appendLine(prompt)
        appendLine()
        appendLine("你上一次的输出不满足要求: " + failure)
        appendLine("请重新输出, 只输出满足下面 JSON Schema 的 JSON 值, 不要任何解释或围栏:")
        append(schemaJson)
    }

    private fun parse(text: String): JsonElement? =
        runCatching { JsonInstant.parseToJsonElement(text) }.getOrNull()

    private fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun matchesType(value: JsonElement, expected: String): Boolean = when (expected) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "null" -> value is JsonNull
        // 不认识的类型名不拦（宁可放过）
        else -> true
    }

    private fun describeType(value: JsonElement): String = when {
        value is JsonObject -> "object"
        value is JsonArray -> "array"
        value is JsonNull -> "null"
        value is JsonPrimitive && value.isString -> "string"
        value is JsonPrimitive && value.booleanOrNull != null -> "boolean"
        value is JsonPrimitive && value.longOrNull != null -> "integer"
        value is JsonPrimitive -> "number"
        else -> "unknown"
    }
}
