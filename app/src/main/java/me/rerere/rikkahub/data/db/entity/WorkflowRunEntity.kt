package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作流的一次运行。
 *
 * [resolvedStepsJson] 是**开跑时对步骤的快照**: 定义在中途被改了也不影响正在跑的这次,
 * 否则"改一下定义"会把在跑的流程变成另一种东西, 排查时完全对不上。
 */
@Entity(
    tableName = "workspace_workflow_runs",
    indices = [
        Index(value = ["workflow_id", "created_at"]),
        Index(value = ["status"]),
        Index(value = ["workspace_id", "created_at"]),
    ],
)
data class WorkflowRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workflow_id")
    val workflowId: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    /** 展示标题（沿用定义的名字 + 描述） */
    @ColumnInfo("title")
    val title: String,
    /** 本次实参（留痕, 便于原样重跑） */
    @ColumnInfo("args_json")
    val argsJson: String = "{}",
    /** 开跑时的步骤快照（含参数渲染后的命令） */
    @ColumnInfo("resolved_steps_json")
    val resolvedStepsJson: String = "[]",
    /** List<WorkflowStepState> */
    @ColumnInfo("step_states_json")
    val stepStatesJson: String = "[]",
    @ColumnInfo("status")
    val status: String = "PENDING",
    @ColumnInfo("trigger_source")
    val triggerSource: String = "AI",
    @ColumnInfo("current_step")
    val currentStep: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long? = null,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("runtime_ms")
    val runtimeMs: Long? = null,
    @ColumnInfo("error")
    val error: String? = null,
    @ColumnInfo("notify")
    val notify: Boolean = true,
    @ColumnInfo("auto_wake")
    val autoWake: Boolean = false,
)
