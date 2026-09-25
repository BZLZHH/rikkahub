package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 工作流参数（声明式, 与 job 的 JobParamDef 同形, 但独立类型以免两边演进互相牵制）。 */
@Serializable
data class WorkflowParam(
    val name: String,
    val required: Boolean = false,
    val default: String? = null,
    val description: String? = null,
)

/** 步骤失败时的策略。 */
@Serializable
enum class StepOnError {
    /** 立即终止整个运行, 后续步骤记为 SKIPPED（默认: 大多数流程"前一步失败就没必要继续"） */
    @SerialName("fail")
    FAIL,

    /** 记录失败继续往下跑（用于"尽力而为"的收尾/上报类步骤） */
    @SerialName("continue")
    CONTINUE,
}

/**
 * 从外部文件捕获本步产出的键值。
 *
 * 步骤之间是**独立进程**, 没法用内存传值, 所以走"文件 + 声明式提取":
 * 引擎为每步注入 RIKKA_OUT 指向一个临时文件, 步骤把结果写成 JSON,
 * 引擎按 [keys] 取出其中若干键暴露给后续步骤。
 */
@Serializable
data class WorkflowExport(
    val keys: List<String> = emptyList(),
)

/** 工作流的一步。 */
@Serializable
data class WorkflowStep(
    /** run 内唯一; 同时是变量命名空间（{{steps.<id>.<key>}}） */
    val id: String,
    val command: String,
    val cwd: String = "",
    /** PIPE（默认）或 PTY */
    val mode: String = "PIPE",
    /** 覆盖/补充工作流级参数（值仍可含占位符） */
    val params: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    /** 单步超时（秒）; 不填沿用工作流级上限 */
    val timeoutSeconds: Long? = null,
    val onError: StepOnError = StepOnError.FAIL,
    /** 失败重试次数（默认 0）; 每次重试都是新的一次执行 */
    val retries: Int = 0,
    val export: WorkflowExport? = null,
)

/** 步骤在一个 run 中的状态。 */
@Serializable
enum class WorkflowStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    KILLED,
    TIMED_OUT,
;

    val isFinished: Boolean get() = this != PENDING && this != RUNNING
}

/** run 里某一步的运行时状态快照（存进 run 的 step_states_json）。 */
@Serializable
data class WorkflowStepState(
    val id: String,
    val status: WorkflowStepStatus = WorkflowStepStatus.PENDING,
    /** 该步对应的 shell 后台任务 id（可在任务页看到它的日志） */
    val jobId: String? = null,
    val exitCode: Int? = null,
    val attempts: Int = 0,
    val error: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** export 出来的键值, 供后续步骤引用 */
    val outputs: Map<String, String> = emptyMap(),
)
