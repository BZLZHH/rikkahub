package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity

/**
 * workflow 一次运行里的**单个 agent**。
 *
 * 这是"高可读性 UI"的数据底座: 上游 TUI 的右栏每一行（标签、模型、token、耗时、状态）
 * 都对应这里的一行。没有它, 界面只能显示"跑了 5 个 agent"这种聚合数字,
 * 用户看不出**哪一个**在跑、跑到哪了、吃了多少 token。
 *
 * [sessionId] 是子代理那次运行的转录 id —— UI 拿它去读**完整子会话**。
 * 复用命中的行没有 sessionId（那一趟根本没跑 agent）。
 *
 * 主键 (runId, callIndex): callIndex 是"脚本开始这次调用时分配的序号",
 * 与可恢复存档用的是同一个序号, 于是 UI 上的一行、存档里的一条、重跑时复用的是同一件事。
 */
@Entity(
    tableName = "workflow_agents",
    primaryKeys = ["runId", "callIndex"],
)
data class WorkflowAgentEntity(
    val runId: String,
    val callIndex: Int,
    /** 脚本给的 label（没有就用 null, UI 退回显示 "agent #N"） */
    val label: String? = null,
    val prompt: String,
    /**
     * 这一条当时的结构化输出要求。
     *
     * 必须存下来: "重试失败的 agent"要**照着原样**再跑一次 —— 少了 schema,
     * 重试就变成"以散文要 JSON"的另一次调用, 结果与原来那次根本不可比。
     */
    val schemaJson: String? = null,
    /** 起这次调用时脚本所处的 phase（左栏按它分组） */
    val phase: String? = null,
    val status: String,
    /** 这一行是复用上一趟存档的结果, 而不是真的跑了 agent */
    val reused: Boolean = false,
    val modelId: String? = null,
    val modelName: String? = null,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** 子会话转录 id（UI 用它读完整对话） */
    /** 这一条实际用的推理档位; null = 继承主会话设置（可空, 老行补 NULL） */
    val reasoning: String? = null,
    val sessionId: String? = null,
    val resultText: String? = null,
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val runtimeMs: Long? = null,
)
