package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 从累积消息里现算进度。
 *
 * 这函数存在的理由就是真机上的"一直 0 tok 0 toolcall": AgentRunner 每轮都更新了这些数字,
 * 但只通过 AgentEvent.Progress 在**整段结束**时发一次, 而每轮都发的 AgentEvent.Messages
 * 被消费者丢掉了。于是运行期间所有数字恒为 0。
 */
class AgentProgressTest {

    private fun assistant(
        text: String = "…",
        usage: TokenUsage? = null,
        toolCount: Int = 0,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = buildList {
            add(UIMessagePart.Text(text))
            repeat(toolCount) { index ->
                add(
                    UIMessagePart.Tool(
                        toolCallId = "call-" + index,
                        toolName = "tool-" + index,
                        input = "{}",
                    )
                )
            }
        },
        usage = usage,
    )

    @Test
    fun emptyConversationIsAllZero() {
        assertEquals(AgentProgressSnapshot(0, 0, 0, 0), snapshotOf(emptyList()))
    }

    @Test
    fun countsAssistantRoundsAndToolCalls() {
        val messages = listOf(
            UIMessage.user("任务"),
            assistant("先看文件", toolCount = 2),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("文件内容"))),
            assistant("再看一个", toolCount = 1),
        )
        val snapshot = snapshotOf(messages)
        assertEquals("只数助手轮次, 用户与工具结果不算", 2, snapshot.steps)
        assertEquals("工具调用要跨消息累加", 3, snapshot.toolCalls)
    }

    @Test
    fun takesUsageFromTheLatestMessageThatHasIt() {
        // 工具结果那些消息没有 usage; 最新的那条带 usage 的才是"这次调用"的用量
        val messages = listOf(
            UIMessage.user("任务"),
            assistant("第一轮", usage = TokenUsage(promptTokens = 100, completionTokens = 10)),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("结果"))),
            assistant("第二轮", usage = TokenUsage(promptTokens = 250, completionTokens = 40)),
        )
        val snapshot = snapshotOf(messages)
        assertEquals(250, snapshot.tokensIn)
        assertEquals(40, snapshot.tokensOut)
    }

    @Test
    fun missingUsageIsZeroNotACrash() {
        // 有些 provider 不回 usage —— 那就显示 0, 而不是抛异常把整条链打断
        val snapshot = snapshotOf(listOf(UIMessage.user("任务"), assistant("没有 usage")))
        assertEquals(0, snapshot.tokensIn)
        assertEquals(0, snapshot.tokensOut)
        assertEquals(1, snapshot.steps)
    }

    @Test
    fun growsMonotonicallyAsTheRunProceeds() {
        // 这就是"边跑边长"要保证的: 同一份列表多一轮, 数字只会更大
        val first = listOf(UIMessage.user("任务"), assistant("第一轮", toolCount = 1))
        val second = first + listOf(
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("结果"))),
            assistant("第二轮", toolCount = 3),
        )
        val a = snapshotOf(first)
        val b = snapshotOf(second)
        assertEquals(1, a.steps)
        assertEquals(2, b.steps)
        assertEquals(1, a.toolCalls)
        assertEquals(4, b.toolCalls)
    }
}
