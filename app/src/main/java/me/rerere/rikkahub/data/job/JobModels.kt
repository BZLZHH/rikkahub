package me.rerere.rikkahub.data.job

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.JobParamDef

enum class WorkspaceJobMode {
    /** 非交互: stdin 立即关闭, stdout/stderr 原始字节日志 */
    PIPE,

    /** 交互式: 真实 pty, 日志来自终端屏幕/transcript, 支持 job_send */
    PTY;

    companion object {
        fun from(value: String?): WorkspaceJobMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PIPE
    }
}

enum class WorkspaceJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    KILLED,
    TIMED_OUT,
    INTERRUPTED,
    DEFERRED;

    val isFinished: Boolean
        get() = this != PENDING && this != RUNNING && this != DEFERRED

    companion object {
        fun from(value: String?): WorkspaceJobStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}

enum class JobTriggerSource { AI, USER, SCHEDULE, RESTART }

enum class JobWakeState { NONE, QUEUED, WOKEN, SKIPPED, MERGED }

enum class JobLogStream { STDOUT, STDERR, SCREEN }

/** 触发器: 一次性 / 固定间隔 / 每天某时刻 / cron 表达式 */
@Serializable
sealed class JobTrigger {
    @Serializable
    @SerialName("delay")
    data class Delay(val seconds: Long) : JobTrigger()

    @Serializable
    @SerialName("interval")
    data class Interval(val seconds: Long, val startAt: Long? = null) : JobTrigger()

    @Serializable
    @SerialName("once")
    data class Once(val at: Long) : JobTrigger()

    @Serializable
    @SerialName("cron")
    data class Cron(val expr: String, val tz: String? = null) : JobTrigger()
}

@Serializable
data class JobParamDto(
    val name: String,
    val required: Boolean = false,
    val default: String? = null,
    val description: String? = null,
)

data class JobLogChunk(
    val text: String,
    val from: Long,
    val to: Long,
    val totalBytes: Long,
    val cursorInvalid: Boolean = false,
    val binary: Boolean = false,
)

// ---- 实体 <-> JSON 转换 ----

fun WorkspaceJobDefEntity.params(): List<JobParamDto> =
    runCatching { JsonInstant.decodeFromString<List<JobParamDto>>(paramsJson) }.getOrDefault(emptyList())

fun WorkspaceJobDefEntity.env(): Map<String, String> =
    runCatching { JsonInstant.decodeFromString<Map<String, String>>(envJson) }.getOrDefault(emptyMap())

fun WorkspaceJobDefEntity.trigger(): JobTrigger? =
    triggerJson?.let { runCatching { JsonInstant.decodeFromString<JobTrigger>(it) }.getOrNull() }

fun encodeTrigger(trigger: JobTrigger?): String? =
    trigger?.let { runCatching { JsonInstant.encodeToString(it) }.getOrNull() }

fun List<JobParamDto>.toParamDefs(): List<JobParamDef> =
    map { JobParamDef(name = it.name, required = it.required, default = it.default, description = it.description) }
