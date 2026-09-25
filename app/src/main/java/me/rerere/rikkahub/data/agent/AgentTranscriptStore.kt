package me.rerere.rikkahub.data.agent

import java.io.File

/**
 * 子代理转录的落盘。
 *
 * 为什么不复用 [me.rerere.rikkahub.data.job.JobLogStore]: 后者的布局是
 * `<base>/<workspaceRoot>/<jobId>.<stdout|stderr|screen>.log`, 为**字节流**设计,
 * 且按 workspace 分目录。子代理的转录是**回合制消息**(JSON Lines),
 * 与 workspace root 无关, 混进去只会让两边都别扭。
 *
 * 一行一个 JSON 对象, 便于: 增量读取（按行游标）、UI 流式跟随、失败后人工查阅。
 */
class AgentTranscriptStore(
    private val baseDir: File,
) {
    fun fileFor(runId: String): File = File(baseDir, runId + ".jsonl")

    /**
     * 追加一行。
     *
     * 超过 [MAX_BYTES] 时从头截断为最近内容的一半 —— 转录是给人看的排查材料,
     * 不像 stdout 那样需要完整字节序列, 所以可以用"保尾部"这种更简单的策略。
     */
    fun append(runId: String, line: String) {
        runCatching {
            val file = fileFor(runId)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) truncateTail(file)
            file.appendText(line.trimEnd('\n') + "\n")
        }
    }

    /** 已有多少行（UI 的增量游标用行号, 比字节偏移更稳）。 */
    fun lineCount(runId: String): Int {
        val file = fileFor(runId)
        if (!file.isFile) return 0
        return runCatching { file.readLines().size }.getOrDefault(0)
    }

    /** 读取 [fromLine] 之后的至多 [limit] 行。 */
    fun read(runId: String, fromLine: Int, limit: Int = 500): List<String> {
        val file = fileFor(runId)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { seq -> seq.drop(fromLine.coerceAtLeast(0)).take(limit).toList() }
        }.getOrDefault(emptyList())
    }

    fun delete(runId: String) {
        runCatching { fileFor(runId).delete() }
    }

    private fun truncateTail(file: File) {
        runCatching {
            val lines = file.readLines()
            val keep = lines.takeLast((lines.size / 2).coerceAtLeast(1))
            file.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    companion object {
        /** 单条转录上限: 超过就保尾部。 */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
