package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.Workflow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.run.RunOrchestrator
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.data.workflow.WorkflowRunner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val RECENT_LIMIT = 40

/**
 * 运行中心: workflow 与子代理放在同一屏里看。
 *
 * 视觉上刻意与设置页/任务页保持一致：CardGroup + ListItem，状态用图标颜色与右侧短词表达，
 * 不再单独造一套状态条/徽章 —— "新增界面"不应该看起来像另一个 App。
 */
@Composable
fun RunsPage(conversationId: String? = null) {
    val workflowDao: WorkflowRunDAO = koinInject()
    val agentDao: AgentRunDAO = koinInject()
    val nav = LocalNavController.current
    val workflowRunner: WorkflowRunner = koinInject()
    val orchestrator: RunOrchestrator = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val alreadyFinished = stringResource(R.string.runs_finished_toast)

    val workflowRuns by workflowDao.listRecentFlow(RECENT_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val agentRuns by agentDao.listRecentFlow(RECENT_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 耗时每秒走一次: 只在组合时算一次的话, 运行中的那一行看着像冻住了。
    val anyRunning = workflowRuns.any { !RunStatus.from(it.status).isFinished } ||
        agentRuns.any { !RunStatus.from(it.status).isFinished }
    val now = rememberTickingNow(anyRunning)

    val rows = remember(workflowRuns, agentRuns, conversationId, now) {
        val scoped = if (conversationId == null) {
            workflowRuns to agentRuns
        } else {
            workflowRuns.filter { it.conversationId == conversationId } to
                agentRuns.filter { it.conversationId == conversationId }
        }
        (scoped.first.map { it.toRow(now) } + scoped.second.map { it.toRow(now) })
            .sortedByDescending { it.createdAt }
    }

    val running = rows.filter { it.isRunning }
    val finished = rows.filterNot { it.isRunning }

    fun stop(row: RunRow) {
        scope.launch {
            val stopped = if (row.isWorkflow) {
                workflowRunner.kill(row.id)
            } else {
                orchestrator.kill(row.id)
            }
            if (!stopped) toaster.show(alreadyFinished)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.runs_page_title)) },
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
            if (running.isNotEmpty()) {
                item("running") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = {
                            Text(stringResource(R.string.runs_section_running, running.size))
                        },
                    ) {
                        running.forEach { row ->
                            item(
                                onClick = { open(nav, row) },
                                leadingContent = { RunKindIcon(row) },
                                headlineContent = {
                                    Text(
                                        text = row.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { RunSupporting(row) },
                                trailingContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = statusLabel(row.status, row.reused),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = statusColor(row.status, row.reused),
                                        )
                                        TextButton(
                                            onClick = { stop(row) },
                                            contentPadding = PaddingValues(
                                                horizontal = 8.dp,
                                                vertical = 0.dp,
                                            ),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.runs_stop),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (finished.isNotEmpty() || running.isEmpty()) {
                item("recent") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.runs_section_recent)) },
                    ) {
                        if (finished.isEmpty()) {
                            item(
                                headlineContent = {
                                    Text(stringResource(R.string.runs_empty_title))
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.runs_empty_desc))
                                },
                            )
                        }
                        finished.forEach { row ->
                            item(
                                onClick = { open(nav, row) },
                                leadingContent = { RunKindIcon(row) },
                                headlineContent = {
                                    Text(
                                        text = row.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { RunSupporting(row) },
                                trailingContent = {
                                    Text(
                                        text = statusLabel(row.status, row.reused),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor(row.status, row.reused),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun open(nav: Navigator, row: RunRow) {
    if (row.isWorkflow) {
        nav.navigate(Screen.WorkflowRun(row.id))
    } else {
        // 子代理没有"详情页"这一层: 它本身就只是一段子会话, 直接进去看。
        nav.navigate(Screen.SubSession(sessionId = row.id, title = row.title))
    }
}

@Composable
private fun RunKindIcon(row: RunRow) {
    Icon(
        imageVector = if (row.isWorkflow) HugeIcons.Workflow else HugeIcons.Robot01,
        contentDescription = null,
        tint = statusColor(row.status, row.reused),
    )
}

@Composable
private fun RunSupporting(row: RunRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        row.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = row.statsLine(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val KIND_WORKFLOW = "workflow"
private const val KIND_SUBAGENT = "subagent"

private data class RunRow(
    val id: String,
    val kind: String,
    val title: String,
    val status: String,
    val subtitle: String?,
    val reused: Boolean,
    val isRunning: Boolean,
    val parts: List<String>,
    val createdAt: Long,
) {
    val key: String get() = kind + ":" + id
    val isWorkflow: Boolean get() = kind == KIND_WORKFLOW
    fun statsLine(): String = parts.joinToString("  ·  ")
}

private fun WorkflowRunEntity.toRow(now: Long): RunRow {
    val duration = runtimeMs ?: startedAt?.let { now - it }
    val stats = buildList {
        modelName?.takeIf { it.isNotBlank() }?.let { add(it) }
        phase?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(agentsCalled.toString() + " agents")
        if (agentsReused > 0) add(agentsReused.toString() + " 复用")
        duration?.let { add(formatDuration(it)) }
        add(formatAgo(createdAt))
    }
    return RunRow(
        id = id,
        kind = KIND_WORKFLOW,
        title = name,
        status = status,
        subtitle = error,
        reused = false,
        isRunning = !RunStatus.from(status).isFinished,
        parts = stats,
        createdAt = createdAt,
    )
}

private fun AgentRunEntity.toRow(now: Long): RunRow {
    val duration = runtimeMs ?: startedAt?.let { now - it }
    val stats = buildList {
        add(stepsDone.toString() + " 轮")
        add(formatTokens(tokensIn + tokensOut) + " tok")
        duration?.let { add(formatDuration(it)) }
        add(formatAgo(createdAt))
    }
    return RunRow(
        id = id,
        kind = KIND_SUBAGENT,
        title = title,
        status = status,
        subtitle = error ?: prompt.take(120),
        reused = false,
        isRunning = !RunStatus.from(status).isFinished,
        parts = stats,
        createdAt = createdAt,
    )
}
