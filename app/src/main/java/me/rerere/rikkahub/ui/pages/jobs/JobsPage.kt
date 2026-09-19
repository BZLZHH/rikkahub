package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 全局后台任务页（主页面侧边栏 / 会话内均可进入）。
 *
 * 与 workspace 详情页的「Jobs」页签的区别: 这里汇总所有 workspace 的任务,
 * 并可按"本会话"过滤（从会话内进入时默认过滤到该会话发起的任务）。
 */
@Composable
fun JobsPage(conversationId: String? = null) {
    val manager: WorkspaceJobManager = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val jobs by manager.recentJobsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val running by manager.runningCount.collectAsStateWithLifecycle()

    var onlyRunning by remember { mutableStateOf(false) }
    var onlyConversation by remember { mutableStateOf(conversationId != null) }
    var logTarget by remember { mutableStateOf<WorkspaceJobEntity?>(null) }

    val workspaceNames = remember(workspaces) { workspaces.associate { it.id to it.name } }
    val filtered = jobs.filter { job ->
        (!onlyRunning || job.status == WorkspaceJobStatus.RUNNING.name) &&
            (!onlyConversation || job.conversationId == conversationId)
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
            item("filters") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.jobs_page_running_count, running, WorkspaceJobManager.MAX_CONCURRENT_GLOBAL))
                    },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                if (onlyRunning) {
                                    stringResource(R.string.jobs_page_filter_all)
                                } else {
                                    stringResource(R.string.jobs_page_filter_running)
                                }
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.jobs_page_filter_hint)) },
                        trailingContent = {
                            FilterChip(
                                selected = onlyRunning,
                                onClick = { onlyRunning = !onlyRunning },
                                label = { Text(stringResource(R.string.jobs_page_filter_running)) },
                            )
                        },
                    )
                    if (conversationId != null) {
                        item(
                            headlineContent = { Text(stringResource(R.string.jobs_page_filter_conversation)) },
                            supportingContent = { Text(stringResource(R.string.jobs_page_filter_conversation_hint)) },
                            trailingContent = {
                                FilterChip(
                                    selected = onlyConversation,
                                    onClick = { onlyConversation = !onlyConversation },
                                    label = { Text(stringResource(R.string.jobs_page_filter_conversation_chip)) },
                                )
                            },
                        )
                    }
                }
            }

            item("jobs") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    if (filtered.isEmpty()) {
                        item(headlineContent = { Text(stringResource(R.string.workspace_jobs_empty)) })
                    }
                    filtered.take(100).forEach { job ->
                        item(
                            onClick = { logTarget = job },
                            headlineContent = { Text(job.name) },
                            supportingContent = {
                                Text(
                                    (workspaceNames[job.workspaceId]?.let { it + " · " } ?: "") + job.jobSubtitle()
                                )
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
                }
            }
        }
    }

    logTarget?.let { job ->
        JobLogDialog(job = job, manager = manager, onDismiss = { logTarget = null })
    }
}
