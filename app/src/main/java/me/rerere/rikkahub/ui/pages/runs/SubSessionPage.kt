package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentTranscriptStore
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject

/**
 * 一个子代理的**完整子会话** —— 作为主会话的子页面打开。
 *
 * 这是本次要的重点: 子代理干了什么, 不该只有一个"结论"摘要可看。
 * 它自己的上下文窗口里发生过什么（收到什么任务、调了哪些工具、工具返回了什么、
 * 中间怎么改的主意）全部在这里按对话顺序展开 —— 排查"它为什么得出这个结论"时,
 * 缺的往往正是中间那几步。
 *
 * 数据来自 [AgentTranscriptStore] 的 messages 文件（完整消息逐条追加）,
 * 而不是那条"保尾部截断"的排查转录 —— 后者会把开头吃掉, 看完整会话时正好缺最关键的任务描述。
 */
@Composable
fun SubSessionPage(sessionId: String, title: String = "") {
    val transcripts: AgentTranscriptStore = koinInject()
    val agentRunDao: AgentRunDAO = koinInject()
    val workflowDao: WorkflowRunDAO = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var messages by remember(sessionId) { mutableStateOf<List<UIMessage>>(emptyList()) }
    var meta by remember(sessionId) { mutableStateOf<SessionMeta?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }

    // 边跑边看: 子代理还在写这个文件时, 页面要跟着长出来。跑完了就停, 不再空转。
    LaunchedEffect(sessionId) {
        var lastCount = -1
        while (true) {
            val decoded = withContext(Dispatchers.IO) {
                transcripts.readMessages(sessionId).mapNotNull { line ->
                    runCatching { JsonInstant.decodeFromString<UIMessage>(line) }.getOrNull()
                }
            }
            if (decoded.size != lastCount) {
                lastCount = decoded.size
                messages = decoded
            }
            if (meta == null) {
                meta = withContext(Dispatchers.IO) { loadMeta(sessionId, agentRunDao, workflowDao) }
            }
            loaded = true
            val status = meta?.status
            if (status != null && RunStatus.from(status).isFinished) break
            delay(1200)
        }
    }

    val listState = rememberLazyListState()
    // 只在用户本来就贴着底部时才自动跟随 —— 否则会把人正在读的那段顶走
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (visible >= messages.size - 3) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(
                            text = title.ifBlank { meta?.title ?: "子会话" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "子会话 · " + messages.size + " 条消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            meta?.let { SessionHeader(it) }
            if (loaded && messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "这个子会话还没有留下消息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(messages, key = { index, _ -> index }) { _, message ->
                        MessageBlock(message)
                    }
                }
            }
        }
    }
}

/** 子会话的元信息: 优先按子代理查, 查不到再按 workflow 里的 agent 行查。 */
private data class SessionMeta(
    val title: String,
    val status: String,
    val modelName: String?,
    val steps: Int,
    val toolCalls: Int,
    val tokens: Int,
    val runtimeMs: Long?,
    val error: String?,
    val prompt: String?,
)

private suspend fun loadMeta(
    sessionId: String,
    agentRunDao: AgentRunDAO,
    workflowDao: WorkflowRunDAO,
): SessionMeta? {
    runCatching { agentRunDao.getById(sessionId) }.getOrNull()?.let { run ->
        return SessionMeta(
            title = run.title,
            status = run.status,
            modelName = null,
            steps = run.stepsDone,
            toolCalls = run.toolCalls,
            tokens = run.tokensIn + run.tokensOut,
            runtimeMs = run.runtimeMs,
            error = run.error,
            prompt = run.prompt,
        )
    }
    runCatching { workflowDao.getAgentBySession(sessionId) }.getOrNull()?.let { agent ->
        return SessionMeta(
            title = agent.label ?: ("agent #" + agent.callIndex),
            status = agent.status,
            modelName = agent.modelName,
            steps = agent.steps,
            toolCalls = agent.toolCalls,
            tokens = agent.tokensIn + agent.tokensOut,
            runtimeMs = agent.runtimeMs,
            error = agent.error,
            prompt = agent.prompt,
        )
    }
    return null
}

