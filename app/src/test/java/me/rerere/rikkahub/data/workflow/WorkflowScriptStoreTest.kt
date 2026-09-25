package me.rerere.rikkahub.data.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本名的白名单校验。
 *
 * 这是整个脚本存储的**安全边界**: 通过校验的名字会被直接拼进 shell 命令与路径,
 * 所以"哪些名字不许过"必须逐条锁住, 而不是靠"调用方不会传坏名字"。
 */
class WorkflowScriptStoreTest {

    @Test
    fun acceptsOrdinaryNames() {
        assertEquals("audit-routes", WorkflowScriptStore.requireValidName("audit-routes"))
        assertEquals("deep_research.v2", WorkflowScriptStore.requireValidName("deep_research.v2"))
        assertEquals("x", WorkflowScriptStore.requireValidName("  x  "))
    }

    @Test
    fun rejectsPathTraversalAndSeparators() {
        // 这几条要是漏了, 脚本就能被写到工作区别的地方去（越过了写工具的路径约束）
        listOf("../evil", "a/b", "/abs", "..", "./x").forEach { bad ->
            assertThrows(
                "应当拒绝: " + bad,
                IllegalArgumentException::class.java,
            ) { WorkflowScriptStore.requireValidName(bad) }
        }
    }

    @Test
    fun rejectsShellMetacharacters() {
        // 名字会被拼进命令, 所以 shell 元字符一个都不能过
        listOf("a b", "a;ls", "a|b", "a&b", "a$(id)", "a`id`", "a\"b", "a\u0027b", "a>b", "a\nb")
            .forEach { bad ->
                assertThrows(
                    "应当拒绝: " + bad,
                    IllegalArgumentException::class.java,
                ) { WorkflowScriptStore.requireValidName(bad) }
            }
    }

    @Test
    fun rejectsHiddenAndEmptyAndTooLong() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowScriptStore.requireValidName(".hidden")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowScriptStore.requireValidName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowScriptStore.requireValidName("a".repeat(WorkflowScriptStore.MAX_NAME_LENGTH + 1))
        }
    }

    @Test
    fun pathLandsUnderTheConventionDirectory() {
        val path = WorkflowScriptStore.pathOf("audit")
        assertEquals("/workspace/.rhe/workflows/audit.js", path)
        assertTrue("必须在约定目录下: " + path, path.startsWith(WorkflowScriptStore.DIR + "/"))
    }
}
