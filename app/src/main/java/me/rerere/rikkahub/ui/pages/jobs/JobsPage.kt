package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val RECENT_LIMIT = 5

/**
 * 全局后台任务页（侧边栏 / 会话内进入）。
 *
 * 可读性优先: 每行第一眼看到的是"这件事是干什么的"（AI 写的理由），状态用中文短词 + 颜色点，
 * 时间用"3 分钟前 / 跑了 8m40s"这种人话; 可操作性: 每行都能看日志/重跑/复制命令/停止/删除，
 * 顶部还能自己新建任务。
 */
@Composable
fun JobsPage(conversationId: String? = null) {
    val manager: WorkspaceJobManager = koinInject()
    val scheduleEngine: JobScheduleEngine = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val jobs by manager.recentJobsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val running by manager.runningCount.collectAsStateWithLifecycle()

    var onlyConversation by remember { mutableStateOf(conversationId != null) }
    var showAllRecent by remember { mutableStateOf(false) }
    var logTarget by remember { mutableStateOf<WorkspaceJobEntity?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    val workspaceNames = remember(workspaces) { workspaces.associate { it.id to it.name } }
    val scoped = jobs.filter { !onlyConversation || it.conversationId == conversationId }
    val runningJobs = scoped.filter { WorkspaceJobStatus.from(it.status) == WorkspaceJobStatus.RUNNING }
    val recentJobs = scoped.filter { WorkspaceJobStatus.from(it.status).isFinished }
    val todayCount = remember(scoped) {
        val dayStart = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        scoped.count { WorkspaceJobStatus.from(it.status).isFinished && (it.finishedAt ?: 0) > dayStart }
    }
    val copied = stringResource(R.string.job_copied)
    val noWorkspace = stringResource(R.string.jobs_no_workspace)
    val actionFailed = stringResource(R.string.jobs_action_failed)

    fun copyCommand(job: WorkspaceJobEntity) {
        clipboard.setText(AnnotatedString(job.command))
        toaster.show(copied)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.jobs_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("overview") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.jobs_overview_running, runningJobs.size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.jobs_overview_today, todayCount))
                        },
                        trailingContent = {
                            Button(onClick = { showCreate = true }) {
                                Text(stringResource(R.string.jobs_action_new))
                            }
                        },
                    )
                    if (conversationId != null) {
                        item(
                            headlineContent = { Text(stringResource(R.string.jobs_scope_title)) },
                            supportingContent = { Text(stringResource(R.string.jobs_scope_hint)) },
                            trailingContent = {
                                FilterChip(
                                    selected = onlyConversation,
                                    onClick = { onlyConversation = !onlyConversation },
                                    label = { Text(stringResource(R.string.jobs_scope_only_this_chat)) },
                                )
                            },
                        )
                    }
                }
            }

            item("running") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(
                            if (runningJobs.isEmpty()) {
                                stringResource(R.string.jobs_section_running_empty)
                            } else {
                                stringResource(R.string.jobs_section_running, runningJobs.size)
                            }
                        )
                    },
                ) {
                    if (runningJobs.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.jobs_empty_running_hint)) },
                        )
                    }
                    runningJobs.forEach { job ->
                        item(
                            onClick = { logTarget = job },
                            headlineContent = { Text(job.displayTitle()) },
                            supportingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    JobStatusDot(job.status)
                                    Text(job.displaySubtitle(workspaceNames[job.workspaceId]))
                                }
                            },
                            trailingContent = {
                                JobActionsMenu(
                                    job = job,
                                    running = true,
                                    onViewLogs = { logTarget = job },
                                    onRerun = {
                                        scope.launch {
                                            if (manager.restart(job.id) == null) toaster.show(actionFailed)
                                        }
                                    },
                                    onCopy = { copyCommand(job) },
                                    onStop = {
                                        scope.launch {
                                            val stopped = manager.kill(job.id)
                                            if (stopped) manager.waitFor(job.id, 5_000) else toaster.show(actionFailed)
                                        }
                                    },
                                    onDelete = {},
                                )
                            },
                        )
                    }
                }
            }

            item("recent") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.jobs_section_recent)) },
                ) {
                    if (recentJobs.isEmpty()) {
                        item(headlineContent = { Text(stringResource(R.string.jobs_empty_hint)) })
                    }
                    val visible = if (showAllRecent) recentJobs else recentJobs.take(RECENT_LIMIT)
                    visible.forEach { job ->
                        item(
                            onClick = { logTarget = job },
                            headlineContent = { Text(job.displayTitle()) },
                            supportingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    JobStatusDot(job.status)
                                    Text(job.displaySubtitle(workspaceNames[job.workspaceId]))
                                }
                            },
                            trailingContent = {
                                JobActionsMenu(
                                    job = job,
                                    running = false,
                                    onViewLogs = { logTarget = job },
                                    onRerun = {
                                        scope.launch {
                                            if (manager.restart(job.id) == null) toaster.show(actionFailed)
                                        }
                                    },
                                    onCopy = { copyCommand(job) },
                                    onStop = {},
                                    onDelete = { scope.launch { manager.removeJob(job.id) } },
                                )
                            },
                        )
                    }
                    if (recentJobs.size > RECENT_LIMIT) {
                        item(
                            onClick = { showAllRecent = !showAllRecent },
                            headlineContent = {
                                Text(
                                    if (showAllRecent) {
                                        stringResource(R.string.jobs_show_less)
                                    } else {
                                        stringResource(R.string.jobs_show_all, recentJobs.size)
                                    }
                                )
                            },
                        )
                    }
                }
            }

            item("defs") {
                val defs by manager.recentDefsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.jobs_section_defs)) },
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
    }

    logTarget?.let { job ->
        JobLogDialog(job = job, manager = manager, onDismiss = { logTarget = null })
    }

    if (showCreate) {
        NewJobDialog(
            onDismiss = { showCreate = false },
            onCreate = { command, reason, mode, cwd ->
                showCreate = false
                scope.launch {
                    val workspaceId = workspaces.firstOrNull()?.id
                    if (workspaceId == null) {
                        toaster.show(noWorkspace)
                        return@launch
                    }
                    runCatching {
                        manager.startAdHoc(
                            workspaceId = workspaceId,
                            command = command,
                            reason = reason,
                            cwd = cwd,
                            mode = mode,
                        )
                    }.onFailure { toaster.show(it.message ?: "failed to start") }
                }
            },
        )
    }
}
