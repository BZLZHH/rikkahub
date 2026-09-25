package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON Schema 子集校验。
 *
 * 两条原则在这里被锁住:
 * 1. **不认识的 keyword 一律忽略** —— 宁可放过, 不可误判（误判会让合规输出被反复重试）;
 * 2. 该拦的（缺字段、类型不对、多出字段）必须拦得住, 否则结构化输出就是摆设。
 */
class WorkflowStructuredOutputTest {

    private fun schema(text: String): JsonObject =
        WorkflowStructuredOutput.parseLoosely(text) as JsonObject

    private fun value(text: String) = WorkflowStructuredOutput.parseLoosely(text)!!

    @Test
    fun requiredKeysAreEnforced() {
        val s = schema("{\"type\":\"object\",\"required\":[\"files\"]}")
        assertNull(WorkflowStructuredOutput.validate(s, value("{\"files\":[]}")))
        assertNotNull(WorkflowStructuredOutput.validate(s, value("{}")))
    }

    @Test
    fun typesAreEnforcedRecursively() {
        val s = schema(
            "{\"type\":\"object\",\"properties\":{\"files\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"
        )
        assertNull(WorkflowStructuredOutput.validate(s, value("{\"files\":[\"a\",\"b\"]}")))
        assertNotNull(
            "元素类型不对要拦: " + WorkflowStructuredOutput.validate(s, value("{\"files\":[1]}")),
            WorkflowStructuredOutput.validate(s, value("{\"files\":[1]}")),
        )
        assertNotNull(
            "整体类型不对要拦",
            WorkflowStructuredOutput.validate(s, value("{\"files\":\"a\"}")),
        )
    }

    @Test
    fun integerAndNumberAreDistinguished() {
        val integer = schema("{\"type\":\"integer\"}")
        assertNull(WorkflowStructuredOutput.validate(integer, value("3")))
        assertNotNull("3.5 不是整数", WorkflowStructuredOutput.validate(integer, value("3.5")))

        val number = schema("{\"type\":\"number\"}")
        assertNull(WorkflowStructuredOutput.validate(number, value("3.5")))
        assertNull(WorkflowStructuredOutput.validate(number, value("3")))
    }

    @Test
    fun unknownKeywordsAreIgnored() {
        // 用上完整实现才认得的 keyword: 我们只要求"别误判"
        val s = schema(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"^a\"}}," +
                "\"\$schema\":\"http://json-schema.org/draft-07/schema#\"}"
        )
        assertNull(
            "不认识的 keyword 不该导致误判: " + WorkflowStructuredOutput.validate(s, value("{\"x\":\"zzz\"}")),
            WorkflowStructuredOutput.validate(s, value("{\"x\":\"zzz\"}")),
        )
    }

    @Test
    fun enumAndAdditionalPropertiesAreEnforced() {
        val enum = schema("{\"enum\":[\"a\",\"b\"]}")
        assertNull(WorkflowStructuredOutput.validate(enum, value("\"a\"")))
        assertNotNull(WorkflowStructuredOutput.validate(enum, value("\"c\"")))

        val closed = schema(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"a\":{\"type\":\"string\"}}}"
        )
        assertNull(WorkflowStructuredOutput.validate(closed, value("{\"a\":\"x\"}")))
        assertNotNull(
            "多出的键要拦",
            WorkflowStructuredOutput.validate(closed, value("{\"a\":\"x\",\"b\":1}")),
        )
    }

    @Test
    fun selfContradictingSchemaIsDetected() {
        // 上游会在启动子代理之前拦下这种 schema（别让它白跑一趟）
        val bad = schema(
            "{\"type\":\"object\",\"required\":[\"a\"],\"additionalProperties\":false,\"properties\":{\"b\":{\"type\":\"string\"}}}"
        )
        assertNotNull(WorkflowStructuredOutput.checkSchema(bad))
        val fine = schema("{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"type\":\"string\"}}}")
        assertNull(WorkflowStructuredOutput.checkSchema(fine))
    }

    @Test
    fun looseParsingToleratesProseAndFences() {
        assertEquals(3, (WorkflowStructuredOutput.parseLoosely("3") as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertNotNull(WorkflowStructuredOutput.parseLoosely("```json\n{\"a\":1}\n```"))
        assertNotNull(WorkflowStructuredOutput.parseLoosely("Here you go:\n{\"a\":1}\nHope that helps!"))
        // 关键: JsonInstant 是宽松模式, 会把大白话也解析成字符串字面量,
        // 所以这里必须返回 null —— 否则"模型给没给 JSON"就判不出来, 一段散文会被当成合法输出。
        assertNull("真的不是 JSON 就要返回 null", WorkflowStructuredOutput.parseLoosely("我不知道"))
        assertNull("英文散文同理", WorkflowStructuredOutput.parseLoosely("I am not sure about this"))
        assertNull("JSON 字符串必须带引号", WorkflowStructuredOutput.parseLoosely("hello world"))
    }

    @Test
    fun promptsCarryTheSchema() {
        val s = WorkflowStructuredOutput.structuredPrompt("干活", "{\"type\":\"object\"}")
        assertTrue("原始指令要在: " + s, s.contains("干活"))
        assertTrue("schema 要在: " + s, s.contains("\"type\":\"object\""))

        val c = WorkflowStructuredOutput.correctivePrompt("干活", "{\"type\":\"object\"}", "缺 files")
        assertTrue("必须说清上次哪里不对: " + c, c.contains("缺 files"))
    }

    @Test
    fun schemaHelperRoundTrips() {
        // 用 buildJsonObject 构造一份, 确认与 parseLoosely 的结果一致（避免测试自己写错 JSON）
        val built = buildJsonObject { put("type", "object") }
        assertEquals(built, schema("{\"type\":\"object\"}"))
    }
}
