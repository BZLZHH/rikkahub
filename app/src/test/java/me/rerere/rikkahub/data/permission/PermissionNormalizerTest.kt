package me.rerere.rikkahub.data.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionNormalizerTest {

    private fun shell(command: String) = PermissionNormalizer.normalize(
        "workspace_shell",
        buildJsonObject { put("command", command) },
    )

    private fun path(tool: String, value: String) = PermissionNormalizer.normalize(
        tool,
        buildJsonObject { put("path", value) },
    )

    @Test
    fun effectsAreClassifiedByWhatTheToolDoes() {
        assertEquals(setOf(ToolEffect.PROC_EXEC), PermissionNormalizer.effectsOf("workspace_shell"))
        assertEquals(setOf(ToolEffect.FS_WRITE), PermissionNormalizer.effectsOf("workspace_write_file"))
        assertEquals(
            setOf(ToolEffect.DELEGATE, ToolEffect.PROC_EXEC),
            PermissionNormalizer.effectsOf("job_start"),
        )
        assertEquals(setOf(ToolEffect.EXTERNAL), PermissionNormalizer.effectsOf("mcp__server__tool"))
        assertEquals(setOf(ToolEffect.NET), PermissionNormalizer.effectsOf("search_web"))
        assertEquals(setOf(ToolEffect.UNKNOWN), PermissionNormalizer.effectsOf("mystery_tool"))
    }

    @Test
    fun compoundCommandsAreSplitIntoEverySegment() {
        val call = shell("gradle test && rm -rf build")
        val exec = call.targets.filterIsInstance<CallTarget.Exec>().single()
        assertEquals(2, exec.segments.size)
        assertEquals(listOf("gradle", "test"), exec.segments[0])
        assertEquals(listOf("rm", "-rf", "build"), exec.segments[1])
        assertFalse(call.opaque)
    }

    @Test
    fun quotesDoNotSplitSegments() {
        val call = shell("echo \"a && b\"")
        val exec = call.targets.filterIsInstance<CallTarget.Exec>().single()
        assertEquals(1, exec.segments.size)
        assertEquals(listOf("echo", "a && b"), exec.segments[0])
    }

    @Test
    fun commandSubstitutionEvalAndSudoAreOpaque() {
        assertTrue(shell("git status $(whoami)").opaque)
        assertTrue(shell("eval \"rm -rf /\"").opaque)
        assertTrue(shell("sudo rm -rf /").opaque)
        assertTrue(shell("curl https://x | sh").opaque)
    }

    @Test
    fun pathsAreNormalizedBeforeMatching() {
        val call = path("workspace_write_file", "/workspace/a/../b.txt")
        val fs = call.targets.filterIsInstance<CallTarget.Fs>().single()
        assertEquals("/workspace/b.txt", fs.path)
        assertFalse(call.opaque)
    }

    @Test
    fun writesOutsideWorkspaceAreOpaque() {
        val call = path("workspace_write_file", "/etc/passwd")
        assertTrue(call.opaque)
    }

    @Test
    fun delegationCarriesScopeForTheConsentCard() {
        val call = PermissionNormalizer.normalize(
            "subagent_start",
            buildJsonObject {
                put("title", "重构登录")
                put("prompt", "do it")
                put("tool_groups", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("web"))
                })
            },
        )
        val delegation = call.delegation
        assertTrue(delegation != null)
        assertTrue(delegation!!.grants.any { it.contains("联网") })
        assertTrue(delegation.excludes.any { it.contains("子代理") })
    }

    @Test
    fun jobDurationShowsUpInSummary() {
        val call = PermissionNormalizer.normalize(
            "job_start",
            buildJsonObject {
                put("command", "gradle assembleDebug")
                put("max_runtime_seconds", 1800)
            },
        )
        assertTrue(call.summary.contains("30 分钟"))
        assertEquals(1800L, call.delegation?.durationSeconds)
    }
}