@Composable
private fun SessionHeader(meta: SessionMeta) {
    val accent = statusColor(meta.status)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(meta.status, size = 11)
                Text(statusLabel(meta.status), style = MaterialTheme.typography.labelLarge, color = accent)
                meta.modelName?.takeIf { it.isNotBlank() }?.let {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Box(Modifier.weight(1f))
                meta.runtimeMs?.let {
                    Text(
                        text = formatDuration(it),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                text = buildList {
                    add(meta.steps.toString() + " 轮")
                    add(meta.toolCalls.toString() + " 次工具")
                    if (meta.tokens > 0) add(formatTokens(meta.tokens) + " tok")
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            meta.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageBlock(message: UIMessage) {
    val (label, tint) = when (message.role) {
        MessageRole.USER -> "任务" to MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> "助手" to MaterialTheme.colorScheme.tertiary
        MessageRole.TOOL -> "工具结果" to MaterialTheme.colorScheme.secondary
        MessageRole.SYSTEM -> "系统" to MaterialTheme.colorScheme.outline
    }
    // 左侧角色色条用 drawBehind: 一屏里能立刻分出"谁在说话"。
    // 不用"fillMaxHeight 的 Box + IntrinsicSize" —— 消息里可能包含任何渲染器,
    // 一旦它对 intrinsic 测量不友好（SubcomposeLayout / AndroidView）, 整页就会崩。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(color = tint.copy(alpha = 0.55f), size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
        message.parts.forEach { part -> PartBlock(part) }
    }
}

@Composable
private fun PartBlock(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Text -> {
            if (part.text.isBlank()) return
            MarkdownBlock(content = part.text, style = MaterialTheme.typography.bodyMedium)
        }

        is UIMessagePart.Reasoning -> {
            if (part.reasoning.isBlank()) return
            CollapsibleBlock(
                header = "思考过程",
                body = part.reasoning,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                initiallyExpanded = false,
            )
        }

        is UIMessagePart.Tool -> ToolBlock(part)

        is UIMessagePart.Image -> CollapsibleBlock(
            header = "图片",
            body = part.url,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 其余类型（视频/音频/文档/服务端工具等）在子会话里不常见,
        // 显式列出来而不是用 else: 上游新增一种 part 时, 编译期就会提醒这里漏了。
        is UIMessagePart.Video -> FallbackPart("视频")
        is UIMessagePart.Audio -> FallbackPart("音频")
        is UIMessagePart.Document -> FallbackPart("文档 " + part.fileName)
        is UIMessagePart.ServerTool -> FallbackPart("服务端工具 " + part.toolName)
        is UIMessagePart.ToolCall -> FallbackPart("工具调用 " + part.toolName)
        is UIMessagePart.ToolResult -> FallbackPart("工具结果 " + part.toolName)
        UIMessagePart.Search -> FallbackPart("搜索")
    }
}

@Composable
private fun FallbackPart(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 工具调用的展开块: 名字与参数是**排查时最常看的**, 所以默认就能看到一行摘要。 */
@Composable
private fun ToolBlock(tool: UIMessagePart.Tool) {
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }
    val done = tool.isExecuted
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (done) "●" else "○",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = tool.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val preview = if (expanded) tool.input else tool.input.take(140)
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && tool.output.isNotEmpty()) {
                Text(
                    text = "结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tool.output.forEach { out ->
                    if (out is UIMessagePart.Text) {
                        MarkdownBlock(content = out.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleBlock(
    header: String,
    body: String,
    tint: androidx.compose.ui.graphics.Color,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (expanded) header + "（收起）" else header + "（展开）",
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.8f),
            modifier = Modifier.clickable { expanded = !expanded },
        )
        Text(
            text = if (expanded) body else body.take(160),
            style = MaterialTheme.typography.bodySmall,
            color = tint.copy(alpha = 0.9f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
