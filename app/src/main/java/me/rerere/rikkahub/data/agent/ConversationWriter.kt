package me.rerere.rikkahub.data.agent

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 把子代理的完整对话增量写进 [AgentTranscriptStore]。
 *
 * ## 为什么不能只按"条数变多了就写新增的"
 *
 * 这是踩过的坑, 值得写清楚: `GenerationLoop` 在一次生成期间会**反复**发出
 * `GenerationChunk.Messages`, 每次都是**同一个列表**、但最后一条助手消息在不断变长
 * （流式）。于是"条数"根本不增长 —— 按条数做游标的话:
 *
 *   [任务]                    -> 写第 0 条
 *   [任务, 助手(刚开始, 几乎是空的)]  -> 条数变了, 写第 1 条**空壳**
 *   [任务, 助手(还在长)]        -> 条数没变, 什么都不写
 *   [任务, 助手(长完了, 真正的结论)] -> 条数还是没变, **真正的结论永远没落盘**
 *
 * 表现就是子会话页面里"只有一个开头的任务, 后面什么都没有" —— 一个**静默**的错误:
 * 文件写成功了, 只是写的是作废的那一版。
 *
 * ## 正确的做法
 *
 * 只写**已经定稿**的消息: 最后一条可能是正在生成的那条, 先不写;
 * 等这一轮流式结束（下一次 [sync] 时它已经不是最后一条了, 或者整段结束时的 [finish]）再写。
 * 因为每条消息只会"长完一次", 所以每条恰好写一次, 既不重复也不丢。
 */
internal class ConversationWriter(
    private val transcripts: AgentTranscriptStore,
    private val runId: String,
) {
    /** 已经落盘的条数。只增不减: 已经写过的消息内容不会再变（见上面的说明）。 */
    private var written = 0

    /**
     * 把"已经定稿"的部分写下去。
     *
     * @param messages 当前累积的对话
     * @param includeLast 连最后一条也写。**只有在整段运行结束时才为 true** ——
     *   运行中最后一条可能还在长, 写下去就是那个作废的版本。
     */
    fun sync(messages: List<UIMessage>, includeLast: Boolean = false) {
        val settled = if (includeLast) messages.size else messages.size - 1
        // 列表万一变短（理论上不该发生）也不能倒退, 否则会倒着重写
        val upto = settled.coerceAtMost(messages.size)
        while (written < upto) {
            transcripts.appendMessage(runId, JsonInstant.encodeToString(messages[written]))
            written++
        }
    }

    /** 整段运行结束: 把最后一条也补上。 */
    fun finish(messages: List<UIMessage>) = sync(messages, includeLast = true)

    /** 已经落盘多少条（测试与排查用）。 */
    fun writtenCount(): Int = written
}
