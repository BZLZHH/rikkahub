package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.run.RunOrchestrator
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.data.workflow.WorkflowRunner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.coroutines.launch
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val RECENT_LIMIT = 40

/**
 * 运行中心: workflow 与子代理**放在同一屏**里看。
 *
 * 为什么不分成两个页面: 它们本来就是一件事的两种形态（workflow 是"脚本持有计划",
 * 子代理是"模型持有计划"）, 用户关心的是"现在有什么在跑、跑成什么样了",
 * 而不是"它属于哪一类"。分开只会让人两边找。
 *
 * 可读性上的几个刻意选择:
 * - 左侧一条**彩色状态条**: 扫一眼就知道哪些在跑, 不用读文字;
 * - 统计行用等宽字体 + `138.4k` 这种短写法, 数字之间用 `·` 分隔, 便于横向扫视;
 * - 标题优先显示脚本名 / 子代理的意图标题, 而不是 UUID。
 */
@Composable
fun RunsPage(conversationId: String? = null) {
    val workflowDao: WorkflowRunDAO = koinInject()
    val agentDao: AgentRunDAO = koinInject()
    val nav = LocalNavController.current
    // 停止: workflow 走 WorkflowRunner, 子代理走编排层 —— 两种执行体各自收自己的尾
    val workflowRunner: WorkflowRunner = koinInject()
    val orchestrator: RunOrchestrator = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val workflowRuns by workflowDao.listRecentFlow(RECENT_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val agentRuns by agentDao.listRecentFlow(RECENT_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 耗时每秒走一次: 只在组合时算一次的话, 运行中的那一行看着像冻住了。
    // 只在真的有东西在跑时才走, 免得空转。
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

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("运行") },
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
            contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running.isNotEmpty()) {
                item("running-header") { SectionHeader("正在进行", running.size) }
                items(running.size, key = { "run-" + running[it].key }) { index ->
                    val row = running[index]
                    RunRowCard(row, onClick = { open(nav, row) }, onStop = {
                        scope.launch {
                            val stopped = if (row.isWorkflow) {
                                workflowRunner.kill(row.id)
                            } else {
                                orchestrator.kill(row.id)
                            }
                            if (!stopped) toaster.show("它已经结束了")
                        }
                    })
                }
            }
            item("finished-header") { SectionHeader("最近", finished.size) }
            if (finished.isEmpty() && running.isEmpty()) {
                item("empty") { EmptyHint() }
            }
            items(finished.size, key = { "done-" + finished[it].key }) { index ->
                val row = finished[index]
                RunRowCard(row, onClick = { open(nav, row) }, onStop = null)
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
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun EmptyHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没有运行记录", style = MaterialTheme.typography.titleSmall)
            Text(
                "让助手跑一个 workflow, 或者派一个子代理, 这里就会出现它们的状态、阶段与子会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunRowCard(row: RunRow, onClick: () -> Unit, onStop: (() -> Unit)?) {
    val accent = statusColor(row.status, row.reused)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // 左侧状态条用 drawBehind 画, 而不是"一个 fillMaxHeight 的 Box + IntrinsicSize":
        // 后者要为整张卡做 intrinsic 测量, 而卡片里将来放任何 SubcomposeLayout 就会崩。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(color = accent, size = Size(4.dp.toPx(), size.height)) }
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KindBadge(row.kind)
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(row.status, reused = row.reused)
                Text(
                    text = statusLabel(row.status, row.reused),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            row.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = row.statsLine(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 只有还在跑的才给停止按钮: 结束后再点只会回来一句"它已经结束了"
                if (onStop != null) {
                    TextButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("停止", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: String) {
    val (label, tint) = if (kind == KIND_WORKFLOW) {
        "WF" to MaterialTheme.colorScheme.primary
    } else {
        "SA" to MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = tint,
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
        // 模型放最前: "这次用的是哪个模型"是排查与对比时第一个要看的东西
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
