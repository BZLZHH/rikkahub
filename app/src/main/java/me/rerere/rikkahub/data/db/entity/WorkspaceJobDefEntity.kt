package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务定义（模板）: 可复用的命令 + 参数 + 触发器。
 * 运行实例见 [WorkspaceJobEntity]，删除定义不会删除历史实例。
 */
@Entity(
    tableName = "workspace_job_defs",
    indices = [
        Index(value = ["workspace_id", "name"], unique = true),
        Index(value = ["enabled", "next_run_at"]),
    ],
)
data class WorkspaceJobDefEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("command")
    val command: String,
    /** 这个任务是干什么的（AI 或用户填写, 展示用） */
    @ColumnInfo("description")
    val description: String? = null,
    @ColumnInfo("cwd")
    val cwd: String = "",
    @ColumnInfo("mode")
    val mode: String = "PIPE",
    /** List<JobParamDto> */
    @ColumnInfo("params_json")
    val paramsJson: String = "[]",
    /** Map<String, String> */
    @ColumnInfo("env_json")
    val envJson: String = "{}",
    /** JobTrigger 的 serde JSON; null 表示只手动运行 */
    @ColumnInfo("trigger_json")
    val triggerJson: String? = null,
    @ColumnInfo("enabled")
    val enabled: Boolean = true,
    @ColumnInfo("notify")
    val notify: Boolean = true,
    @ColumnInfo("auto_wake")
    val autoWake: Boolean = false,
    @ColumnInfo("max_runtime_ms")
    val maxRuntimeMs: Long = 6L * 60 * 60 * 1000,
    /** 定时触发时若无法在后台确保常驻前台服务, 是否延后到下次前台再跑 */
    @ColumnInfo("require_keep_alive")
    val requireKeepAlive: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_run_at")
    val lastRunAt: Long? = null,
    @ColumnInfo("next_run_at")
    val nextRunAt: Long? = null,
    @ColumnInfo("run_count")
    val runCount: Int = 0,
    @ColumnInfo("last_status")
    val lastStatus: String? = null,
)
