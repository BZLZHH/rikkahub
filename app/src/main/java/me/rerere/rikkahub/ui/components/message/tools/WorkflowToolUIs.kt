package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.runs.formatTokens
import me.rerere.rikkahub.ui.pages.runs.statusLabel

/**
 * workflow_* 工具的聊天内展示。
 *
 * 在此之前这三个工具**一个渲染器都没注册**, 走的是默认的 JSON 展示 ——
 * 用户看到的是 `{"runId":"…","name":"…","status":"RUNNING",…}` 一整坨, 而不是"我起了个什么活"。
 *
 * 与 [SubagentToolUI] 同一目标（让用户一眼看懂 AI 派了什么活）, 差别在于 workflow 有**自己的运行页**,
 * 所以卡片上还要能**跳过去看**。
 */
abstract class WorkflowToolUI(
    override val toolName: String,
    private val label: String,
) : ToolUIRenderer {

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GitFork

    protected fun argumentString(context: ToolUIContext, key: String): String? =
        context.arguments.getStringContent(key)

    protected fun contentString(context: ToolUIContext, key: String): String? =
        context.content?.getStringContent(key)

    /** 这次运行的名字: title 优先, 其次已保存脚本的 name。 */
    protected fun runName(context: ToolUIContext): String? =
        argumentString(context, "title")?.takeIf { it.isNotBlank() }
            ?: argumentString(context, "name")?.takeIf { it.isNotBlank() }
            ?: contentString(context, "name")?.takeIf { it.isNotBlank() }

    protected fun runId(context: ToolUIContext): String? =
        contentString(context, "runId")?.takeIf { it.isNotBlank() }
            ?: argumentString(context, "run_id")?.takeIf { it.isNotBlank() }

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = runName(context)
        val id = runId(context)
        return when {
            name != null -> name
            id != null -> label + " · " + id.take(8)
            else -> label
        }
    }

    @Composable
    protected fun statusLine(context: ToolUIContext) {
        val status = contentString(context, "status")
        val phase = contentString(context, "phase")
        val agents = contentString(context, "agentsCalled")
        val reused = contentString(context, "agentsReused")
        val model = contentString(context, "model")
        val error = contentString(context, "error")

        val parts = buildList {
            status?.let { add(statusLabel(it)) }
            model?.let { add(it) }
            phase?.let { add(it) }
            agents?.let { add(it + " agents") }
            reused?.takeIf { it != "0" }?.let { add(it + " 复用") }
        }
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    /** 卡片上直接给一个"跳过去看"的入口 —— 光有 runId 用户没法用。 */
    @Composable
    protected fun openRunAction(context: ToolUIContext, onDismissRequest: (() -> Unit)? = null) {
        val runId = runId(context) ?: return
        val nav = LocalNavController.current
        TextButton(
            onClick = {
                onDismissRequest?.invoke()
                nav.navigate(Screen.WorkflowRun(runId))
            },
        ) {
            Text("查看运行")
        }
    }
}

object WorkflowRunToolUI : WorkflowToolUI("workflow_run", "运行 workflow") {

    override fun hasSummary(context: ToolUIContext): Boolean =
        runId(context) != null || argumentString(context, "script") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            statusLine(context)
            openRunAction(context)
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = runName(context) ?: "workflow",
                style = MaterialTheme.typography.titleSmall,
            )
            statusLine(context)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                openRunAction(context, onDismissRequest)
            }
            DefaultToolPreview(context = context)
        }
    }
}

object WorkflowStatusToolUI : WorkflowToolUI("workflow_status", "workflow 状态") {

    override fun hasSummary(context: ToolUIContext): Boolean = runId(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            statusLine(context)
            openRunAction(context)
        }
    }
}

object WorkflowKillToolUI : WorkflowToolUI("workflow_kill", "停止 workflow") {

    override fun hasSummary(context: ToolUIContext): Boolean = runId(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val requested = contentString(context, "requested")
        Text(
            text = "停止请求" + if (requested == "true") "已发出" else "未生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
