package me.rerere.rikkahub.data.workflow

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次 agent 调用的存档。
 *
 * [callIndex] 是"脚本开始这次调用时分配的序号", 顺序由脚本决定, 而不是完成顺序 ——
 * pipeline/parallel 扇出时完成顺序是乱的, 按完成顺序存档会在重跑时张冠李戴。
 */
data class WorkflowAgentRecord(
    val callIndex: Int,
    val prompt: String,
    val schemaJson: String?,
    val label: String?,
    /** 产出文本; 结构化输出时是归一化后的 JSON 文本 */
    val resultText: String,
)

/**
 * 可恢复性的存储。
 *
 * 键是「**脚本指纹** + 调用序号」而不是 runId: 这样"同一个脚本重跑一趟"才可能命中,
 * 而脚本一旦改过（指纹变了）就全部作废 —— 这正是我们要的语义。
 *
 * 只**存成功**的调用: 被停止 / 不可恢复错误（返回 null）与结构化输出失败的调用不存,
 * 于是重跑时自然会重做它们。
 */
interface WorkflowJournal {
    suspend fun recall(fingerprint: String, callIndex: Int): WorkflowAgentRecord?
    suspend fun remember(fingerprint: String, record: WorkflowAgentRecord)
}

/** 内存实现: 单测与"同进程内重跑"用; 跨进程要持久化就换成 Room 实现。 */
class InMemoryWorkflowJournal : WorkflowJournal {
    private val entries = ConcurrentHashMap<String, WorkflowAgentRecord>()

    override suspend fun recall(fingerprint: String, callIndex: Int): WorkflowAgentRecord? =
        entries[key(fingerprint, callIndex)]

    override suspend fun remember(fingerprint: String, record: WorkflowAgentRecord) {
        entries[key(fingerprint, record.callIndex)] = record
    }

    private fun key(fingerprint: String, callIndex: Int): String = fingerprint + "#" + callIndex
}

/**
 * 一次运行内的调用记账。
 *
 * [resumeBarrier] 实现的是上游的"前缀式重放"语义:
 * 重放时**从第一个对不上的调用开始, 它以及它之后的全部重跑**, 哪怕后面的调用本身存过档。
 * 原因很直白: 后面那些调用的提示词往往是前面结果的函数, 前面变了, 后面就不再是同一个调用。
 */
internal class WorkflowCallSession(val fingerprint: String) {
    private val counter = AtomicInteger(0)

    @Volatile
    var resumeBarrier: Int = Int.MAX_VALUE
        private set

    val reused = AtomicInteger(0)

    /** 已经分配出去的序号数, 也就是"这次运行尝试了多少个 agent 调用"。 */
    val total: Int get() = counter.get()

    fun nextIndex(): Int = counter.getAndIncrement()

    fun stopReuseFrom(index: Int) {
        if (index < resumeBarrier) resumeBarrier = index
    }
}
