package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 档位字符串的解析。
 *
 * 关键约定: **认不出返回 null, 不兜底成 AUTO**。
 * 调用方要靠这个区分"没指定"（安静继承上层）与"指定错了"（该出声提示）——
 * 一旦兜底, 一个 typo 就会变成"怎么调都没反应"的谜。
 */
class ReasoningLevelTest {

    @Test
    fun parsesEveryDocumentedSpelling() {
        assertEquals(ReasoningLevel.OFF, ReasoningLevel.fromSpec("off"))
        assertEquals(ReasoningLevel.AUTO, ReasoningLevel.fromSpec("auto"))
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.fromSpec("low"))
        assertEquals(ReasoningLevel.MEDIUM, ReasoningLevel.fromSpec("medium"))
        assertEquals(ReasoningLevel.HIGH, ReasoningLevel.fromSpec("high"))
        assertEquals(ReasoningLevel.XHIGH, ReasoningLevel.fromSpec("xhigh"))
        assertEquals(ReasoningLevel.MAX, ReasoningLevel.fromSpec("max"))
    }

    @Test
    fun isCaseAndWhitespaceInsensitive() {
        assertEquals(ReasoningLevel.HIGH, ReasoningLevel.fromSpec("  HIGH "))
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.fromSpec("Low"))
    }

    @Test
    fun acceptsEnumNamesAndAFewSynonyms() {
        // OFF 的 effort 是 "none", 名字才是 "OFF" —— 两条路都要走得通
        assertEquals(ReasoningLevel.OFF, ReasoningLevel.fromSpec("OFF"))
        assertEquals(ReasoningLevel.OFF, ReasoningLevel.fromSpec("none"))
        assertEquals(ReasoningLevel.OFF, ReasoningLevel.fromSpec("disabled"))
        assertEquals(ReasoningLevel.AUTO, ReasoningLevel.fromSpec("default"))
    }

    @Test
    fun unknownIsNullNotAFallback() {
        assertNull("拼错必须是 null, 不能悄悄当成 AUTO", ReasoningLevel.fromSpec("turbo"))
        assertNull(ReasoningLevel.fromSpec(""))
        assertNull(ReasoningLevel.fromSpec("   "))
        assertNull(ReasoningLevel.fromSpec(null))
    }

    @Test
    fun specsListsWhatIsAccepted() {
        // 报错信息与工具描述都靠它, 所以必须覆盖全部取值
        ReasoningLevel.entries.forEach { level ->
            assertEquals(level, ReasoningLevel.fromSpec(level.effort))
        }
    }
}
