package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),

    @SerialName("auto")
    AUTO(-1, "auto"),

    @SerialName("low")
    LOW(1_000, "low"),

    @SerialName("medium")
    MEDIUM(2_000, "medium"),

    @SerialName("high")
    HIGH(8_000, "high"),

    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),

    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        /**
         * 从字符串解析; 认不出返回 null（**不是**退回 AUTO）。
         *
         * 为什么返回 null 而不是兜底: 调用方需要能区分"没指定"与"指定了一个我不认识的"。
         * 前者该安静地继承上层设置, 后者该**出声**告诉用户拼错了 —— 否则一个 typo
         * 会变成"怎么调都没反应"的谜。
         */
        fun fromSpec(value: String?): ReasoningLevel? {
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return null
            entries.firstOrNull { it.effort.equals(raw, ignoreCase = true) }?.let { return it }
            return when (raw) {
                "none", "disabled", "disable" -> OFF
                "default" -> AUTO
                else -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
        }

        /** 所有可接受的写法, 用于报错提示与工具描述。 */
        val specs: String get() = entries.joinToString(", ") { it.effort }

        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull {
                kotlin.math.abs(
                    it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)
                )
            } ?: AUTO
        }
    }
}
