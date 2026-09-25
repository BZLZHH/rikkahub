package me.rerere.rikkahub.data.run

/**
 * 执行体的种类。
 *
 * 编排层只按 kind 分账与统计, 不关心它内部是 PRoot 进程还是模型循环 ——
 * 这正是"job 与 subagent 平级"的落点。
 */
enum class RunKind {
    /** 沙箱里的 shell 命令（PRoot 进程） */
    JOB,

    /** 子代理（模型 + 受限工具集） */
    AGENT,
}
