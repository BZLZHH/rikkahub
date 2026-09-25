package me.rerere.rikkahub.data.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 探针: 直接调 WorkflowRuntime 本体, 把返回值原样打印。 */
class RuntimeDirectProbeTest {

    @Test
    fun callTheRuntimeDirectly() = runBlocking {
        val script = WorkflowScriptParser.parse("return 1 + 2")
        println("PROBE body=[" + script.body + "]")
        println("PROBE meta=" + script.meta)
        val rt = WorkflowRuntime(
            agentHandler = WorkflowAgentHandler { _, _, _ -> null },
            timeoutMillis = 3_000,
        )
        val r = rt.execute(script)
        println("PROBE ok=" + r.ok)
        println("PROBE resultJson=" + r.resultJson)
        println("PROBE error=" + r.error)
        println("PROBE DIAGNOSTIC=" + WorkflowRuntime.DIAGNOSTIC)
    }
}
