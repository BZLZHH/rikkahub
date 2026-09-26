package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作区工具的默认审批策略。
 *
 * 锁两件事:
 * 1. **shell 默认不审批**（用户要求）。原来是 true, 而首启向导里"自动审批"默认开启 ——
 *    两个默认值互相打架时, 用户看到的是"明明开了自动审批, 还是一直弹窗";
 * 2. **工作区里的手动覆盖仍然优先** —— 已经在某台设备上开过审批的, 不该被这次改动悄悄关掉。
 */
class WorkspaceToolApprovalTest {

    @Test
    fun shellDoesNotRequireApprovalByDefault() {
        assertEquals(
            "shell 默认不该要审批",
            false,
            resolveWorkspaceToolApproval("workspace_shell", emptyMap()),
        )
    }

    @Test
    fun explicitPerWorkspaceOverrideStillWins() {
        assertEquals(
            "用户手动开过审批就仍然要问",
            true,
            resolveWorkspaceToolApproval("workspace_shell", mapOf("workspace_shell" to true)),
        )
    }

    @Test
    fun fileToolsStayApprovalFree() {
        listOf("workspace_read_file", "workspace_write_file", "workspace_edit_file").forEach { name ->
            assertEquals(name + " 不该要审批", false, resolveWorkspaceToolApproval(name, emptyMap()))
        }
    }

    @Test
    fun unknownToolDefaultsToNoApproval() {
        // 兜底是 false: 表里没登记的按"不打扰用户"处理
        assertEquals(false, resolveWorkspaceToolApproval("something_new", emptyMap()))
    }

    @Test
    fun overrideCanAlsoTurnApprovalOff() {
        assertEquals(
            "覆盖成 false 也要生效",
            false,
            resolveWorkspaceToolApproval("workspace_shell", mapOf("workspace_shell" to false)),
        )
    }
}
