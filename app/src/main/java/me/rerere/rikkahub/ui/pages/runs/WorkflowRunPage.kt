package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.WorkflowAgentEntity
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.data.workflow.AgentRetryResult
import me.rerere.rikkahub.data.workflow.WorkflowRunner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject

/**
 * 单个 workflow 运行的状态页。
 *
 * 布局直接照搬上游 TUI 的骨架 —— 左栏是 phases, 右栏是当前 phase 下的 agents:
 * 这个分工之所以好用, 是因为它把"剧本走到哪了"和"这一步里谁在干活"分在了两个视觉层,
 * 扫一眼就能同时读到进度与细节。手机上竖屏也放得下（左栏 104dp）。
 *
 * 比 TUI 多出来的东西:
 * - 顶部的**分段进度条**: 完成/在跑/失败各占一段, 一眼看出整体形状;
 * - 每一行点进去就是那个 agent 的**完整子会话** —— TUI 只能看最近几次工具调用,
 *   这里能看到整段对话（这是本次要的重点）。
 */
@Composable
fun WorkflowRunPage(runId: String) {
    val dao: WorkflowRunDAO = koinInject()
    val nav = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val runner: WorkflowRunner = koinInject()
    val toaster = LocalToaster.current
    val run by dao.getByIdFlow(runId).collectAsStateWithLifecycle(initialValue = null)
    val agents by dao.listAgentsFlow(runId).collectAsStateWithLifecycle(initialValue = emptyList())

    // 运行中要能强停; 停止是挂起的, 所以扔到界面作用域里
    val scope = rememberCoroutineScope()
    val running = run?.let { !RunStatus.from(it.status).isFinished } == true
    // 耗时每秒走一次 —— 否则统计看着像冻住了
    val now = rememberTickingNow(running)

    val declared = remember(run?.phasesJson) { parseStringList(run?.phasesJson) }
    // 左栏的顺序: 脚本声明的在前, 实际出现过的补在后面（脚本可能用了没声明的 phase）
    val phaseNames = remember(declared, agents) {
        val observed = agents.mapNotNull { it.phase }.distinct()
        (declared + observed).distinct()
    }
    val ungrouped = agents.filter { it.phase == null }
    val showUngrouped = phaseNames.isEmpty() || ungrouped.isNotEmpty()

    var selected by remember { mutableStateOf<String?>(null) }
    val currentPhase = selected ?: phaseNames.firstOrNull()

    val visibleAgents = remember(agents, currentPhase, showUngrouped) {
        when {
            currentPhase != null -> agents.filter { it.phase == currentPhase }
            showUngrouped -> ungrouped
            else -> agents
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        // 运行名是"这是哪一次"的主要依据, 一行常常不够 —— 给两行
                        Text(
                            text = run?.name ?: "workflow",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        run?.let { Text(it.fingerprint.take(8), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (running) {
                        TextButton(onClick = { scope.launch { runner.kill(runId) } }) {
                            Text(stringResource(R.string.runs_stop))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            run?.let { r ->
                RunHeader(
                    name = r.name,
                    modelName = r.modelName,
                    status = r.status,
                    phase = r.phase,
                    agentsCalled = r.agentsCalled,
                    agentsReused = r.agentsReused,
                    runtimeMs = r.runtimeMs ?: r.startedAt?.let { now - it },
                    tokens = agents.sumOf { it.tokensIn + it.tokensOut },
                    agents = agents,
                    phaseCount = phaseNames.size,
                    error = r.error,
                    progressJson = r.progressJson,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            // 刻意**不**用 IntrinsicSize: 右栏是 LazyColumn, 对它做 intrinsic 测量会抛异常。
            // 左栏用 fillMaxHeight 撑满即可 —— Row 的高度由 fillMaxSize 定, 是确定的。
            Row(Modifier.fillMaxSize()) {
                PhaseRail(
                    phases = phaseNames,
                    ungroupedCount = if (showUngrouped && phaseNames.isNotEmpty()) ungrouped.size else null,
                    agents = agents,
                    selected = currentPhase,
                    onSelect = { selected = it },
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AgentList(
                    agents = visibleAgents,
                    now = now,
                    runModel = run?.modelName,
                    modifier = Modifier.weight(1f),
                    onRetry = { agent ->
                        val result = runner.retryAgent(runId, agent.callIndex)
                        if (result is AgentRetryResult.Rejected) toaster.show(result.reason)
                    },
                    onOpen = { agent ->
                        agent.sessionId?.let { sessionId ->
                            nav.navigate(
                                Screen.SubSession(
                                    sessionId = sessionId,
                                    title = agent.label ?: ("agent #" + agent.callIndex),
                                )
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RunHeader(
    name: String,
    modelName: String?,
    status: String,
    phase: String?,
    agentsCalled: Int,
    agentsReused: Int,
    runtimeMs: Long?,
    tokens: Int,
    agents: List<WorkflowAgentEntity>,
    /** 左栏那一份阶段数 —— 与左栏**同源**, 免得同一屏出现两个不一样的数字 */
    phaseCount: Int,
    error: String?,
    progressJson: String,
) {
    val accent = statusColor(status)
    Surface(color = CustomColors.topBarColors.containerColor) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 刻意拆成两行: 原来挤在一行里时, 模型 chip 会把剩余宽度吃光,
            // 排在最后又没有 weight 的**耗时会拿到 0 宽度, 被挤成一列一个字**
            // （真机上就是右上角那一竖列 "1 0 m 4 8 s"）。
            // 第一行只放"短且必须有"的东西, 第二行放"可以省略号"的东西。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(status, size = 12)
                Text(statusLabel(status), style = MaterialTheme.typography.labelLarge, color = accent)
                Box(Modifier.weight(1f))
                runtimeMs?.let {
                    Text(
                        text = formatDuration(it),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 阶段与模型: 都允许被省略号截断（weight + fill=false 让它们按内容取宽、不抢空间）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                phase?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 模型 chip: 同一脚本换模型跑, 结果可能完全不同 —— 它必须一眼可见
                modelName?.takeIf { it.isNotBlank() }?.let {
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 分段进度条: 完成 / 在跑 / 失败各占一段, 整体形状一眼可读
            ProgressBar(agents)

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeaderStat("agents", agentsCalled.toString())
                if (agentsReused > 0) HeaderStat("复用", agentsReused.toString())
                HeaderStat("tok", formatTokens(tokens))
                HeaderStat("phase", phaseCount.toString())
            }

            // 最近几条活动: 这是"此刻正在发生什么" —— 光有 phases 与 agent 列表,
            // 用户看不出脚本刚打了什么日志、刚进了哪个阶段。
            val activity = remember(progressJson) { recentActivity(progressJson, limit = 3) }
            if (activity.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    activity.forEach { entry ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (entry.kind == "phase") "▸" else "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.kind == "phase") {
                                    MaterialTheme.colorScheme.primary
                                } else if (entry.kind == "warn") {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            error?.takeIf { it.isNotBlank() }?.let {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一条把"完成 / 在跑 / 失败 / 还没开始"同时表达出来的条。空列表时退化成一条底色。 */
@Composable
private fun ProgressBar(agents: List<WorkflowAgentEntity>) {
    val done = agents.count { it.reused || RunStatus.from(it.status) == RunStatus.SUCCEEDED }
    val running = agents.count {
        val s = RunStatus.from(it.status)
        s == RunStatus.RUNNING || s == RunStatus.PENDING
    }
    val failed = agents.count {
        val s = RunStatus.from(it.status)
        s == RunStatus.FAILED || s == RunStatus.KILLED || s == RunStatus.TIMED_OUT ||
            s == RunStatus.INTERRUPTED
    }
    val total = agents.size.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (done > 0) Box(Modifier.weight(done.toFloat() / total).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
        if (running > 0) Box(Modifier.weight(running.toFloat() / total).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        if (failed > 0) Box(Modifier.weight(failed.toFloat() / total).fillMaxHeight().background(MaterialTheme.colorScheme.error))
        val rest = total - done - running - failed
        if (rest > 0) Box(Modifier.weight(rest.toFloat() / total).fillMaxHeight())
    }
}

@Composable
private fun PhaseRail(
    phases: List<String>,
    ungroupedCount: Int?,
    agents: List<WorkflowAgentEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    // 108dp 时中文阶段名几乎只剩两三个字, 前缀一样就分不清了 —— 加宽并允许折两行
    Column(Modifier.width(128.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        Text(
            text = "PHASES",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        if (phases.isEmpty() && ungroupedCount == null) {
            RailItem(index = 0, title = "全部", count = agents.size, active = true, onClick = { onSelect(null) })
            return@Column
        }
        phases.forEachIndexed { index, name ->
            RailItem(
                index = index + 1,
                title = name,
                count = agents.count { it.phase == name },
                active = name == selected,
                onClick = { onSelect(name) },
            )
        }
        if (ungroupedCount != null && phases.isNotEmpty()) {
            RailItem(
                index = phases.size + 1,
                title = "未分组",
                count = ungroupedCount,
                active = false,
                onClick = { onSelect(null) },
                enabled = false,
            )
        }
    }
}

@Composable
private fun RailItem(
    index: Int,
    title: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.14f) else Color_Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count == 1) "1 agent" else count.toString() + " agents",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AgentList(
    agents: List<WorkflowAgentEntity>,
    now: Long,
    runModel: String?,
    modifier: Modifier = Modifier,
    onRetry: (WorkflowAgentEntity) -> Unit,
    onOpen: (WorkflowAgentEntity) -> Unit,
) {
    if (agents.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "这一阶段还没有 agent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(agents, key = { it.callIndex }) { agent ->
            AgentRow(agent, now = now, runModel = runModel, onRetry = { onRetry(agent) }, onClick = { onOpen(agent) })
        }
    }
}

@Composable
private fun AgentRow(
    agent: WorkflowAgentEntity,
    now: Long,
    runModel: String?,
    onRetry: () -> Unit,
    onClick: () -> Unit,
) {
    val accent = statusColor(agent.status, agent.reused)
    val openable = agent.sessionId != null
    // 只对"跑完了但没成功"的行给重试: 成功的不需要, 还在跑的给了会与脚本打架
    val status = RunStatus.from(agent.status)
    val retryable = status.isFinished && status != RunStatus.SUCCEEDED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = openable, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatusDot(agent.status, reused = agent.reused, size = 9)
        Column(Modifier.weight(1f)) {
            Text(
                text = agent.label ?: ("agent #" + agent.callIndex),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = agentMetaLine(agent, runModel),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (agent.reused) {
            Text(
                text = "复用",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        // 运行中的行没有 runtimeMs, 但耗时恰恰是这时候最想看的 —— 用走时的"现在"现算
        val elapsed = agent.runtimeMs ?: if (status == RunStatus.RUNNING || status == RunStatus.PENDING) {
            (now - agent.startedAt).coerceAtLeast(0)
        } else {
            null
        }
        elapsed?.let {
            Text(
                text = formatDuration(it),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (retryable) {
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("重试", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * agent 行的第二行。
 *
 * **数字在前, 模型只在"与整趟不一样"时才出现** —— 这是真机上暴露的:
 * 模型是整趟统一的（`workflow_run` 的 `model` 参数）, 每行重复一遍 `deepseek-v4.1-flash`
 * 会把后面 token 与轮次全挤成省略号, 而那两个数才是用户看这一行时要读的东西。
 */
private fun agentMetaLine(agent: WorkflowAgentEntity, runModel: String?): String {
    val parts = buildList {
        val tokens = agent.tokensIn + agent.tokensOut
        if (tokens > 0) add(formatTokens(tokens) + " tok")
        if (agent.steps > 0) add(agent.steps.toString() + " 轮")
        if (agent.toolCalls > 0) add(agent.toolCalls.toString() + " 次工具")
        // 档位只在**单独指定过**时才显示: 继承主会话是常态, 每条都标一遍反而变成噪音
        agent.reasoning?.takeIf { it.isNotBlank() }?.let { add("推理 " + it) }
        // 模型只在与整趟不同时才占用这一行 —— 相同就是纯噪音
        agent.modelName
            ?.takeIf { it.isNotBlank() && it != runModel }
            ?.let { add(it) }
        agent.error?.takeIf { it.isNotBlank() }?.let { add(it.take(40)) }
    }
    return if (parts.isEmpty()) statusLabel(agent.status, agent.reused) else parts.joinToString("  ·  ")
}

private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent

private data class ActivityEntry(val kind: String, val text: String)

/** 进度时间线里最近的几条（最新的在最后）。解析失败就当没有, 不打扰界面。 */
private fun recentActivity(progressJson: String, limit: Int): List<ActivityEntry> {
    if (progressJson.isBlank()) return emptyList()
    val array = runCatching { JsonInstant.parseToJsonElement(progressJson) as? JsonArray }
        .getOrNull() ?: return emptyList()
    return array.takeLast(limit).mapNotNull { element ->
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        ActivityEntry((obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "log", text)
    }
}

/** 从 phasesJson 里读回有序的 phase 名。 */
private fun parseStringList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JsonInstant.parseToJsonElement(json) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
