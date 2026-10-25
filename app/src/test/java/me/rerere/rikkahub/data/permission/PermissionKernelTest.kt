package me.rerere.rikkahub.data.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁决矩阵：档位、规则、信封、通道四者的组合必须可预测。
 */
class PermissionKernelTest {

    private fun shell(command: String) = PermissionNormalizer.normalize(
        "workspace_shell",
        buildJsonObject { put("command", command) },
    )

    private fun job(command: String) = PermissionNormalizer.normalize(
        "job_start",
        buildJsonObject { put("command", command) },
    )

    private fun context(
        mode: PermissionMode,
        rules: List<PermissionRule> = emptyList(),
        channel: PermissionChannel = PermissionChannel.INTERACTIVE,
        envelope: Set<ToolEffect> = PermissionContext.ALL_EFFECTS,
    ) = PermissionContext(
        mode = mode,
        channel = channel,
        rules = rules,
        envelope = envelope,
    )

    private fun allowRule(commandPrefix: String) = PermissionRule(
        id = "r_gradle",
        decision = RuleDecision.ALLOW,
        tool = "workspace_shell",
        effect = ToolEffect.PROC_EXEC,
        commandPrefix = commandPrefix,
    )

    @Test
    fun askModeAsksForExecButAllowsReads() {
        val exec = PermissionKernel.evaluate(shell("gradle test"), context(PermissionMode.ASK))
        assertTrue(exec is PermissionDecision.Ask)

        val read = PermissionNormalizer.normalize(
            "workspace_read_file",
            buildJsonObject { put("path", "/workspace/a.txt") },
        )
        val allowed = PermissionKernel.evaluate(read, context(PermissionMode.ASK))
        assertTrue(allowed is PermissionDecision.Allow)
    }

    @Test
    fun trustedModeRunsWorkspaceCommandsWithoutAsking() {
        val decision = PermissionKernel.evaluate(shell("gradle test"), context(PermissionMode.TRUSTED))
        assertTrue(decision is PermissionDecision.Allow)
    }

    @Test
    fun ruleAllowsOnlyTheRememberedCommandFamily() {
        val rules = listOf(allowRule("gradle"))
        val gradle = PermissionKernel.evaluate(shell("gradle test"), context(PermissionMode.ASK, rules))
        assertTrue(gradle is PermissionDecision.Allow)
        assertEquals("r_gradle", (gradle as PermissionDecision.Allow).ruleId)

        val other = PermissionKernel.evaluate(shell("rm -rf build"), context(PermissionMode.ASK, rules))
        assertTrue("别的命令族必须重新问", other is PermissionDecision.Ask)
    }

    @Test
    fun ruleDoesNotCoverCompoundCommandsWithOtherBinaries() {
        val decision = PermissionKernel.evaluate(
            shell("gradle test && rm -rf build"),
            context(PermissionMode.ASK, listOf(allowRule("gradle"))),
        )
        assertTrue("规则要求每一段都属于同一个命令族", decision is PermissionDecision.Ask)
    }

    @Test
    fun opaqueCommandsCannotBeAllowedByRuleOrTrustedMode() {
        val rules = listOf(allowRule("git"))
        val trusted = PermissionKernel.evaluate(
            shell("git status $(rm -rf /)"),
            context(PermissionMode.TRUSTED, rules),
        )
        assertTrue("解析不确定的命令永远要问", trusted is PermissionDecision.Ask)
    }

    @Test
    fun denyRuleBeatsAllowRule() {
        val rules = listOf(
            PermissionRule(id = "a", decision = RuleDecision.ALLOW, tool = "workspace_shell"),
            PermissionRule(id = "b", decision = RuleDecision.DENY, tool = "workspace_shell"),
        )
        val decision = PermissionKernel.evaluate(shell("ls"), context(PermissionMode.TRUSTED, rules))
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun backgroundEnvelopeRejectsDelegation() {
        val decision = PermissionKernel.evaluate(
            job("sleep 10"),
            PermissionContext.background(),
        )
        assertTrue(decision is PermissionDecision.Deny)
        assertEquals("out_of_envelope", (decision as PermissionDecision.Deny).code)
    }

    @Test
    fun backgroundContextWithoutAutoAllowDeniesInsteadOfHanging() {
        val decision = PermissionKernel.evaluate(
            shell("ls"),
            PermissionContext(
                mode = PermissionMode.ASK,
                channel = PermissionChannel.BACKGROUND,
                envelope = PermissionContext.ALL_EFFECTS,
            ),
        )
        assertTrue(decision is PermissionDecision.Deny)
        assertEquals("unattended", (decision as PermissionDecision.Deny).code)
    }

    @Test
    fun proposalForShellIsTheCommandFamily() {
        val rule = PermissionNormalizer.propose(shell("gradle test"))
        assertEquals("gradle", rule?.commandPrefix)
        assertEquals("workspace_shell", rule?.tool)
    }

    @Test
    fun proposalForWriteIsTheParentDirectory() {
        val write = PermissionNormalizer.normalize(
            "workspace_write_file",
            buildJsonObject { put("path", "/workspace/proj/a.txt"); put("text", "x") },
        )
        val rule = PermissionNormalizer.propose(write)
        assertEquals("/workspace/proj/", rule?.pathPrefix)
    }
}
