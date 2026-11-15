package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.permission.ConsentInfo
import me.rerere.rikkahub.data.permission.PermissionChannel
import me.rerere.rikkahub.data.permission.PermissionContext
import me.rerere.rikkahub.data.permission.PermissionDecision
import me.rerere.rikkahub.data.permission.PermissionKernel
import me.rerere.rikkahub.data.permission.PermissionNormalizer
import me.rerere.rikkahub.data.permission.ToolEffect
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceLayout

/**
 * Apply the Enhanced permission kernel before the shared agent loop can execute a tool.
 * Both interactive chat and unattended agents use this path. Explicit user decisions and
 * completed calls survive resumption; only new Auto calls require a policy decision.
 */
internal fun UIMessage.withToolPermissions(
    permission: PermissionContext,
    workspaceLayout: WorkspaceLayout? = null,
    workspaceCwd: String? = null,
): UIMessage = copy(
    parts = parts.map { part ->
        if (part !is UIMessagePart.Tool || part.isExecuted || part.approvalState != ToolApprovalState.Auto) {
            return@map part
        }
        val call = PermissionNormalizer.normalize(part.toolName, part.inputAsJson(), workspaceLayout, workspaceCwd)
        if (ToolEffect.HUMAN in call.effects && permission.channel == PermissionChannel.INTERACTIVE) {
            // ask_user is an interaction primitive, not a request for permission.
            return@map part.copy(approvalState = ToolApprovalState.Pending)
        }
        when (val decision = PermissionKernel.evaluate(call, permission)) {
            is PermissionDecision.Allow -> part.copy(approvalState = ToolApprovalState.Approved)
            is PermissionDecision.Ask -> part.copy(
                approvalState = ToolApprovalState.Pending,
                metadata = part.metadata.withConsent(decision.info),
            )
            is PermissionDecision.Deny -> part.copy(approvalState = ToolApprovalState.Denied(decision.message))
        }
    }
)

private fun JsonObject?.withConsent(info: ConsentInfo): JsonObject = JsonObject(
    orEmpty().toMutableMap().apply {
        put("consent", JsonInstant.encodeToJsonElement(ConsentInfo.serializer(), info))
    }
)
