package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 子代理的一次运行。
 *
 * 为什么单独一张表而不是塞进 `workspace_jobs`: 后者是**进程**语义的账本
 * （pid / exit_code / mode=PIPE|PTY / cwd / command / 字节日志大小 …）,
 * 而子代理根本没有进程 —— 它的执行体是"模型 + 受限工具集", 该记的是轮次、工具调用与 token。
 * 两者是平级的执行体（见 me.rerere.rikkahub.data.run.RunKind）, 共用编排层而非共用表。
 *
 * 注意: 状态列用 String 存（同 [WorkspaceJobEntity]）, 不走 TypeConverter ——
 * 这样新增实体不会给自动迁移引入额外风险。读取时用 RunStatus.from() 解析。
 */
@Entity(
    tableName = "agent_runs",
    indices = [
        Index(value = ["workspace_id", "created_at"]),
        Index(value = ["status"]),
    ],
)
data class AgentRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** 发起它的会话; 完成后按需唤醒 */
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    /** 展示标题: 用户语言的一句话（同 job 的 reason 约定） */
    @ColumnInfo("title")
    val title: String,
    /** 交给子代理的指令 */
    @ColumnInfo("prompt")
    val prompt: String,
    /** 使用的模型; null 表示回退到设置的"快速模型" */
    @ColumnInfo("model_id")
    val modelId: String? = null,
    /** 工具白名单（组名数组的 JSON, 见 AgentToolGroup） */
    @ColumnInfo("tool_groups_json")
    val toolGroupsJson: String = "[]",
    /** 额外参数（cwd / max_steps / result_schema 等） */
    @ColumnInfo("args_json")
    val argsJson: String = "{}",
    /** 结构化返回值（子代理按 result_schema 产出, 供父代理/工作流程序化消费） */
    @ColumnInfo("result_json")
    val resultJson: String? = null,
    /** 追加式转录文件（相对 job 日志目录）, 用户可读 */
    @ColumnInfo("transcript")
    val transcript: String? = null,
    @ColumnInfo("status")
    val status: String = "PENDING",
    /** 已完成的 AI 轮次 */
    @ColumnInfo("steps_done")
    val stepsDone: Int = 0,
    /** 工具调用次数 */
    @ColumnInfo("tool_calls")
    val toolCalls: Int = 0,
    @ColumnInfo("tokens_in")
    val tokensIn: Int = 0,
    @ColumnInfo("tokens_out")
    val tokensOut: Int = 0,
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
    @ColumnInfo("error")
    val error: String? = null,
    @ColumnInfo("notify")
    val notify: Boolean = true,
    @ColumnInfo("auto_wake")
    val autoWake: Boolean = false,
    @ColumnInfo("max_runtime_ms")
    val maxRuntimeMs: Long = 30L * 60 * 1000,
)
