package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.jobs.JobActionsMenu
import me.rerere.rikkahub.ui.pages.jobs.JobLogDialog
import me.rerere.rikkahub.ui.pages.jobs.JobStatusDot
import me.rerere.rikkahub.ui.pages.jobs.displaySubtitle
import me.rerere.rikkahub.ui.pages.jobs.displayTitle
import org.koin.compose.koinInject

/**
 * workspace 详情页的「Jobs」页签。
 *
 * 与全局任务页同一套"人话"呈现: 标题=任务理由, 副标题=状态·时间·耗时, 行尾菜单可看日志/重跑/停止/删除。
 */
@Composable
fun WorkspaceJobsTab(workspaceId: String, modifier: Modifier = Modifier) {
    val manager: WorkspaceJobManager = koinInject()
    val scheduleEngine: JobScheduleEngine = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    val jobs by manager.jobsFlow(workspaceId).collectAsStateWithLifecycle(initialValue = emptyList())
    val defs by manager.defsFlow(workspaceId).collectAsStateWithLifecycle(initialValue = emptyList())
    var logTarget by remember { mutableStateOf<WorkspaceJobEntity?>(null) }
    val copied = stringResource(R.string.job_copied)

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
                    item(headlineContent = { Text(stringResource(R.string.jobs_empty_hint)) })
                }
                jobs.take(20).forEach { job ->
                    val running = WorkspaceJobStatus.from(job.status) == WorkspaceJobStatus.RUNNING
                    item(
                        onClick = { logTarget = job },
                        headlineContent = { Text(job.displayTitle()) },
                        supportingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                JobStatusDot(job.status)
                                Text(job.displaySubtitle())
                            }
                        },
                        trailingContent = {
                            JobActionsMenu(
                                job = job,
                                running = running,
                                onViewLogs = { logTarget = job },
                                onRerun = { scope.launch { manager.restart(job.id) } },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(job.command))
                                    toaster.show(copied)
                                },
                                onStop = { scope.launch { manager.kill(job.id) } },
                                onDelete = { scope.launch { manager.removeJob(job.id) } },
                            )
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
                    item(headlineContent = { Text(stringResource(R.string.jobs_empty_defs_hint)) })
                }
                defs.forEach { def ->
                    item(
                        headlineContent = { Text(def.description?.takeIf { it.isNotBlank() } ?: def.name) },
                        supportingContent = { Text(def.displaySubtitle()) },
                        trailingContent = {
                            Row {
                                Switch(
                                    checked = def.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            manager.setDefEnabled(def, enabled)
                                            scheduleEngine.reschedule(def.copy(enabled = enabled))
                                        }
                                    },
                                )
                                TextButton(onClick = { scope.launch { manager.runDefNow(def) } }) {
                                    Text(stringResource(R.string.jobs_action_run_now))
                                }
                                TextButton(onClick = { scope.launch { manager.deleteDef(def.id) } }) {
                                    Text(stringResource(R.string.job_action_delete))
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
