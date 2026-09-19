package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.jobs.JobLogDialog
import me.rerere.rikkahub.ui.pages.jobs.jobDefSubtitle
import me.rerere.rikkahub.ui.pages.jobs.jobSubtitle
import org.koin.compose.koinInject

/**
 * workspace 详情页的「Jobs」页签: 运行实例 + 任务定义。
 * 全局视图（跨 workspace / 按会话过滤）见 [me.rerere.rikkahub.ui.pages.jobs.JobsPage]。
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
                        supportingContent = { Text(job.jobSubtitle()) },
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
                        supportingContent = { Text(def.jobDefSubtitle()) },
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
