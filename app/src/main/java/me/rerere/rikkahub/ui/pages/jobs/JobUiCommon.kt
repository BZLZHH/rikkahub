package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Stop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobTrigger
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.job.trigger

/**
 * 任务相关的"人话"文案。
 *
 * 原则: 用户看到的第一行是**这件事是干什么的**（AI 写的 reason / 定义的 description），
 * 第二行才是状态与耗时; 状态一律用中文短词 + 颜色点, 不暴露枚举名。
 */

@Composable
fun jobStatusLabel(status: String): String = stringResource(
    when (WorkspaceJobStatus.from(status)) {
        WorkspaceJobStatus.PENDING -> R.string.job_status_pending
        WorkspaceJobStatus.RUNNING -> R.string.job_status_running
        WorkspaceJobStatus.SUCCEEDED -> R.string.job_status_succeeded
        WorkspaceJobStatus.FAILED -> R.string.job_status_failed
        WorkspaceJobStatus.KILLED -> R.string.job_status_killed
        WorkspaceJobStatus.TIMED_OUT -> R.string.job_status_timed_out
        WorkspaceJobStatus.INTERRUPTED -> R.string.job_status_interrupted
        WorkspaceJobStatus.DEFERRED -> R.string.job_status_deferred
    }
)

@Composable
fun jobStatusColor(status: String): Color = when (WorkspaceJobStatus.from(status)) {
    WorkspaceJobStatus.RUNNING -> MaterialTheme.colorScheme.primary
    WorkspaceJobStatus.SUCCEEDED -> Color(0xFF2E7D32)
    WorkspaceJobStatus.FAILED, WorkspaceJobStatus.TIMED_OUT -> MaterialTheme.colorScheme.error
    WorkspaceJobStatus.KILLED, WorkspaceJobStatus.INTERRUPTED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun JobStatusDot(status: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(jobStatusColor(status), CircleShape)
    )
}

/** 第一行: 优先 AI 给的理由, 其次任务名 */
fun WorkspaceJobEntity.displayTitle(): String =
    reason?.takeIf { it.isNotBlank() } ?: name

/** 第二行: 状态 · 时间 · 耗时 · 退出码 */
@Composable
fun WorkspaceJobEntity.displaySubtitle(workspaceName: String? = null): String {
    val parts = mutableListOf<String>()
    parts += jobStatusLabel(status)
    if (WorkspaceJobStatus.from(status) == WorkspaceJobStatus.RUNNING) {
        startedAt?.let { parts += stringResource(R.string.job_running_for, durationText(System.currentTimeMillis() - it)) }
    } else {
        startedAt?.let { parts += stringResource(R.string.job_started_ago, relativeTime(it)) }
        runtimeMs?.let { if (it > 0) parts += durationText(it) }
    }
    if (WorkspaceJobStatus.from(status) == WorkspaceJobStatus.FAILED) {
        exitCode?.let { parts += stringResource(R.string.job_exit_code, it) }
    }
    deferredReason?.let { if (WorkspaceJobStatus.from(status) == WorkspaceJobStatus.DEFERRED) parts += it }
    workspaceName?.let { parts += it }
    parts += if (WorkspaceJobMode.from(mode) == WorkspaceJobMode.PTY) {
        stringResource(R.string.job_mode_pty)
    } else {
        stringResource(R.string.job_mode_pipe)
    }
    return parts.joinToString(" · ")
}

/** 定义的第二行: 什么时候跑 · 下次时间 · 上次结果 */
@Composable
fun WorkspaceJobDefEntity.displaySubtitle(): String {
    val parts = mutableListOf<String>()
    parts += when (val t = trigger()) {
        is JobTrigger.Cron -> stringResource(R.string.job_trigger_cron, t.expr)
        is JobTrigger.Interval -> stringResource(R.string.job_trigger_every, durationText(t.seconds * 1_000L))
        is JobTrigger.Delay -> stringResource(R.string.job_trigger_once_after, durationText(t.seconds * 1_000L))
        is JobTrigger.Once -> stringResource(R.string.job_trigger_once)
        null -> stringResource(R.string.job_trigger_manual)
    }
    if (!enabled) parts += stringResource(R.string.job_def_paused)
    nextRunAt?.let { parts += stringResource(R.string.job_def_next_run, relativeTime(it)) }
    if (runCount > 0) parts += stringResource(R.string.job_def_run_count, runCount)
    lastStatus?.let { parts += stringResource(R.string.job_def_last_status, jobStatusLabel(it)) }
    return parts.joinToString(" · ")
}

@Composable
fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    return when {
        delta < 60_000 -> stringResource(R.string.job_time_just_now)
        delta < 3_600_000 -> stringResource(R.string.job_time_minutes_ago, (delta / 60_000).toInt())
        delta < 86_400_000 -> stringResource(R.string.job_time_hours_ago, (delta / 3_600_000).toInt())
        else -> stringResource(R.string.job_time_days_ago, (delta / 86_400_000).toInt())
    }
}

/** 任务行的"更多"菜单: 查看日志 / 重跑 / 复制命令 / 停止 / 删除 */
@Composable
fun JobActionsMenu(
    job: WorkspaceJobEntity,
    running: Boolean,
    onViewLogs: () -> Unit,
    onRerun: () -> Unit,
    onCopy: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.job_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.job_action_logs)) },
                leadingIcon = { Icon(HugeIcons.FileView, null) },
                onClick = { expanded = false; onViewLogs() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.job_action_rerun)) },
                leadingIcon = { Icon(HugeIcons.Refresh01, null) },
                onClick = { expanded = false; onRerun() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.job_action_copy_command)) },
                leadingIcon = { Icon(HugeIcons.Copy01, null) },
                onClick = { expanded = false; onCopy() },
            )
            if (running) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.job_action_stop)) },
                    leadingIcon = { Icon(HugeIcons.Stop, null) },
                    onClick = { expanded = false; onStop() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.job_action_delete)) },
                    leadingIcon = { Icon(HugeIcons.Delete01, null) },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

fun durationText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> seconds.toString() + "s"
        seconds < 3600 -> (seconds / 60).toString() + "m" + (seconds % 60).toString().padStart(2, '0') + "s"
        else -> (seconds / 3600).toString() + "h" + ((seconds % 3600) / 60).toString().padStart(2, '0') + "m"
    }
}
