package me.rerere.rikkahub.data.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workflow 运行时的单测。
 *
 * 之所以能在 JVM 上测: app/build.gradle.kts 为 UnitTestRuntimeClasspath 做了依赖替换,
 * 用的是 quickjs-kt-jvm（内含 linux_x64 的 native 库）。本项目的教训是
 * "单测全绿但真机崩"确实发生过（桌面正则引擎容忍未转义的 }）, 所以能测的必须测。
 *
 * **当前状态: 7 条里 6 条失败（超时）—— 这是待修基线, 不是已通过的测试。**
 *
 * 已用探针确认的事实（缩小了排查范围）:
 * - 包装后的脚本**行为正确**: 探针里第一趟 evaluate 之后 done=true、res=3、
 *   读回状态得到 {"ok":1,"r":3}。也就是说"脚本只跑一次 + 结果写进 globalThis + 再求值读回"
 *   这套桥接思路是成立的。
 * - 宿主 async 绑定**会在 evaluate 期间自行跑完**（实测 invoked=1 completed=1）。
 *
 * 因此故障点在 WorkflowRuntime 的集成层, 重点看这两处:
 * 1. awaitDone() 的轮询是否真能看到 __rheDone（探针里是同步 evaluate 读的, 这里是 suspend evaluate）;
 * 2. parseOutcome() 对 hasResult 的解析（JSON.stringify 得到的是布尔 true,
 *    而代码里按字符串 "true" 比较过 —— 需要核对）。
 * 下轮先修这两处, 再谈 W2。
 */
class WorkflowRuntimeTest {

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
        assertTrue("应成功: " + r.error, r.ok)
        assertEquals("3", r.resultJson)
    }

    @Test
    fun agentResultIsAvailableToScript() = runBlocking {
        val script = WorkflowScriptParser.parse(
            "const a = await agent(\"do it\")\nreturn { got: a }",
        )
        val r = runtime(onAgent = { _, _, _ -> "AGENT-OUT" }).execute(script)
        assertTrue("应成功: " + r.error, r.ok)
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
        assertTrue("应成功: " + r.error, r.ok)
        assertTrue("应看到 null: " + r.resultJson, r.resultJson!!.contains("was-null"))
    }

    @Test
    fun argsAreInjected() = runBlocking {
        val script = WorkflowScriptParser.parse("return args.who")
        val r = runtime().execute(script, mapOf("who" to "rhe"))
        assertTrue("应成功: " + r.error, r.ok)
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
        assertTrue("应成功: " + r.error, r.ok)
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
}
