package me.rerere.rikkahub.data.permission

import android.util.Log

private const val TAG = "PermissionKernel"

/**
 * 唯一裁决点。
 *
 * 顺序固定：信封 → 规则（deny → ask → allow）→ 档位 → 通道降级。
 * 纯函数、无网络、无 LLM：同样的输入永远得到同样的结论，可回放、可测。
 */
object PermissionKernel {

    fun evaluate(call: NormalizedCall, context: PermissionContext): PermissionDecision {
        // 1. 信封：这个执行体最多能干到哪一步
        val outside = call.effects.filter { it !in context.envelope }
        if (outside.isNotEmpty()) {
            return deny(
                call, context, "out_of_envelope",
                "超出这次运行被授权的范围（" + outside.joinToString { it.name } + "）"
            )
        }

        // 2. 规则：deny 永远优先，其次 ask，最后 allow（更具体的在前）
        val matched = context.rules
            .asSequence()
            .filter { it.matches(call) }
            .sortedWith(compareByDescending<PermissionRule> { it.specificity() }.thenBy { it.id })
            .toList()

        matched.firstOrNull { it.decision == RuleDecision.DENY }?.let {
            return deny(call, context, "rule", it.label())
        }
        matched.firstOrNull { it.decision == RuleDecision.ASK }?.let {
            return ask(call, context)
        }
        if (!call.opaque) {
            matched.firstOrNull { it.decision == RuleDecision.ALLOW }?.let {
                return allow(call, context, "rule", it.id)
            }
        }

        // 3. 档位：多效果取最严（任一要问就要问，任一禁止就禁止）
        val decisions = call.effects.map { context.mode.fallback(it) }
        when {
            decisions.any { it == RuleDecision.DENY } -> return deny(
                call, context, "mode",
                "当前权限档位是「" + context.mode.label() + "」，这项操作被禁止"
            )
            decisions.any { it == RuleDecision.ASK } -> return ask(call, context)
        }

        // 4. 解析不确定的命令：档位不能替它做主（FULL 是明确的"全放行"除外）
        if (call.opaque && context.mode != PermissionMode.FULL) {
            return ask(call, context)
        }

        return allow(call, context, "mode", null)
    }

    fun ruleFor(call: NormalizedCall): PermissionRule? = PermissionNormalizer.propose(call)

    private fun allow(
        call: NormalizedCall,
        context: PermissionContext,
        basis: String,
        ruleId: String?,
    ): PermissionDecision {
        PermissionAudit.record(call, context, "allow", basis, ruleId)
        return PermissionDecision.Allow(basis, ruleId)
    }

    private fun ask(call: NormalizedCall, context: PermissionContext): PermissionDecision {
        if (context.channel != PermissionChannel.INTERACTIVE) {
            return deny(call, context, "unattended", "后台执行体没有人可以确认，直接拒绝；需要主会话先授权")
        }
        PermissionAudit.record(call, context, "ask", "none", null)
        val proposal = PermissionNormalizer.propose(call)
        val info = ConsentInfo(
            summary = call.summary,
            grants = call.delegation?.grants.orEmpty(),
            excludes = call.delegation?.excludes.orEmpty(),
            proposal = proposal?.label(),
        )
        return PermissionDecision.Ask(info, proposal)
    }

    private fun deny(
        call: NormalizedCall,
        context: PermissionContext,
        code: String,
        message: String,
    ): PermissionDecision {
        PermissionAudit.record(call, context, "deny", code, null)
        return PermissionDecision.Deny(code, message)
    }

    private fun log(decision: String, call: NormalizedCall, context: PermissionContext) {
        Log.i(TAG, "decision=" + decision + " tool=" + call.tool + " mode=" + context.mode.name +
            " channel=" + context.channel.name + " summary=" + call.summary)
    }
}
