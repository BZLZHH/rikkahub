package me.rerere.rikkahub.data.run

/**
 * 编排层眼里的一次执行。
 *
 * 编排层只通过它做事, 因此**不允许**在这里出现进程 / pid / PRoot 等概念:
 * 那是执行体自己的事。shell 任务把它实现为"杀进程树", 子代理把它实现为"停模型循环"。
 */
interface RunHandle {
    /** 优雅停止; 宽限期内没退出则由实现决定是否强杀。 */
    suspend fun terminate(graceMillis: Long)

    /** 等到结束; 返回是否在超时前结束。 */
    suspend fun await(timeoutMillis: Long): Boolean

    /** 是否仍在执行。 */
    val isAlive: Boolean
}
