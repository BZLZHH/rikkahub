package me.rerere.rikkahub.data.workflow

import me.rerere.workspace.JobParamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流变量解析: 步骤之间靠它传值, 出错会静默产出错命令, 所以逐条锁住。
 */
class WorkflowVarResolverTest {

    @Test
    fun plainParamIsShellQuoted() {
        val r = WorkflowVarResolver.render("echo {{name}}", mapOf("name" to "a b"), emptyMap())
        assertTrue(r.isClean)
        assertEquals("echo " + JobParamResolver.shellQuote("a b"), r.text)
    }

    @Test
    fun rawSuffixSkipsQuoting() {
        val r = WorkflowVarResolver.render("run {{flags|raw}}", mapOf("flags" to "-x -y"), emptyMap())
        assertEquals("run -x -y", r.text)
    }

    @Test
    fun stepOutputIsUsable() {
        val steps = mapOf("build" to mapOf("version" to "1.2.3"))
        val r = WorkflowVarResolver.render("echo {{steps.build.version}}", emptyMap(), steps)
        assertTrue(r.isClean)
        assertTrue(r.text.contains("1.2.3"))
    }

    @Test
    fun builtinExitCodeIsExported() {
        val steps = mapOf("build" to WorkflowVarResolver.builtinStepKeys(0))
        val r = WorkflowVarResolver.render("echo {{steps.build.exit_code}}", emptyMap(), steps)
        assertTrue(r.isClean)
        assertTrue(r.text.contains("0"))
    }

    @Test
    fun unknownPlaceholderIsKeptAndReported() {
        val r = WorkflowVarResolver.render("echo {{nope}}", emptyMap(), emptyMap())
        assertTrue("应当被记录而不是静默替换", r.unresolved.contains("nope"))
        assertEquals("echo {{nope}}", r.text)
    }

    @Test
    fun missingStepKeyIsReported() {
        val r = WorkflowVarResolver.render("echo {{steps.build.nope}}", emptyMap(), mapOf("build" to emptyMap()))
        assertTrue(r.unresolved.contains("steps.build.nope"))
    }

    @Test
    fun paramsUseDeclaredDefaults() {
        val (values, missing) = WorkflowVarResolver.resolveParams(
            listOf(
                WorkflowParam(name = "env", default = "dev"),
                WorkflowParam(name = "tag", required = true),
            ),
            mapOf("tag" to "v1" ),
        )
        assertEquals("dev", values["env"])
        assertEquals("v1", values["tag"])
        assertTrue(missing.isEmpty())
    }

    @Test
    fun missingRequiredParamIsReported() {
        val (_, missing) = WorkflowVarResolver.resolveParams(
            listOf(WorkflowParam(name = "tag", required = true)),
            emptyMap(),
        )
        assertEquals(listOf("tag"), missing)
    }

    @Test
    fun undeclaredArgIsStillAvailable() {
        val (values, _) = WorkflowVarResolver.resolveParams(emptyList(), mapOf("adhoc" to "x"))
        assertEquals("x", values["adhoc"])
    }
}
