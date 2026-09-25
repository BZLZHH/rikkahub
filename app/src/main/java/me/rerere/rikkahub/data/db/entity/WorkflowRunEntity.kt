package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次 workflow 运行的账目。
 *
 * 与 [AgentRunEntity]（子代理）分表而不是复用: 两者的"进度"形状根本不同 ——
 * 子代理的进度是"跑了几个回合、调了几次工具", 而 workflow 的进度是
 * "到了哪个 phase、每个 agent 复用了没有"。硬塞进一张表只会让两边都长出空字段。
 *
 * [fingerprint] 是脚本指纹: 可恢复性靠它 + 调用序号定位存档, 而不是靠 runId ——
 * "同一个脚本再跑一趟"才可能命中, 脚本改过就应当全部作废。
 */
@Entity(tableName = "workflow_runs")
data class WorkflowRunEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val conversationId: String? = null,
    val assistantId: String? = null,
    /** meta.name; 内联脚本若没写 meta 就是 "(inline workflow)" */
    val name: String,
    val fingerprint: String,
    /** 脚本落盘路径（按名保存/复用时有值）; 纯内联脚本为 null */
    val scriptPath: String? = null,
    val status: String,
    /** 当前阶段（最近一次 phase() 的标题） */
    val phase: String? = null,
    /** 阶段与日志的时间线（JSON 数组, 只保尾部） */
    val progressJson: String = "[]",
    /**
     * 脚本声明 / 走过的 phase 顺序（JSON 数组）。
     *
     * 光看进度时间线也能推出"见过哪些 phase", 但推不出顺序、更推不出"还没有 agent 的空阶段" ——
     * 而空阶段恰恰要在左栏显示出来, 否则用户会以为那一阶段不存在。
     *
     * defaultValue 是**必须写的**: v30 -> v31 是自动迁移, 而 Room 拒绝"加一个没有默认值的
     * NOT NULL 列"（既有行没法填）。写成 '[]' 而不是 [], 是因为这个字符串会原样进 SQL。
     */
    @ColumnInfo(defaultValue = "'[]'")
    val phasesJson: String = "[]",
    /** 脚本 return 的值 */
    val resultJson: String? = null,
    val error: String? = null,
    /** 这次运行尝试了多少个 agent 调用、其中多少个复用了存档 */
    val agentsCalled: Int = 0,
    val agentsReused: Int = 0,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val runtimeMs: Long? = null,
)
