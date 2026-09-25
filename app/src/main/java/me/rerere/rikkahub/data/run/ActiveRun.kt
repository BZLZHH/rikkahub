package me.rerere.rikkahub.data.run

import kotlinx.coroutines.Job

/**
 * 编排层持有的"正在跑"的条目。
 *
 * [handle] 由执行体在 [RunExecutor.launch] 里挂上; [cancel] 是执行体可选的内部协程取消钩子
 * （编排层在 kill / 超时时调用）。除此之外编排层不知道执行体内部长什么样。
 */
class ActiveRun(
    val record: RunRecord,
    /** 同一 runId 被重跑后, 旧执行体收尾时不能覆盖新记录 —— 靠这个代际号区分。 */
    val generation: Long,
) {
    @Volatile
    var handle: RunHandle? = null

    @Volatile
    var cancel: Job? = null

    /** 已被显式要求停止（kill / 超时）; 执行体据此分辨"被杀"与"自己失败"。 */
    @Volatile
    var stopRequested: Boolean = false

    /** 是否因超时被要求停止。 */
    @Volatile
    var timedOut: Boolean = false
}
