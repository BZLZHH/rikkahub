package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 后台任务的一次运行实例（job_* 工具与任务页签展示的"任务"）。
 *
 * 进程本身不持久: App 被杀后 [status] 会由 WorkspaceJobManager 在启动时改写为 interrupted。
 */
@Entity(
    tableName = "workspace_jobs",
    indices = [
        Index(value = ["workspace_id", "created_at"]),
        Index(value = ["status"]),
    ],
)
data class WorkspaceJobEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** 来自哪个任务定义（模板）; 直接 job_start 的临时任务为 null */
    @ColumnInfo("def_id")
    val defId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    /** 发起该任务的会话; 完成唤醒 AI 时用 */
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("command")
    val command: String,
    @ColumnInfo("cwd")
    val cwd: String = "",
    @ColumnInfo("args_json")
    val argsJson: String = "{}",
    @ColumnInfo("mode")
    val mode: String = "PIPE",
    @ColumnInfo("status")
    val status: String = "PENDING",
    @ColumnInfo("exit_code")
    val exitCode: Int? = null,
    @ColumnInfo("pid")
    val pid: Long? = null,
    @ColumnInfo("trigger_source")
    val triggerSource: String = "AI",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long? = null,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("runtime_ms")
    val runtimeMs: Long? = null,
    @ColumnInfo("log_bytes_out")
    val logBytesOut: Long = 0L,
    @ColumnInfo("log_bytes_err")
    val logBytesErr: Long = 0L,
    @ColumnInfo("log_truncated")
    val logTruncated: Boolean = false,
    @ColumnInfo("error")
    val error: String? = null,
    @ColumnInfo("deferred_reason")
    val deferredReason: String? = null,
    @ColumnInfo("wake_state")
    val wakeState: String = "NONE",
    @ColumnInfo("notify")
    val notify: Boolean = true,
    @ColumnInfo("auto_wake")
    val autoWake: Boolean = false,
    @ColumnInfo("max_runtime_ms")
    val maxRuntimeMs: Long = 6L * 60 * 60 * 1000,
)
