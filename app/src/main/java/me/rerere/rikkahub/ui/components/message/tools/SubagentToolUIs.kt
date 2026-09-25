package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.UserMultiple
import me.rerere.rikkahub.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * subagent_* 工具的聊天内展示。
 *
 * 与 job_* 卡片同一目标: 让用户一眼看懂"AI 派了个什么活"而不是先看到 JSON。
 * 标题用 AI 写的 title（用户语言）; 摘要给任务描述与用量。
 */
abstract class SubagentToolUI(
    override val toolName: String,
    private val titleRes: Int,
) : ToolUIRenderer {

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.UserMultiple

    @Composable
    protected fun label(): String = stringResource(titleRes)

    protected fun argumentString(context: ToolUIContext, key: String): String? =
        context.arguments.getStringContent(key)

    protected fun contentString(context: ToolUIContext, key: String): String? =
        context.content?.getStringContent(key)

    protected fun contentInt(context: ToolUIContext, key: String): Int? =
        context.content?.jsonObject?.get(key)?.jsonPrimitive?.intOrNull

    protected fun argumentInt(context: ToolUIContext, key: String): Int? =
        context.arguments.jsonObject[key]?.jsonPrimitive?.intOrNull

    /** 卡片内的小字元信息（本文件私有: JobToolUIs 里那个是 private 的, 不能跨文件用）。 */
    @Composable
    protected fun MetaText(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val title = argumentString(context, "title")
        val runId = argumentString(context, "run_id")
        return when {
            !title.isNullOrBlank() -> title
            !runId.isNullOrBlank() -> label() + " · " + runId.take(8)
            else -> label()
        }
    }
}

object SubagentStartToolUI : SubagentToolUI("subagent_start", R.string.tool_ui_subagent_start) {

    override fun hasSummary(context: ToolUIContext): Boolean =
        argumentString(context, "prompt") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val prompt = argumentString(context, "prompt")
        val groups = argumentString(context, "tool_groups")
        val maxSteps = argumentInt(context, "max_steps")
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!prompt.isNullOrBlank()) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!groups.isNullOrBlank()) {
                    MetaText(stringResource(R.string.tool_ui_subagent_groups, groups))
                }
                if (maxSteps != null) {
                    MetaText(stringResource(R.string.tool_ui_subagent_max_steps, maxSteps))
                }
            }
        }
    }
}

object SubagentStatusToolUI : SubagentToolUI("subagent_status", R.string.tool_ui_subagent_status) {

    override fun hasSummary(context: ToolUIContext): Boolean =
        contentString(context, "status") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val status = contentString(context, "status")
        val steps = contentInt(context, "stepsDone")
        val calls = contentInt(context, "toolCalls")
        val tokensIn = contentInt(context, "tokensIn")
        val tokensOut = contentInt(context, "tokensOut")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!status.isNullOrBlank()) {
                MetaText(status)
            }
            if (steps != null) {
                MetaText(stringResource(R.string.tool_ui_subagent_steps, steps))
            }
            if (calls != null) {
                MetaText(stringResource(R.string.tool_ui_subagent_calls, calls))
            }
            if (tokensIn != null && tokensOut != null) {
                MetaText(stringResource(R.string.tool_ui_subagent_tokens, tokensIn, tokensOut))
            }
        }
    }
}

object SubagentResultToolUI : SubagentToolUI("subagent_result", R.string.tool_ui_subagent_result)

object SubagentKillToolUI : SubagentToolUI("subagent_kill", R.string.tool_ui_subagent_kill)
