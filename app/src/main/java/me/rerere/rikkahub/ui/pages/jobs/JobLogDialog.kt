package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobLogStream
import me.rerere.rikkahub.data.job.JobTrigger
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.job.trigger

/** 任务日志弹窗: 以游标增量跟随输出（pipe 看 stdout, pty 看屏幕快照）。 */
@Composable
fun JobLogDialog(
    job: WorkspaceJobEntity,
    manager: WorkspaceJobManager,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(job.id) { mutableStateOf("") }
    var cursor by remember(job.id) { mutableStateOf(0L) }
    var status by remember(job.id) { mutableStateOf(job.status) }
    val stream = if (WorkspaceJobMode.from(job.mode) == WorkspaceJobMode.PTY) {
        JobLogStream.SCREEN
    } else {
        JobLogStream.STDOUT
    }

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
        title = { Text(job.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.workspace_jobs_status) + ": " + status +
                        (job.exitCode?.let { " · exit=" + it } ?: "") +
                        (job.runtimeMs?.let { " · " + formatMillisShort(it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = text.ifEmpty { stringResource(R.string.workspace_jobs_logs_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_jobs_close)) }
        },
        dismissButton = {
            if (WorkspaceJobStatus.from(status) == WorkspaceJobStatus.RUNNING) {
                TextButton(onClick = { scope.launch { manager.kill(job.id) } }) {
                    Text(stringResource(R.string.workspace_jobs_stop))
                }
            }
        },
    )
}

fun WorkspaceJobEntity.jobSubtitle(): String {
    val parts = mutableListOf<String>()
    parts += WorkspaceJobStatus.from(status).name.lowercase()
    exitCode?.let { parts += "exit=" + it }
    runtimeMs?.let { parts += formatMillisShort(it) }
    deferredReason?.let { parts += it }
    if (status == WorkspaceJobStatus.FAILED.name || status == WorkspaceJobStatus.INTERRUPTED.name) {
        error?.let { parts += it }
    }
    return parts.joinToString(" · ")
}

fun WorkspaceJobDefEntity.jobDefSubtitle(): String {
    val parts = mutableListOf<String>()
    parts += when (val t = trigger()) {
        is JobTrigger.Cron -> "cron: " + t.expr
        is JobTrigger.Interval -> "every " + t.seconds + "s"
        is JobTrigger.Delay -> "in " + t.seconds + "s"
        is JobTrigger.Once -> "once"
        null -> "manual"
    }
    parts += "runs=" + runCount
    lastStatus?.let { parts += it }
    nextRunAt?.let { parts += "next=" + formatEpochShort(it) }
    return parts.joinToString(" · ")
}

fun formatMillisShort(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> seconds.toString() + "s"
        seconds < 3600 -> (seconds / 60).toString() + "m"
        else -> (seconds / 3600).toString() + "h" + ((seconds % 3600) / 60) + "m"
    }
}

fun formatEpochShort(epochMillis: Long): String {
    val delta = epochMillis - System.currentTimeMillis()
    return if (delta <= 0) "now" else "in " + formatMillisShort(delta)
}
