package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * 子会话"完整对话"的落盘与读回。
 *
 * 这条路径支撑"能看到子代理的完整子会话"这个能力, 而它有个**很坏的失效模式**:
 * 只要中间某一条消息编码/解码失败, 界面就会**静默地少显示一段** ——
 * 用户看到的是"只有开头那一句", 而不是一个报错。所以这里逐类型地锁住往返。
 */
class AgentTranscriptStoreTest {

    private fun newStore(): AgentTranscriptStore {
        val dir = Files.createTempDirectory("agent-runs-test").toFile()
        return AgentTranscriptStore(dir)
    }

    @Test
    fun plainPromptRoundTrips() {
        val store = newStore()
        store.appendMessage("r1", JsonInstant.encodeToString(UIMessage.user("干一件事")))
        val lines = store.readMessages("r1")
        assertEquals(1, lines.size)
        assertEquals("干一件事", JsonInstant.decodeFromString<UIMessage>(lines[0]).toText())
    }

    @Test
    fun assistantWithModelAndUsageRoundTrips() {
        // 带 modelId / usage 的助手消息是最可能出问题的一类（用户那条提示词什么都没有, 所以它能显示）
        val store = newStore()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("我做了")),
            modelId = Uuid.random(),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )
        store.appendMessage("r2", JsonInstant.encodeToString(message))
        val decoded = JsonInstant.decodeFromString<UIMessage>(store.readMessages("r2").single())
        assertEquals(MessageRole.ASSISTANT, decoded.role)
    }

    @Test
    fun reasoningAndToolPartsRoundTrip() {
        // 子代理最典型的一条助手消息: 思考 + 正文 + 工具调用（含嵌套的输出 part）
        val store = newStore()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "先看一眼文件"),
                UIMessagePart.Text("我去读文件"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_read_file",
                    input = "{\"path\":\"/workspace/a.txt\"}",
                    output = listOf(UIMessagePart.Text("文件内容")),
                ),
            ),
            modelId = Uuid.random(),
            usage = TokenUsage(promptTokens = 100, completionTokens = 20),
        )
        store.appendMessage("r3", JsonInstant.encodeToString(message))
        val decoded = JsonInstant.decodeFromString<UIMessage>(store.readMessages("r3").single())
        assertEquals(3, decoded.parts.size)
    }

    @Test
    fun wholeConversationKeepsOrderAndCount() {
        val store = newStore()
        val conversation = listOf(
            UIMessage.user("原始任务"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("第一步")),
                modelId = Uuid.random(),
                usage = TokenUsage(promptTokens = 1, completionTokens = 1),
            ),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("工具结果"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("结论")),
                modelId = Uuid.random(),
                usage = TokenUsage(promptTokens = 2, completionTokens = 2),
            ),
        )
        conversation.forEach { store.appendMessage("r4", JsonInstant.encodeToString(it)) }

        val lines = store.readMessages("r4")
        assertEquals("每一条都要落盘（不能只留下第一条）", 4, lines.size)
        val decoded = lines.map { JsonInstant.decodeFromString<UIMessage>(it) }
        assertEquals(4, decoded.size)
        assertEquals("原始任务", decoded.first().toText())
        assertEquals("结论", decoded.last().toText())
    }

    @Test
    fun messageJsonIsSingleLine() {
        // 一行一条是这个格式的前提: JSON 里若混进真换行, 后面的行会被读成"坏行"而丢掉
        val json = JsonInstant.encodeToString(UIMessage.user("带\n换行的\n提示词"))
        assertEquals("编码后不能含真换行", false, json.contains('\n'))
    }
}
