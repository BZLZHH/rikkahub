package me.rerere.rikkahub.data.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workflow 运行时的单测。
 *
 * 之所以能在 JVM 上测: app/build.gradle.kts 为 UnitTestRuntimeClasspath 做了依赖替换,
 * 用的是 quickjs-kt-jvm（内含 linux_x64 的 native 库）。本项目的教训是
 * "单测全绿但真机崩"确实发生过（桌面正则引擎容忍未转义的 }）, 所以能测的必须测。
 *
 * ## 曾经 6/7 全红, 根因是 JsonNull.content（已修, 记在这里免得重踩）
 *
 * 症状: 除"脚本抛错"那条外全部失败, 失败信息是 "应成功: null"。
 * 当时的推断是"超时后 error 没填上", 于是往事件循环、async 绑定、轮询方向排查 —— **全错**。
 *
 * 真相: JS 侧读到的是 {"hasResult":true,"result":3,"error":null}, 而
 *   kotlinx.serialization 的 JsonNull.content 返回的是**字面量字符串 "null"**（长度 4）,
 * 不是 Kotlin null。parseOutcome 于是把每个**成功**的脚本判成失败, 并给出 error="null"。
 * 而断言消息 "应成功: " + error 打印出来正是 "应成功: null" —— 看起来像 error 是 null。
 * 一次性探针（RuntimeFlowProbeTest）的输出早就写明第一次 poll 就拿到了正确状态,
 * 那份证据当时没被读懂。
 *
 * 两条留下的防复发措施:
 * 1. 成功/失败由 JS 侧给出的**布尔标志 hasError** 判定, 不再看 error 字段的"空/非空";
 * 2. show() 把错误信息加引号并带长度: 真 null 是 <null>, 字面量 "null" 是 "null"(4)。
 */
class WorkflowRuntimeTest {

    /**
     * 把可空错误信息渲染成**不会有歧义**的形式。
     *
     * 起因: JsonNull.content 返回的是字面量字符串 "null", 于是"成功"被误判成失败时,
     * 失败信息 "应成功: null" 与真正的 error == null 打印结果完全一样,
     * 排查方向被带偏了一整轮。加引号 + 长度后, 这两种情况一眼可辨。
     */
    private fun show(s: String?): String = if (s == null) "<null>" else "\"" + s + "\"(" + s.length + ")"

    private fun runtime(
        onAgent: suspend (String, String?, String?) -> String? = { _, _, _ -> null },
        progress: WorkflowProgressSink? = null,
    ) = WorkflowRuntime(
        agentHandler = WorkflowAgentHandler { prompt, schema, label -> onAgent(prompt, schema, label) },
        progress = progress,
        // 诊断期用短超时: 故障时能快速拿到"卡在哪"的信息
        timeoutMillis = 3_000,
    )

    @Test
    fun simpleReturnValue() = runBlocking {
        val script = WorkflowScriptParser.parse("return 1 + 2")
        val r = runtime().execute(script)
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals("3", r.resultJson)
    }

    @Test
    fun agentResultIsAvailableToScript() = runBlocking {
        val script = WorkflowScriptParser.parse(
            "const a = await agent(\"do it\")\nreturn { got: a }",
        )
        val r = runtime(onAgent = { _, _, _ -> "AGENT-OUT" }).execute(script)
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("脚本应拿到 agent 的返回值: " + r.resultJson, r.resultJson!!.contains("AGENT-OUT"))
    }

    @Test
    fun agentSeesPromptSchemaAndLabel() = runBlocking {
        var seenPrompt: String? = null
        var seenSchema: String? = null
        var seenLabel: String? = null
        val script = WorkflowScriptParser.parse(
            "await agent(\"P\", { schema: { type: \"object\" }, label: \"L\" })\nreturn 0",
        )
        val r = runtime(onAgent = { p, s, l ->
            seenPrompt = p; seenSchema = s; seenLabel = l; "x"
        }).execute(script)
        assertTrue(r.ok)
        assertEquals("P", seenPrompt)
        assertTrue("schema 应以 JSON 文本传入: " + seenSchema, seenSchema != null && seenSchema!!.contains("object"))
        assertEquals("L", seenLabel)
    }

    @Test
    fun nullAgentResultIsPassedThrough() = runBlocking {
        // 上游约定: 被停止或不可恢复错误时 agent() 返回 null, 脚本据此判断
        val script = WorkflowScriptParser.parse(
            "const a = await agent(\"x\")\nreturn a === null ? \"was-null\" : \"not-null\"",
        )
        val r = runtime(onAgent = { _, _, _ -> null }).execute(script)
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("应看到 null: " + r.resultJson, r.resultJson!!.contains("was-null"))
    }

    @Test
    fun argsAreInjected() = runBlocking {
        val script = WorkflowScriptParser.parse("return args.who")
        val r = runtime().execute(script, mapOf("who" to "rhe"))
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("args 应可用: " + r.resultJson, r.resultJson!!.contains("rhe"))
    }

    @Test
    fun phaseAndLogReachTheSink() = runBlocking {
        val phases = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val sink = object : WorkflowProgressSink {
            override fun onPhase(title: String) { phases += title }
            override fun onLog(message: String, level: String) { logs += level + ":" + message }
        }
        val script = WorkflowScriptParser.parse(
            "phase(\"search\")\nlog(\"hello\")\nreturn 1",
        )
        val r = runtime(progress = sink).execute(script)
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals(listOf("search"), phases)
        assertTrue("日志应到达: " + logs, logs.any { it.contains("hello") })
    }

    @Test
    fun scriptErrorIsReportedNotThrown() = runBlocking {
        val script = WorkflowScriptParser.parse("throw new Error(\"boom\")")
        val r = runtime().execute(script)
        assertFalse("应失败", r.ok)
        assertTrue("错误应被报出: " + r.error, r.error != null && r.error!!.contains("boom"))
    }

    /**
     * 回归: 成功的脚本必须报 error == null。
     *
     * 曾经这里返回的是**字符串** "null"（JsonNull.content 的坑）: ok=false 且 error 看起来是 null,
     * 结果 7 条单测里 6 条以 "应成功: null" 失败 —— 现象极像"超时后 error 没填", 实际是误判成功为失败。
     * 这条断言同时锁住"成功"与"error 是真 null"两件事, 防止同类问题再次伪装成别的故障。
     */
    @Test
    fun successfulRunReportsNoError() = runBlocking {
        val r = runtime().execute(WorkflowScriptParser.parse("return 1"))
        assertNull("成功时 error 必须是真 null, 而不是字符串 null: " + show(r.error), r.error)
        assertTrue("应成功: " + show(r.error), r.ok)
    }
}
