package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.rikkahub.R

/**
 * job_* 工具的聊天内展示。
 *
 * 目标: 让用户一眼看懂"AI 要干什么、为什么", 而不是先看到一坨 JSON。
 * - 标题优先用 AI 填的 reason（为什么跑）/ description（这个任务是干什么的）;
 * - 摘要显示命令行本身（等宽、最多几行）;
 * - 详情仍是完整 JSON（便于排查），但放在展开之后。
 */
abstract class JobToolUI(
    override val toolName: String,
    private val titleRes: Int,
) : ToolUIRenderer {

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Cpu

    /** 优先展示的理由: job_start 的 reason / 定义的 description */
    protected fun reasonOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("reason")
            ?: context.arguments.getStringContent("description")

    @Composable
    protected fun label(): String = stringResource(titleRes)

    @Composable
    override fun title(context: ToolUIContext): String {
        val reason = reasonOf(context)
        val name = context.arguments.getStringContent("name")
            ?: context.arguments.getStringContent("def_id")
            ?: context.arguments.getStringContent("job_id")
        return when {
            !reason.isNullOrBlank() -> reason
            !name.isNullOrBlank() -> label() + " · " + name
            else -> label()
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("command") != null ||
            context.arguments.getStringContent("def_id") != null ||
            context.arguments.getStringContent("job_id") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val command = context.arguments.getStringContent("command")
        val cwd = context.arguments.getStringContent("cwd")
        val mode = context.arguments.getStringContent("mode")
        val target = context.arguments.getStringContent("def_id")
            ?: context.arguments.getStringContent("job_id")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!command.isNullOrBlank()) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!target.isNullOrBlank()) {
                    MetaText(stringResource(R.string.tool_ui_job_target, target))
                }
                if (!mode.isNullOrBlank()) {
                    MetaText(stringResource(R.string.tool_ui_job_mode, mode))
                }
                if (!cwd.isNullOrBlank()) {
                    MetaText(stringResource(R.string.tool_ui_job_cwd, cwd))
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

object JobStartToolUI : JobToolUI("job_start", R.string.tool_ui_job_start)

object JobRunToolUI : JobToolUI("job_run", R.string.tool_ui_job_run)

object JobListToolUI : JobToolUI("job_list", R.string.tool_ui_job_list)

object JobStatusToolUI : JobToolUI("job_status", R.string.tool_ui_job_status)

object JobLogsToolUI : JobToolUI("job_logs", R.string.tool_ui_job_logs)

object JobWaitToolUI : JobToolUI("job_wait", R.string.tool_ui_job_wait)

object JobKillToolUI : JobToolUI("job_kill", R.string.tool_ui_job_kill)

object JobRestartToolUI : JobToolUI("job_restart", R.string.tool_ui_job_restart)

object JobRemoveToolUI : JobToolUI("job_remove", R.string.tool_ui_job_remove)

object JobDefCreateToolUI : JobToolUI("job_def_create", R.string.tool_ui_job_def_create)

object JobDefUpdateToolUI : JobToolUI("job_def_update", R.string.tool_ui_job_def_update)

object JobDefListToolUI : JobToolUI("job_def_list", R.string.tool_ui_job_def_list)

object JobDefRemoveToolUI : JobToolUI("job_def_remove", R.string.tool_ui_job_def_remove)

object JobSendToolUI : JobToolUI("job_send", R.string.tool_ui_job_send)

object JobScreenToolUI : JobToolUI("job_screen", R.string.tool_ui_job_screen)
