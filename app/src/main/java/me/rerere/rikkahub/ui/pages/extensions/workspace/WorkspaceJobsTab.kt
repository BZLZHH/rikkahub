package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobLogStream
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.JobTrigger
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.job.trigger
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

/**
 * workspace 详情页的「任务」页签: 运行实例 + 任务定义。
 * 只做观察与控制, 创建任务交给 AI（job_def_create）或后续版本的编辑页。
 */
@Composable
fun WorkspaceJobsTab(workspaceId: String, modifier: Modifier = Modifier) {
    val manager: WorkspaceJobManager = koinInject()
    val scheduleEngine: JobScheduleEngine = koinInject()
    val scope = rememberCoroutineScope()
    val jobs by manager.jobsFlow(workspaceId).collectAsStateWithLifecycle(initialValue = emptyList())
    val defs by manager.defsFlow(workspaceId).collectAsStateWithLifecycle(initialValue = emptyList())
    var logTarget by remember { mutableStateOf<WorkspaceJobEntity?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("jobs") {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.workspace_jobs_running_title)) },
            ) {
                if (jobs.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.workspace_jobs_empty)) })
                }
                jobs.take(20).forEach { job ->
                    item(
                        onClick = { logTarget = job },
                        headlineContent = { Text(job.name) },
                        supportingContent = {
                            Text(job.subtitle())
                        },
                        trailingContent = {
                            if (WorkspaceJobStatus.from(job.status) == WorkspaceJobStatus.RUNNING) {
                                IconButton(onClick = { scope.launch { manager.kill(job.id) } }) {
                                    Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.workspace_jobs_stop))
                                }
                            } else {
                                IconButton(onClick = { scope.launch { manager.removeJob(job.id) } }) {
                                    Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.workspace_jobs_delete))
                                }
                            }
                        },
                    )
                }
                if (jobs.any { WorkspaceJobStatus.from(it.status).isFinished }) {
                    item(
                        onClick = { scope.launch { manager.clearFinished(workspaceId) } },
                        headlineContent = { Text(stringResource(R.string.workspace_jobs_clear_finished)) },
                    )
                }
            }
        }

        item("defs") {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.workspace_jobs_defs_title)) },
            ) {
                if (defs.isEmpty()) {
                    item(headlineContent = { Text(stringResource(R.string.workspace_jobs_defs_empty)) })
                }
                defs.forEach { def ->
                    item(
                        headlineContent = { Text(def.name) },
                        supportingContent = { Text(def.subtitle()) },
                        trailingContent = {
                            androidx.compose.foundation.layout.Row {
                                Switch(
                                    checked = def.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            manager.setDefEnabled(def, enabled)
                                            scheduleEngine.reschedule(def.copy(enabled = enabled))
                                        }
                                    },
                                )
                                IconButton(onClick = { scope.launch { manager.runDefNow(def) } }) {
                                    Icon(HugeIcons.Play, contentDescription = stringResource(R.string.workspace_jobs_run_now))
                                }
                                IconButton(onClick = { scope.launch { manager.deleteDef(def.id) } }) {
                                    Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.workspace_jobs_delete))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    logTarget?.let { job ->
        JobLogDialog(job = job, manager = manager, onDismiss = { logTarget = null })
    }
}

@Composable
private fun JobLogDialog(
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
                        (job.exitCode?.let { " · exit=" + it } ?: ""),
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

private fun WorkspaceJobEntity.subtitle(): String {
    val parts = mutableListOf<String>()
    parts += WorkspaceJobStatus.from(status).name.lowercase()
    exitCode?.let { parts += "exit=$it" }
    runtimeMs?.let { parts += formatMillis(it) }
    deferredReason?.let { parts += it }
    error?.let { if (status == WorkspaceJobStatus.FAILED.name || status == WorkspaceJobStatus.INTERRUPTED.name) parts += it }
    return parts.joinToString(" · ")
}

private fun WorkspaceJobDefEntity.subtitle(): String {
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
    nextRunAt?.let { parts += "next=" + formatEpoch(it) }
    return parts.joinToString(" · ")
}

private fun formatMillis(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> seconds.toString() + "s"
        seconds < 3600 -> (seconds / 60).toString() + "m"
        else -> (seconds / 3600).toString() + "h" + ((seconds % 3600) / 60) + "m"
    }
}

private fun formatEpoch(epochMillis: Long): String {
    val delta = epochMillis - System.currentTimeMillis()
    return if (delta <= 0) "now" else "in " + formatMillis(delta)
}
