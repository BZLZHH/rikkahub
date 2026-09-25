package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作流定义（多步骤流程的模板）。
 *
 * 与 [WorkspaceJobDefEntity] 的关系: 后者是**单条命令**的模板, 本表是**一串有依赖、要传递数据**
 * 的步骤。两者刻意分开而不是把 job def 扩展成"多步" —— 单命令任务是非常常见的场景,
 * 让它背上步骤树、依赖与变量传递的复杂度不划算。运行实例见 [WorkflowRunEntity]。
 */
@Entity(
    tableName = "workspace_workflows",
    indices = [
        Index(value = ["workspace_id", "name"], unique = true),
    ],
)
data class WorkflowEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** 稳定标识, AI 按名引用 */
    @ColumnInfo("name")
    val name: String,
    /** 这条流程是干什么的（展示用, 同 job 的 reason 约定: 用户语言一句话） */
    @ColumnInfo("description")
    val description: String? = null,
    /** List<WorkflowParam> */
    @ColumnInfo("params_json")
    val paramsJson: String = "[]",
    /** List<WorkflowStep> */
    @ColumnInfo("steps_json")
    val stepsJson: String = "[]",
    /** Map<String, String> */
    @ColumnInfo("env_json")
    val envJson: String = "{}",
    @ColumnInfo("notify")
    val notify: Boolean = true,
    @ColumnInfo("auto_wake")
    val autoWake: Boolean = false,
    /** 整个运行的上限; 单步可用 timeout_seconds 收紧 */
    @ColumnInfo("max_runtime_ms")
    val maxRuntimeMs: Long = 6L * 60 * 60 * 1000,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_run_at")
    val lastRunAt: Long? = null,
    @ColumnInfo("run_count")
    val runCount: Int = 0,
    @ColumnInfo("last_status")
    val lastStatus: String? = null,
)
