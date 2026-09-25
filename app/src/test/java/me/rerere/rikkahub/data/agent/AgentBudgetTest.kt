package me.rerere.rikkahub.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮次预算: 这是一条"会烧钱"的规则, 所以必须被测住。
 */
class AgentBudgetTest {

    @Test
    fun countsOneRoundPerToolCycle() {
        val budget = AgentBudget(maxRounds = 3)
        // 第一轮: 助手要工具 -> 工具执行完 -> 助手给新回复
        assertFalse(budget.onAssistantMessage(true))
        assertFalse(budget.onAssistantMessage(false))
        assertEquals(1, budget.rounds)
    }

    @Test
    fun repeatedChunksInOneRoundDoNotCountTwice() {
        val budget = AgentBudget(maxRounds = 3)
        // 一次生成内部的多次 chunk 都是"带工具", 不该被重复计数
        assertFalse(budget.onAssistantMessage(true))
        assertFalse(budget.onAssistantMessage(true))
        assertFalse(budget.onAssistantMessage(true))
        assertEquals(0, budget.rounds)
        assertFalse(budget.onAssistantMessage(false))
        assertEquals(1, budget.rounds)
    }

    @Test
    fun plainReplyWithoutToolsNeverCountsARound() {
        val budget = AgentBudget(maxRounds = 2)
        assertFalse(budget.onAssistantMessage(false))
        assertFalse(budget.onAssistantMessage(false))
        assertEquals(0, budget.rounds)
    }

    @Test
    fun nonAssistantEventsAreIgnored() {
        val budget = AgentBudget(maxRounds = 2)
        assertFalse(budget.onAssistantMessage(null))
        assertEquals(0, budget.rounds)
    }

    @Test
    fun stopsExactlyAtTheBudget() {
        val budget = AgentBudget(maxRounds = 2)
        assertFalse(budget.onAssistantMessage(true))
        assertFalse(budget.onAssistantMessage(false))
        // 第二轮结束时应当刚好触发
        assertFalse(budget.onAssistantMessage(true))
        assertTrue("应当在本轮结束时超预算", budget.onAssistantMessage(false))
        assertEquals(2, budget.rounds)
    }

    @Test
    fun keepsReportingExceededAfterwards() {
        val budget = AgentBudget(maxRounds = 1)
        assertFalse(budget.onAssistantMessage(true))
        assertTrue(budget.onAssistantMessage(false))
        // 即便调用方没及时中止, 后续也必须持续报告"已超"
        assertFalse(budget.onAssistantMessage(true))
        assertTrue(budget.onAssistantMessage(false))
    }
}
