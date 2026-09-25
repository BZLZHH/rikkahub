package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobLogStream
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.ui.context.LocalToaster

/**
 * 任务详情弹窗: 人话标题 + 实时输出 + 常用操作（重跑 / 停止 / 复制）。
 */
@Composable
fun JobLogDialog(
    job: WorkspaceJobEntity,
    manager: WorkspaceJobManager,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    var text by remember(job.id) { mutableStateOf("") }
    var cursor by remember(job.id) { mutableStateOf(0L) }
    var status by remember(job.id) { mutableStateOf(job.status) }
    val copied = stringResource(R.string.job_copied)
    val actionFailed = stringResource(R.string.jobs_action_failed)
    val stream = if (WorkspaceJobMode.from(job.mode) == WorkspaceJobMode.PTY) {
        JobLogStream.SCREEN
    } else {
        JobLogStream.STDOUT
    }
    val running = WorkspaceJobStatus.from(status) == WorkspaceJobStatus.RUNNING

    LaunchedEffect(job.id) {
        while (true) {
            val chunk = manager.readLog(job.id, stream, cursor, 64 * 1024)
            if (chunk != null && chunk.text.isNotEmpty()) {
                text = (text + chunk.text).takeLast(64 * 1024)
                cursor = chunk.to
            }
            status = manager.getJob(job.id)?.status ?: status
            delay(1_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(job.displayTitle(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobStatusDot(status)
                    Text(
                        text = job.displaySubtitle() + (job.exitCode?.let { " · exit=$it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = job.command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = text.ifEmpty { stringResource(R.string.workspace_jobs_logs_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_jobs_close)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        toaster.show(copied)
                    }
                ) { Text(stringResource(R.string.job_action_copy_logs)) }
                if (running) {
                    TextButton(onClick = {
                        scope.launch {
                            val stopped = manager.kill(job.id)
                            if (stopped) manager.waitFor(job.id, 5_000) else toaster.show(actionFailed)
                        }
                    }) {
                        Text(stringResource(R.string.job_action_stop))
                    }
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            if (manager.restart(job.id) == null) toaster.show(actionFailed)
                        }
                    }) {
                        Text(stringResource(R.string.job_action_rerun))
                    }
                }
            }
        },
    )
}
