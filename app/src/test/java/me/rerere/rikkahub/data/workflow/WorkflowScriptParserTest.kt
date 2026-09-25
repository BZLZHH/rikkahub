package me.rerere.rikkahub.data.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * meta 解析: 文档约定 meta 必须是首个语句且为纯字面量。
 * 这里覆盖它在真实脚本里的各种写法 —— 尤其是"嵌套对象的收尾括号落在行首"那种,
 * 正则若写成"第一个换行后的 }"会被提前截断, phases 就静默变空了。
 */
class WorkflowScriptParserTest {

    @Test
    fun parsesSimpleMeta() {
        val src = """
            |export const meta = {
            |  name: "audit-routes",
            |  description: "Audit routes",
            |}
            |
            |const x = await agent("hi")
            |return x
        """.trimMargin()
        val s = WorkflowScriptParser.parse(src)
        assertNotNull(s.meta)
        assertEquals("audit-routes", s.meta!!.name)
        assertEquals("Audit routes", s.meta!!.description)
        assertTrue("body 应保留可执行部分", s.body.contains("await agent"))
        assertTrue("body 不该再含 meta 声明", !s.body.contains("export const meta"))
    }

    @Test
    fun parsesPhasesWithNestedBraces() {
        // 关键用例: phases 数组的收尾括号与 meta 的收尾括号都可能落在行首
        val src = """
            |export const meta = {
            |  name: "deep",
            |  description: "Deep research",
            |  phases: [
            |    "search",
            |    "verify",
            |  ],
            |}
            |return 1
        """.trimMargin()
        val s = WorkflowScriptParser.parse(src)
        assertNotNull(s.meta)
        assertEquals(listOf("search", "verify"), s.meta!!.phases)
    }

    @Test
    fun scriptWithoutMetaStillParses() {
        val src = "const x = await agent(\"hi\")\nreturn x"
        val s = WorkflowScriptParser.parse(src)
        assertNull("没有 meta 允许内联运行", s.meta)
        assertEquals(src.trim(), s.body.trim())
    }

    @Test
    fun metaWithoutNameIsIgnored() {
        val src = """export const meta = {
            |  description: "no name",
            |}
            |return 1""".trimMargin()
        val s = WorkflowScriptParser.parse(src)
        assertNull("缺 name 视为无效 meta", s.meta)
    }

    @Test
    fun fingerprintIgnoresIndentationAndBlankLines() {
        val a = "export const meta = {\n  name: \"x\",\n}\nconst y = 1"
        val b = "export const meta = {\n        name: \"x\",\n}\n\n\nconst y = 1"
        assertEquals(
            "行首缩进/空行差异不该改变指纹（重排缩进就让已完成的 agent 全部作废是不可接受的）",
            WorkflowScriptParser.parse(a).fingerprint,
            WorkflowScriptParser.parse(b).fingerprint,
        )
    }

    @Test
    fun fingerprintTreatsInlineWhitespaceAsMeaningful() {
        // 这是刻意的保守取舍: 不归一化行内空格。
        // 归一化它会让"内容不同、只有空格不同"的脚本指纹相同 ——
        // 那会**错误地复用**已完成 agent 的结果, 比"多跑几次"危险得多。
        val a = "const y = 1"
        val b = "const y  =  1"
        assertTrue(
            "行内空格视为语义差异（宁可多跑, 不可错配）",
            WorkflowScriptParser.parse(a).fingerprint != WorkflowScriptParser.parse(b).fingerprint,
        )
    }

    @Test
    fun fingerprintChangesWhenContentChanges() {
        val a = "const y = 1"
        val c = "const y = 2"
        assertTrue(
            "内容变了指纹必须变",
            WorkflowScriptParser.parse(a).fingerprint != WorkflowScriptParser.parse(c).fingerprint,
        )
    }
}
