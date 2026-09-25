package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.Tool

/**
 * 子代理可以申请的工具组。
 *
 * 默认最小权限: 只给 [WORKSPACE]（干活所需）;其余要父代理显式放开。
 */
enum class AgentToolGroup {
    /** 工作区内读写文件 / 执行命令（子代理的主要用途, 含 job_*） */
    WORKSPACE,

    /** 检索外部资料 */
    WEB,

    /** 本机能力（时间、剪贴板、JS 引擎、TTS、日历） */
    LOCAL,

    /** 助手记忆 */
    MEMORY,

    /** 参考历史会话 */
    CONVERSATION,

    /** 技能（可复用的提示词/流程） */
    SKILL;

    companion object {
        fun from(value: String?): AgentToolGroup? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        /** 解析白名单; 无法识别的组名忽略(不报错, 但也不悄悄放开)。 */
        fun parseAll(names: List<String>): Set<AgentToolGroup> =
            names.mapNotNull { from(it) }.toSet()
    }
}

/**
 * 工具白名单过滤。
 *
 * 分两层:
 * 1. **组开关**: 父代理声明开哪些组;
 * 2. **永不放开**: 无论怎么声明都不给子代理的工具。
 *
 * 第 2 条是硬性的, 其中两类尤其重要:
 * - **递归**: `subagent_*` 一律不给（递归必须显式且有限, 前置阶段直接禁死）;
 * - **编排**: `workflow_*` 一律不给（"编排其他执行体"的能力不该被某个执行体内部持有）;
 * - **人工交互**: `ask_user` 一律不给 —— 子代理没有 UI 通道, 给了就是死锁。
 */
object AgentToolPolicy {

    /** 精确工具名 → 所属组。用精确名单而不是前缀匹配: 命名风格不统一, 猜前缀会漏或误伤。 */
    private val TOOL_GROUPS: Map<String, AgentToolGroup> = mapOf(
        // 工作区
        "workspace_read_file" to AgentToolGroup.WORKSPACE,
        "workspace_write_file" to AgentToolGroup.WORKSPACE,
        "workspace_edit_file" to AgentToolGroup.WORKSPACE,
        "workspace_shell" to AgentToolGroup.WORKSPACE,
        // 后台任务（job_* 全部按前缀归入 WORKSPACE）
        "job_start" to AgentToolGroup.WORKSPACE,
        "job_run" to AgentToolGroup.WORKSPACE,
        "job_list" to AgentToolGroup.WORKSPACE,
        "job_status" to AgentToolGroup.WORKSPACE,
        "job_logs" to AgentToolGroup.WORKSPACE,
        "job_wait" to AgentToolGroup.WORKSPACE,
        "job_kill" to AgentToolGroup.WORKSPACE,
        "job_restart" to AgentToolGroup.WORKSPACE,
        "job_remove" to AgentToolGroup.WORKSPACE,
        "job_send" to AgentToolGroup.WORKSPACE,
        "job_screen" to AgentToolGroup.WORKSPACE,
        "job_def_create" to AgentToolGroup.WORKSPACE,
        "job_def_update" to AgentToolGroup.WORKSPACE,
        "job_def_list" to AgentToolGroup.WORKSPACE,
        "job_def_remove" to AgentToolGroup.WORKSPACE,
        // 检索
        "search_web" to AgentToolGroup.WEB,
        "scrape_web" to AgentToolGroup.WEB,
        // 本机能力
        "get_time_info" to AgentToolGroup.LOCAL,
        "get_screen_time" to AgentToolGroup.LOCAL,
        "clipboard_tool" to AgentToolGroup.LOCAL,
        "eval_javascript" to AgentToolGroup.LOCAL,
        "text_to_speech" to AgentToolGroup.LOCAL,
        "calendar_create" to AgentToolGroup.LOCAL,
        "calendar_query" to AgentToolGroup.LOCAL,
        // 记忆 / 会话 / 技能
        "memory_tool" to AgentToolGroup.MEMORY,
        "conversation_search" to AgentToolGroup.CONVERSATION,
        "recent_chats" to AgentToolGroup.CONVERSATION,
        "use_skill" to AgentToolGroup.SKILL,
    )

    /** 永远不给子代理的工具名前缀。 */
    private val NEVER_ALLOWED_PREFIXES = listOf("subagent", "workflow")

    /** 永远不给子代理的精确工具名。 */
    private val NEVER_ALLOWED_NAMES = setOf("ask_user")

    /** 工具属于哪个组; 未登记的工具返回 null（因此不会被放行）。 */
    fun groupOf(toolName: String): AgentToolGroup? = TOOL_GROUPS[toolName]

    /**
     * 按白名单过滤工具集。
     *
     * [excludeJobTools] 用于"给工作区能力但不许它自己起后台任务"的场景。
     */
    fun filter(
        tools: List<Tool>,
        groups: Set<AgentToolGroup>,
        excludeJobTools: Boolean = false,
    ): List<Tool> = tools.filter { tool ->
        val name = tool.name
        when {
            NEVER_ALLOWED_PREFIXES.any { name.startsWith(it) } -> false
            name in NEVER_ALLOWED_NAMES -> false
            excludeJobTools && name.startsWith("job_") -> false
            else -> groupOf(name)?.let { it in groups } ?: false
        }
    }

    /** 供 UI / 提示词展示: 这一次实际给了子代理哪些工具。 */
    fun describe(tools: List<Tool>): String =
        if (tools.isEmpty()) "(none)" else tools.joinToString(", ") { it.name }
}
