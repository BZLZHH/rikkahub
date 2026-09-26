package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/** 一个正在跑的子代理"现在跑到哪了"。 */
internal data class AgentProgressSnapshot(
    val steps: Int,
    val toolCalls: Int,
    val tokensIn: Int,
    val tokensOut: Int,
)

/**
 * 从**累积的消息列表**里现算进度。
 *
 * 为什么需要这个函数（真机上踩过的坑）: `AgentRunner` 每收到一轮消息就更新了这些数字,
 * 但只通过 [AgentEvent.Progress] **在整段跑完之后**发一次。于是运行期间所有消费者看到的都是 0 ——
 * 界面上就是"一直 0 tok 0 toolcall", 直到结束才跳一下。
 *
 * 而 [AgentEvent.Messages] 是**每轮都发**的, 里面就是那份累积列表, 数字本来就在手上。
 * 所以"边跑边长"不需要改 AgentRunner, 只要在这里现算一次。
 *
 * 取 usage 用 `asReversed().firstNotNullOfOrNull`: 最后一条**带 usage 的**才是最新的那次调用
 * （工具结果那些消息没有 usage）。
 */
internal fun snapshotOf(messages: List<UIMessage>): AgentProgressSnapshot {
    val usage = messages.asReversed().firstNotNullOfOrNull { it.usage }
    return AgentProgressSnapshot(
        steps = messages.count { it.role == MessageRole.ASSISTANT },
        toolCalls = messages.sumOf { it.getTools().size },
        tokensIn = usage?.promptTokens ?: 0,
        tokensOut = usage?.completionTokens ?: 0,
    )
}
