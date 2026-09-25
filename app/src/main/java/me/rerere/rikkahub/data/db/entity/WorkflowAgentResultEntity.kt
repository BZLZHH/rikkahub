package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity

/**
 * 可恢复性的存档: 「脚本指纹 + 调用序号」-> 那次 agent 调用的产出。
 *
 * 为什么键是这两样而不是 runId: 我们要的是"同一个脚本重跑一趟可以跳过已完成的部分",
 * 而 runId 每次都不同。指纹保证了"脚本改过就一律重跑"（宁可多跑, 不可错配）。
 *
 * 只存**成功**的调用: 返回 null（被停止/不可恢复错误）与结构化输出失败的都不存,
 * 于是重跑自然会重做它们。
 */
@Entity(
    tableName = "workflow_agent_results",
    primaryKeys = ["fingerprint", "callIndex"],
)
data class WorkflowAgentResultEntity(
    val fingerprint: String,
    /** 脚本**开始**这次调用时分配的序号（不是完成顺序 —— 扇出时完成顺序是乱的） */
    val callIndex: Int,
    /** 存一份提示词用于比对: 对不上就不许复用（前缀式重放） */
    val prompt: String,
    val schemaJson: String? = null,
    val label: String? = null,
    val resultText: String,
    val createdAt: Long = 0,
)
