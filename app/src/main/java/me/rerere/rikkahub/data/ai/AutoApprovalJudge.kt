package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

private const val TAG = "AutoApprovalJudge"

/**
 * 工具调用自动审批: 把"需要人工确认"的工具调用先交给用户指定的模型判断。
 *
 * 设计取舍:
 * - 只输出 JSON（approve/reason），解析失败一律视为不批准 → 退回人工确认（fail-safe）;
 * - 默认只对高影响工具（job_ / workspace_ / mcp__）生效, 防止把所有工具都放开;
 * - 判据里带上用户最近的意图文本, 让模型判断"这是不是用户想要的操作";
 * - 每次判断都写日志（设置页提示用户可在日志页审计）。
 */
class AutoApprovalJudge(
    private val providerManager: ProviderManager,
) {
    data class Decision(
        /** 是否自动批准; judged=false 表示压根没参与判断（功能没开/不在范围内） */
        val approved: Boolean,
        val reason: String,
        val judged: Boolean,
    ) {
        companion object {
            val NotJudged = Decision(approved = false, reason = "", judged = false)
        }
    }

    suspend fun judge(
        settings: Settings,
        toolName: String,
        arguments: JsonElement,
        userIntent: String,
    ): Decision {
        if (!settings.autoApprovalEnabled) return Decision.NotJudged
        if (settings.autoApprovalActionToolsOnly && !toolName.isHighImpactTool()) return Decision.NotJudged

        val model = settings.autoApprovalModelId?.let { settings.findModelById(it) }
            ?: settings.findModelById(settings.fastModelId)
            ?: return Decision.NotJudged
        val provider = model.findProvider(settings.providers) ?: return Decision.NotJudged

        val prompt = buildPrompt(toolName, arguments, userIntent)
        return runCatching {
            val handler = providerManager.getProviderByType(provider)
            val result = handler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0f,
                    maxTokens = 200,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            parse(result.message.toText())
        }.onFailure {
            Log.w(TAG, "auto approval judge failed for $toolName, fall back to manual", it)
        }.getOrDefault(Decision(approved = false, reason = "judge error", judged = true))
            .also { decision ->
                Log.i(
                    TAG,
                    "auto approval: tool=$toolName approved=${decision.approved} reason=${decision.reason}",
                )
            }
    }

    private fun parse(text: String): Decision {
        val jsonText = text.substringAfter('{', "").let { if (it.isEmpty()) "" else "{" + it.substringBeforeLast('}') + "}" }
        if (jsonText.isEmpty()) return Decision(false, "unparsable judge output", judged = true)
        return runCatching {
            val obj = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(jsonText).jsonObject
            val approved = obj["approve"]?.jsonPrimitive?.booleanOrNull
                ?: obj["approved"]?.jsonPrimitive?.booleanOrNull
                ?: false
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            Decision(approved = approved, reason = reason, judged = true)
        }.getOrElse { Decision(false, "unparsable judge output", judged = true) }
    }

    private fun buildPrompt(toolName: String, arguments: JsonElement, userIntent: String): String {
        val args = arguments.toString().take(2_000)
        return """
            你是工具调用审批器。用户在 RikkaHub（Android AI 客户端）里开启了"自动审批"。
            请判断下面这次工具调用是否可以**自动批准**，替用户省去手动确认。

            判据（按重要性）：
            1. 是否符合用户最近的意图；2. 是否只在该应用自己的沙箱/工作区内操作；
            3. 是否可能造成不可逆破坏（删除/覆盖大量数据、外发隐私、提权、影响系统）；
            4. 参数是否合理（路径、命令、范围是否与意图一致）。

            用户最近的意图：
            ${userIntent.take(1_500).ifBlank { "(无上下文)" }}

            工具名：$toolName
            工具参数（JSON，可能被截断）：
            $args

            只输出一行 JSON，不要任何其它文字：
            {"approve": true|false, "reason": "一句话理由（中文）"}
        """.trimIndent()
    }
}

/** 高影响工具: 会真正动到文件/进程/外部系统的那些 */
internal fun String.isHighImpactTool(): Boolean =
    startsWith("job_") || startsWith("workspace_") || startsWith("mcp__")
