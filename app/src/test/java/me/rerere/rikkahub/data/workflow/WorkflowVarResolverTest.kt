package me.rerere.rikkahub.data.workflow

import me.rerere.workspace.JobParamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- 正则健壮性（真机崩过一次, 见下）----

    /**
     * 占位符匹配依赖一条正则。它曾经写成结尾不转义的 `}}`, 桌面 JVM 容忍,
     * 但 Android 的 Pattern（com.android.icu）严格解析会抛 PatternSyntaxException;
     * 又因为它当时是 object 的静态字段, 异常发生在 <clinit>, 于是整个类永远加载失败 ——
     * 第一次 ExceptionInInitializerError, 之后每次 NoClassDefFoundError, 流程一步都跑不了。
     *
     * 说明: 本测试跑在桌面 JVM 上, **无法**复现 Android 的严格解析;
     * 它锁的是"正则能编译"与"花括号成对"这两条底线, 真正的运行时差异只能靠真机验证。
     */
    @Test
    fun placeholderPatternMatchesBracesCleanly() {
        val r = WorkflowVarResolver.render("a {{x}} b", mapOf("x" to "1"), emptyMap())
        assertTrue("应匹配并使用参数", r.isClean)
        assertTrue(r.text.contains("1"))
        assertFalse("右花括号不该被吃掉", r.text.contains("}}"))
    }

    @Test
    fun adjacentPlaceholdersBothMatch() {
        val r = WorkflowVarResolver.render("{{a}}{{b}}", mapOf("a" to "1", "b" to "2"), emptyMap())
        assertTrue(r.isClean)
        assertEquals(JobParamResolver.shellQuote("1") + JobParamResolver.shellQuote("2"), r.text)
    }

    @Test
    fun singleBraceIsNotAPlaceholder() {
        val r = WorkflowVarResolver.render("echo {x}", emptyMap(), emptyMap())
        assertTrue("单花括号不该被当成占位符", r.unresolved.isEmpty())
        assertEquals("echo {x}", r.text)
    }

    @Test
    fun rawModifierIsRecognised() {
        val r = WorkflowVarResolver.render("{{k|raw}}", mapOf("k" to "a b"), emptyMap())
        assertTrue(r.isClean)
        assertEquals("a b", r.text)
    }

    @Test
    fun rawModifierToleratesSpaces() {
        val r = WorkflowVarResolver.render("{{k | raw}}", mapOf("k" to "a b"), emptyMap())
        assertEquals("a b", r.text)
    }
}
