package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 子代理完整对话的增量落盘。
 *
 * 这里锁的是一个**真机上真实发生过**的 bug: 子会话页面只能看到开头那句任务,
 * 后面什么都没有（哪怕任务已经跑完）。
 *
 * 根因是"按条数判断有没有新消息"—— 而流式期间最后一条助手消息会**原地变长**,
 * 条数根本不变。于是第一条快照（几乎是空的）被写下去之后, 真正的结论再也没落盘过。
 * 文件写成功了, 写的是作废的那一版 —— 所以这是个静默错误。
 */
class ConversationWriterTest {

    private fun newStore(): AgentTranscriptStore {
        val dir = Files.createTempDirectory("conversation-writer-test").toFile()
        return AgentTranscriptStore(dir)
    }

    private fun reply(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun store(store: AgentTranscriptStore, runId: String): List<UIMessage> =
        store.readMessages(runId).map { JsonInstant.decodeFromString<UIMessage>(it) }

    @Test
    fun streamingGrowthOfTheLastMessageIsNotLost() {
        val store = newStore()
        val writer = ConversationWriter(store, "r1")
        val task = UIMessage.user("原始任务")

        // 真实顺序: 先只有任务; 然后助手消息出现并在若干次回调里原地变长 ——
        // **全程列表长度都是 2**, 变的只是最后一条的内容。
        writer.sync(listOf(task))
        writer.sync(listOf(task, reply("")))
        writer.sync(listOf(task, reply("我在")))
        writer.sync(listOf(task, reply("我在读文件")))
        writer.finish(listOf(task, reply("我在读文件, 结论是 X")))

        val decoded = store(store, "r1")
        assertEquals("任务 + 结论, 不多不少", 2, decoded.size)
        assertEquals("原始任务", decoded[0].toText())
        assertEquals(
            "落盘必须是**最终版本**, 而不是流式途中的空壳 —— 这正是真机上踩的那个坑",
            "我在读文件, 结论是 X",
            decoded[1].toText(),
        )
    }

    @Test
    fun inFlightMessageIsVisibleWhileStreaming() {
        // "边跑边看"靠的就是这个: 定稿文件里只有已经写完的消息,
        // 而正在生成的那条单独放在 live 里, 每次整体覆盖。
        val store = newStore()
        val writer = ConversationWriter(store, "r6", liveWriteIntervalMs = 0)
        val task = UIMessage.user("任务")

        writer.sync(listOf(task, reply("我在")))
        writer.sync(listOf(task, reply("我在读文件")))

        val live = store.readLive("r6")
        assertEquals("流式内容要能立刻看到", "我在读文件", JsonInstant.decodeFromString<UIMessage>(live!!).toText())
        assertEquals("定稿文件里不能有它, 否则会写出作废的版本", 1, store.readMessages("r6").size)

        writer.finish(listOf(task, reply("我在读文件, 结论是 X")))
        assertNull("结束后 live 要清掉, 否则界面会一直挂着一份过期的内容", store.readLive("r6"))
        assertEquals(2, store.readMessages("r6").size)
        assertEquals("我在读文件, 结论是 X", store(store, "r6").last().toText())
    }

    @Test
    fun liveTailIsThrottled() {
        // 流式每秒可能来几十个 chunk, 每个都写盘没必要 —— 节流要真的生效
        val store = newStore()
        val writer = ConversationWriter(store, "r7", liveWriteIntervalMs = 60_000)
        val task = UIMessage.user("任务")
        writer.sync(listOf(task, reply("第一次")))
        val first = store.readLive("r7")
        writer.sync(listOf(task, reply("第二次")))
        assertEquals("同一时间窗内不该再写", first, store.readLive("r7"))
    }

    @Test
    fun everyRoundOfAToolLoopIsKept() {
        val store = newStore()
        val writer = ConversationWriter(store, "r2")
        val task = UIMessage.user("任务")
        val a1 = reply("先读文件")
        val tool = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("文件内容")))
        val a2 = reply("看完了, 结论 Y")

        writer.sync(listOf(task, a1))
        writer.sync(listOf(task, a1, tool))
        writer.sync(listOf(task, a1, tool, a2))
        writer.finish(listOf(task, a1, tool, a2))

        val decoded = store(store, "r2")
        assertEquals("四个回合一条都不能少", 4, decoded.size)
        assertEquals("看完了, 结论 Y", decoded.last().toText())
    }

    @Test
    fun nothingIsWrittenTwice() {
        // 同一个列表反复 sync（流式的常态）不该产生重复行
        val store = newStore()
        val writer = ConversationWriter(store, "r3")
        val messages = listOf(UIMessage.user("任务"), reply("结论"))
        repeat(5) { writer.sync(messages, includeLast = true) }

        assertEquals("重复 sync 不该写出重复消息", 2, store.readMessages("r3").size)
        assertEquals(2, writer.writtenCount())
    }

    @Test
    fun inFlightMessageIsNotWrittenUntilItSettles() {
        val store = newStore()
        val writer = ConversationWriter(store, "r4")
        val task = UIMessage.user("任务")

        writer.sync(listOf(task))
        assertEquals("只有一条时, 它就是\"还在长的\"那条, 先不写", 0, store.readMessages("r4").size)

        writer.sync(listOf(task, reply("半成品")))
        assertEquals("任务已经定稿了, 应当落盘", 1, store.readMessages("r4").size)
        assertEquals("任务", store(store, "r4").single().toText())
    }

    @Test
    fun shrinkingListDoesNotRewindTheCursor() {
        // 理论上不该发生; 万一发生也绝不能倒着重写（那会把文件写乱）
        val store = newStore()
        val writer = ConversationWriter(store, "r5")
        val task = UIMessage.user("任务")
        writer.sync(listOf(task, reply("一"), reply("二")), includeLast = true)
        assertEquals(3, writer.writtenCount())

        writer.sync(listOf(task))
        assertTrue("条数不能倒退", writer.writtenCount() >= 3)
        assertEquals(3, store.readMessages("r5").size)
    }
}
