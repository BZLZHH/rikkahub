package me.rerere.rikkahub.data.permission

/**
 * 一次生成运行的授权上下文。
 *
 * - 交互式会话：档位来自设置，规则来自设置，信封 = 全部效果；
 * - 后台执行体：档位 FULL（信封内不再询问——没人可问），信道 BACKGROUND，
 *   信封去掉 HUMAN 与 DELEGATE：子代理不能反过来再派生执行体。
 */
data class PermissionContext(
    val mode: PermissionMode,
    val channel: PermissionChannel,
    val rules: List<PermissionRule> = emptyList(),
    val envelope: Set<ToolEffect> = ALL_EFFECTS,
    val conversationId: String? = null,
) {
    companion object {
        val ALL_EFFECTS: Set<ToolEffect> = ToolEffect.entries.toSet()
        val BACKGROUND_ENVELOPE: Set<ToolEffect> = ALL_EFFECTS - ToolEffect.HUMAN - ToolEffect.DELEGATE

        fun interactive(
            mode: PermissionMode,
            rules: List<PermissionRule>,
            conversationId: String? = null,
        ) = PermissionContext(
            mode = mode,
            channel = PermissionChannel.INTERACTIVE,
            rules = rules,
            conversationId = conversationId,
        )

        fun background(conversationId: String? = null) = PermissionContext(
            mode = PermissionMode.FULL,
            channel = PermissionChannel.BACKGROUND,
            rules = emptyList(),
            envelope = BACKGROUND_ENVELOPE,
            conversationId = conversationId,
        )
    }
}
