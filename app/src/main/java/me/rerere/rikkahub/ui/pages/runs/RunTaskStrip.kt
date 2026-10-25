package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

/**
 * 输入框上方的一条"现在有什么在跑"。
 *
 * 与 Claude Code 的任务条同一个用意: 派出去的子代理 / workflow 是**后台**跑的,
 * 而用户的眼睛在输入框附近 —— 不在这儿给他一个反馈, 他就会以为"点了没反应"。
 *
 * 刻意做得**极窄且只在真的有东西在跑时出现**: 没有运行时它完全不占地方,
 * 所以不必做成可折叠的。
 */
@Composable
fun RunTaskStrip(conversationId: String?, modifier: Modifier = Modifier) {
    val workflowDao: WorkflowRunDAO = koinInject()
    val agentDao: AgentRunDAO = koinInject()
    val nav = LocalNavController.current

    val workflows by workflowDao.listRecentFlow(20).collectAsStateWithLifecycle(initialValue = emptyList())
    val agents by agentDao.listRecentFlow(50).collectAsStateWithLifecycle(initialValue = emptyList())

    val running = remember(workflows, agents, conversationId) {
        val workflowItems = workflows
            .filter { !RunStatus.from(it.status).isFinished }
            .filter { conversationId == null || it.conversationId == conversationId }
            .map { RunningTask(it.id, true, it.name, it.phase, it.startedAt, it.agentsCalled) }
        val agentItems = agents
            .filter { !RunStatus.from(it.status).isFinished }
            .filter { conversationId == null || it.conversationId == conversationId }
            .map { RunningTask(it.id, false, it.title, null, it.startedAt, null) }
        (workflowItems + agentItems).sortedByDescending { it.startedAt ?: 0L }
    }
    if (running.isEmpty()) return

    val now = rememberTickingNow(active = true)
    val latest = running.first()
    val elapsed = latest.startedAt?.let { (now - it).coerceAtLeast(0) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { nav.navigate(Screen.Runs(conversationId)) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(RunStatus.RUNNING.name, size = 9)
            Text(
                text = stringResource(R.string.runs_running_count, running.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(1.dp),
                    )
                    .padding(horizontal = 0.dp, vertical = 6.dp),
            )
            Text(
                text = buildString {
                    append(latest.title)
                    latest.phase?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
                    latest.agents?.takeIf { it > 0 }?.let { append("  ·  ").append(it).append(" agents") }
                    elapsed?.let { append("  ·  ").append(formatDuration(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class RunningTask(
    val id: String,
    val isWorkflow: Boolean,
    val title: String,
    val phase: String?,
    val startedAt: Long?,
    val agents: Int?,
)
