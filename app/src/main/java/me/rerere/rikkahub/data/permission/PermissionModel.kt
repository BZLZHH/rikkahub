package me.rerere.rikkahub.data.permission

import kotlinx.serialization.Serializable

/**
 * 工具会造成的**效果**。
 *
 * 工具不再声明"要不要问我"，只声明"我会做什么"：问不问由 [PermissionKernel] 结合
 * 权限档位、记住的规则、运行信封与通道决定。
 */
@Serializable
enum class ToolEffect {
    /** 无副作用（时间、记忆、技能、历史会话……） */
    NONE,
    FS_READ,
    FS_WRITE,
    FS_DELETE,
    /** 执行任意进程（含其内部的一切） */
    PROC_EXEC,
    /** 控制已经在跑的执行体（查状态、看日志、停止、重启） */
    PROC_CONTROL,
    NET,
    /** 本机能力：日历、剪贴板、TTS、屏幕使用时间 */
    DEVICE,
    /** 向用户提问等交互原语，永远不参与授权判断 */
    HUMAN,
    /** 派生一个新的自主执行体（后台任务 / 子代理 / 工作流） */
    DELEGATE,
    /** 外部服务（MCP）—— 会改变本机之外的状态 */
    EXTERNAL,
    /** 没登记过的工具：宁可多问一句 */
    UNKNOWN,
}

@Serializable
enum class RuleDecision { ALLOW, ASK, DENY }

@Serializable
enum class RuleOrigin { USER, SYSTEM }

/**
 * 一条持久规则。只有用户点过"以后都这样"才会产生 USER 规则；
 * SYSTEM 规则由代码内置（例如只读工具免问），用户不可编辑。
 */
@Serializable
data class PermissionRule(
    val id: String,
    val decision: RuleDecision = RuleDecision.ALLOW,
    /** 工具名，或 * 表示任意工具 */
    val tool: String = "*",
    val effect: ToolEffect? = null,
    /** 命令族（argv 第一段），例如 gradle；命中时要求命令的**每一段**都是它 */
    val commandPrefix: String? = null,
    val pathPrefix: String? = null,
    val origin: RuleOrigin = RuleOrigin.USER,
    val createdAt: Long = 0L,
) {
    fun matches(call: NormalizedCall): Boolean {
        if (tool != "*" && tool != call.tool) return false
        if (effect != null && effect !in call.effects) return false
        if (commandPrefix != null) {
            val execs = call.targets.filterIsInstance<CallTarget.Exec>()
            if (execs.isEmpty()) return false
            if (!execs.all { exec -> exec.segments.all { it.firstOrNull() == commandPrefix } }) return false
        }
        if (pathPrefix != null) {
            val paths = call.targets.filterIsInstance<CallTarget.Fs>()
            if (paths.isEmpty() || paths.any { !it.path.startsWith(pathPrefix) }) return false
        }
        return true
    }

    fun specificity(): Int =
        (if (tool != "*") 4 else 0) + (if (effect != null) 2 else 0) +
            (commandPrefix?.length ?: 0) + (if (pathPrefix != null) 3 else 0)

    fun label(): String = when {
        decision == RuleDecision.DENY -> "禁止" + toolLabel()
        commandPrefix != null -> "允许「" + commandPrefix + "」开头的命令"
        pathPrefix != null -> "允许写「" + pathPrefix + "」下的文件"
        tool == "*" -> "允许所有工具"
        else -> "允许" + toolLabel()
    }

    private fun toolLabel(): String = when (tool) {
        "workspace_shell" -> "执行命令"
        "workspace_write_file", "workspace_edit_file" -> "写文件"
        "job_start", "job_run" -> "启动后台任务"
        "subagent_start" -> "派生子代理"
        "workflow_run" -> "运行工作流"
        else -> tool
    }
}

sealed interface CallTarget {
    data class Fs(val path: String, val write: Boolean) : CallTarget
    data class Exec(val segments: List<List<String>>) : CallTarget
    data class Net(val host: String?) : CallTarget
    data class Device(val kind: String) : CallTarget
}

/** 派生子执行体时，给用户看的"这张卡里有什么"。 */
data class DelegationSummary(
    val title: String,
    val grants: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val durationSeconds: Long? = null,
)

/** 归一化后的调用：规则匹配与审计都只看这个结构，不看原始 JSON。 */
data class NormalizedCall(
    val tool: String,
    val effects: Set<ToolEffect>,
    val targets: List<CallTarget> = emptyList(),
    /** 解析不确定（命令替换、eval、管道进 shell……）—— 永远不能被规则放行 */
    val opaque: Boolean = false,
    val summary: String,
    val delegation: DelegationSummary? = null,
)

/** 同意卡片上的结构化信息，落在 tool part 的 metadata 里，随消息持久化。 */
@Serializable
data class ConsentInfo(
    val summary: String,
    val grants: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    /** "以后都这样"按钮的文案；null 表示这条调用没有可记的规则 */
    val proposal: String? = null,
)

sealed interface PermissionDecision {
    data class Allow(val basis: String, val ruleId: String? = null) : PermissionDecision
    data class Ask(val info: ConsentInfo, val proposalRule: PermissionRule? = null) : PermissionDecision
    data class Deny(val code: String, val message: String) : PermissionDecision
}

@Serializable
enum class PermissionMode {
    /** 只看不改 */
    PLAN,
    /** 每次都问（默认） */
    ASK,
    /** 改文件不问，执行/设备仍问 */
    EDIT,
    /** 工作区全信任：读写执行都不问，设备与委托仍问 */
    TRUSTED,
    /** 开发调试用：全部放行（仍受运行信封约束） */
    FULL;

    /** 没有规则命中时，某个效果在这个档位下的默认答案。 */
    fun fallback(effect: ToolEffect): RuleDecision = when (this) {
        FULL -> RuleDecision.ALLOW
        PLAN -> when (effect) {
            ToolEffect.NONE, ToolEffect.HUMAN, ToolEffect.FS_READ, ToolEffect.NET -> RuleDecision.ALLOW
            else -> RuleDecision.DENY
        }
        ASK -> when (effect) {
            ToolEffect.NONE, ToolEffect.HUMAN, ToolEffect.FS_READ, ToolEffect.NET,
            ToolEffect.PROC_CONTROL -> RuleDecision.ALLOW
            else -> RuleDecision.ASK
        }
        EDIT -> when (effect) {
            ToolEffect.NONE, ToolEffect.HUMAN, ToolEffect.FS_READ, ToolEffect.NET,
            ToolEffect.PROC_CONTROL, ToolEffect.FS_WRITE -> RuleDecision.ALLOW
            else -> RuleDecision.ASK
        }
        TRUSTED -> when (effect) {
            ToolEffect.NONE, ToolEffect.HUMAN, ToolEffect.FS_READ, ToolEffect.NET,
            ToolEffect.PROC_CONTROL, ToolEffect.FS_WRITE, ToolEffect.FS_DELETE,
            ToolEffect.PROC_EXEC -> RuleDecision.ALLOW
            else -> RuleDecision.ASK
        }
    }

    fun label(): String = when (this) {
        PLAN -> "只看不改"
        ASK -> "每次都问"
        EDIT -> "改文件不问"
        TRUSTED -> "工作区全信任"
        FULL -> "全部放行（调试）"
    }
}

enum class PermissionChannel { INTERACTIVE, VOICE, BACKGROUND, WAKE }
