package me.rerere.rikkahub.data.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 运行时的语义单测: pipeline / parallel / 上限 / 确定性护栏 / 可恢复 / 结构化输出。
 *
 * 一律走真实 QuickJS（JVM 单测能跑 native 库, 见 WorkflowRuntimeNotes）,
 * 不 mock 求值层 —— 本项目吃过"单测全绿但真机崩"的亏, 能真跑的就真跑。
 */
class WorkflowRuntimeSemanticsTest {

    private fun runtime(
        onAgent: suspend (String, String?, String?) -> String? = { _, _, _ -> null },
        progress: WorkflowProgressSink? = null,
        journal: WorkflowJournal? = null,
        agentConcurrency: Int = 4,
        maxAgents: Int = WorkflowLimits.MAX_AGENTS_PER_RUN,
        maxSchemaAttempts: Int = WorkflowLimits.MAX_SCHEMA_ATTEMPTS,
        tracker: WorkflowAgentTracker = NoopWorkflowAgentTracker,
        defaultReasoning: String? = null,
    ) = WorkflowRuntime(
        // 契约升级成"一次调用"对象后, 测试仍只需要看提示词/schema/label 三样
        agentHandler = WorkflowAgentHandler { call, _ -> onAgent(call.prompt, call.schemaJson, call.label) },
        progress = progress,
        timeoutMillis = 10_000,
        journal = journal,
        agentConcurrency = agentConcurrency,
        maxAgents = maxAgents,
        maxSchemaAttempts = maxSchemaAttempts,
        tracker = tracker,
        defaultReasoning = defaultReasoning,
    )

    /** 失败信息加引号带长度: 字面量 "null" 与真 null 打印出来一模一样, 这坑踩过一次。 */
    private fun show(s: String?): String = if (s == null) "<null>" else "\"" + s + "\"(" + s.length + ")"

    private suspend fun run(body: String, rt: WorkflowRuntime = runtime()): WorkflowExecutionResult =
        rt.execute(WorkflowScriptParser.parse(body))

    // ---- pipeline / parallel ----

    @Test
    fun pipelineKeepsNullsAndPreservesOrder() = runBlocking {
        // 上游明确: pipeline 把每个 null 保留在结果数组里（调用方自己 filter(Boolean)）
        val rt = runtime(onAgent = { prompt, _, _ -> if (prompt == "b") null else prompt.uppercase() })
        val r = run(
            "const out = await pipeline([\"a\", \"b\", \"c\"], x => agent(x))\nreturn out",
            rt,
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals("[\"A\",null,\"C\"]", r.resultJson)
    }

    @Test
    fun pipelinePassesIndexAsSecondArgument() = runBlocking {
        val r = run(
            "const out = await pipeline([\"x\", \"y\"], (item, i) => item + i)\nreturn out",
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals("[\"x0\",\"y1\"]", r.resultJson)
    }

    @Test
    fun parallelAcceptsThunksAndAlreadyStartedPromises() = runBlocking {
        val r = run(
            "const out = await parallel([() => agent(\"t\"), agent(\"p\")])\nreturn out",
            runtime(onAgent = { prompt, _, _ -> "got:" + prompt }),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("thunk 与 promise 都要支持: " + r.resultJson, r.resultJson!!.contains("got:t"))
        assertTrue("thunk 与 promise 都要支持: " + r.resultJson, r.resultJson!!.contains("got:p"))
    }

    @Test
    fun listLimitIsEnforcedAtDocumentedBoundary() = runBlocking {
        // 边界两侧各测一次: 4096 必须能过, 4097 必须被拒（"静默截断"是最坏的结果）
        val ok = run("const a = new Array(4096).fill(0)\nawait parallel(a.map(() => 1))\nreturn a.length")
        assertTrue("4096 项应当放行: " + show(ok.error), ok.ok)
        assertEquals("4096", ok.resultJson)

        val tooMany = run("const a = new Array(4097).fill(0)\nawait parallel(a.map(() => 1))\nreturn a.length")
        assertFalse("4097 项应当被拒", tooMany.ok)
        assertTrue("错误要说清超限: " + show(tooMany.error), tooMany.error!!.contains("4096"))
    }

    @Test
    fun concurrencyGateCapsSimultaneousAgents() = runBlocking {
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val rt = runtime(
            agentConcurrency = 2,
            onAgent = { _, _, _ ->
                val now = live.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(150)
                live.decrementAndGet()
                "ok"
            },
        )
        val r = run("return await pipeline([1, 2, 3, 4, 5, 6], x => agent(\"j\" + x))", rt)
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("不得越过闸门: peak=" + peak.get(), peak.get() <= 2)
        assertTrue("闸门内应当真的并发（否则等于退化成串行）: peak=" + peak.get(), peak.get() >= 2)
    }

    // ---- 确定性护栏 ----

    @Test
    fun clockAndRandomnessAreBlocked() = runBlocking {
        val now = run("return Date.now()")
        assertFalse("Date.now() 应当抛异常", now.ok)
        assertTrue("要说清原因: " + show(now.error), now.error!!.contains("Date.now()"))

        val random = run("return Math.random()")
        assertFalse("Math.random() 应当抛异常", random.ok)
        assertTrue("要说清原因: " + show(random.error), random.error!!.contains("Math.random()"))

        val bare = run("return new Date().getTime()")
        assertFalse("无参 new Date() 应当抛异常", bare.ok)
        assertTrue("要说清原因: " + show(bare.error), bare.error!!.contains("new Date()"))
    }

    @Test
    fun explicitTimestampsStillWork() = runBlocking {
        // 护栏掐的是"读当前时间"这一条路, 不是 Date 本身 —— 否则连格式化都做不了
        val r = run("return new Date(0).getTime()")
        assertTrue("显式时间戳应当可用: " + show(r.error), r.ok)
        assertEquals("0", r.resultJson)
    }

    @Test
    fun agentCountIsCapped() = runBlocking {
        val rt = runtime(onAgent = { _, _, _ -> "x" }, maxAgents = 3)
        val r = run("for (let i = 0; i < 10; i++) { await agent(\"a\") }\nreturn 1", rt)
        assertFalse("超过上限应当失败", r.ok)
        assertTrue("错误要说清上限: " + show(r.error), r.error!!.contains("3"))
    }

    // ---- 可恢复 ----

    @Test
    fun resumeReusesCompletedCalls() = runBlocking {
        val journal = InMemoryWorkflowJournal()
        val calls = AtomicInteger(0)
        val script = WorkflowScriptParser.parse(
            "const a = await agent(\"one\")\nconst b = await agent(\"two\")\nreturn [a, b]",
        )
        suspend fun go(): WorkflowExecutionResult = runtime(
            onAgent = { prompt, _, _ -> calls.incrementAndGet(); "R:" + prompt },
            journal = journal,
        ).execute(script)

        val first = go()
        assertTrue("应成功: " + show(first.error), first.ok)
        assertEquals(2, first.agentsCalled)
        assertEquals(0, first.agentsReused)

        val second = go()
        assertTrue("应成功: " + show(second.error), second.ok)
        assertEquals("第二趟应当全部命中存档", 2, second.agentsReused)
        assertEquals("命中存档就不该再调子代理", 2, calls.get())
        assertEquals("[\"R:one\",\"R:two\"]", second.resultJson)
    }

    @Test
    fun scriptChangeInvalidatesTheWholeArchive() = runBlocking {
        val journal = InMemoryWorkflowJournal()
        val calls = AtomicInteger(0)
        val rt = runtime(onAgent = { _, _, _ -> calls.incrementAndGet().toString() }, journal = journal)

        rt.execute(WorkflowScriptParser.parse("return await agent(\"same\")"))
        assertEquals(1, calls.get())

        // 脚本改了 -> 指纹变了 -> 旧存档一个都不许命中（宁可多跑, 不可错配）。
        // 注意这里必须改**内容**: 指纹会归一化行首缩进与空行, 只多一个换行是不算改动的。
        val changed = rt.execute(WorkflowScriptParser.parse("return await agent(\"same\") + 1"))
        assertTrue("应成功: " + show(changed.error), changed.ok)
        assertEquals("指纹变了就该重跑", 2, calls.get())
        assertEquals(0, changed.agentsReused)
    }

    @Test
    fun resumeStopsAtFirstDivergence() = runBlocking {
        // 上游语义: 从第一个对不上的位置开始, 它以及它之后**全部**重跑,
        // 哪怕后面的调用本身存过档 —— 因为后面的提示词通常是前面结果的函数。
        val journal = InMemoryWorkflowJournal()
        val calls = AtomicInteger(0)
        val firstRun = AtomicInteger(0)
        val script = WorkflowScriptParser.parse(
            "const a = await agent(\"a\")\nconst b = await agent(\"b:\" + a)\nconst c = await agent(\"c\")\nreturn [a, b, c]",
        )
        // 分歧的来源必须是"上游那次调用没存档、重跑后产出不同",
        // 而不是凭空换掉 handler —— 后者不是这个机制要处理的情况（脚本没变就没有理由不复用）。
        val rt = runtime(
            journal = journal,
            onAgent = { prompt, _, _ ->
                calls.incrementAndGet()
                if (prompt == "a" && firstRun.incrementAndGet() == 1) null else if (prompt == "a") "v2" else "X"
            },
        )

        val first = rt.execute(script)
        assertTrue("应成功: " + show(first.error), first.ok)
        assertEquals(3, calls.get())
        assertEquals("返回 null 的调用不存档", 0, first.agentsReused)

        // 第一个调用（返回过 null, 没存档）重跑后产出 v2 -> 第二个的提示词从 "b:null" 变成 "b:v2",
        // 于是从第二个开始一律重跑（前缀式重放）, 第三个虽然提示词没变也要跟着重跑。
        val second = rt.execute(script)
        assertTrue("应成功: " + show(second.error), second.ok)
        assertEquals("从第一个分歧处起全部重跑", 0, second.agentsReused)
        assertEquals("三个都要重跑", 6, calls.get())
    }

    // ---- 结构化输出 ----

    private val filesSchema =
        "{ type: \"object\", required: [\"files\"], properties: { files: { type: \"array\", items: { type: \"string\" } } } }"

    @Test
    fun structuredOutputIsParsedIntoAnObject() = runBlocking {
        // 上游用法: const found = await agent(..., { schema }); 然后 found.files —— 拿到的是**对象**
        val r = run(
            "const found = await agent(\"list\", { schema: " + filesSchema + " })\nreturn found.files.length",
            runtime(onAgent = { _, _, _ -> "{\"files\":[\"a.ts\",\"b.ts\"]}" }),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals("2", r.resultJson)
    }

    @Test
    fun schemaIsAttachedToThePromptAndRetried() = runBlocking {
        val seenPrompts = mutableListOf<String>()
        val calls = AtomicInteger(0)
        val r = run(
            "return await agent(\"list\", { schema: " + filesSchema + " })",
            runtime(onAgent = { prompt, _, _ ->
                seenPrompts += prompt
                if (calls.incrementAndGet() == 1) "我不知道" else "{\"files\":[]}"
            }),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals(2, calls.get())
        assertTrue("schema 必须附进提示词: " + seenPrompts[0], seenPrompts[0].contains("files"))
        assertTrue("重试必须说清上次哪里不对: " + seenPrompts[1], seenPrompts[1].contains("不满足") || seenPrompts[1].contains("合法 JSON"))
    }

    @Test
    fun schemaExhaustionFailsTheCall() = runBlocking {
        val calls = AtomicInteger(0)
        val rt = runtime(
            maxSchemaAttempts = 3,
            onAgent = { _, _, _ -> calls.incrementAndGet(); "永远不是 JSON" },
        )
        val r = run("return await agent(\"list\", { schema: " + filesSchema + " })", rt)
        assertFalse("应当失败", r.ok)
        assertEquals("重试次数应当被尊重", 3, calls.get())
        assertTrue("错误要带最后一次失败原因: " + show(r.error), r.error!!.contains("structured output failed"))
    }

    @Test
    fun selfContradictingSchemaFailsBeforeSpendingAnAgent() = runBlocking {
        // 上游: 能证明矛盾的 schema 在**启动子代理之前**就失败, 别让它白跑
        val calls = AtomicInteger(0)
        val r = run(
            "return await agent(\"x\", { schema: { type: \"object\", required: [\"a\"], additionalProperties: false, properties: { b: { type: \"string\" } } } })",
            runtime(onAgent = { _, _, _ -> calls.incrementAndGet(); "{}" }),
        )
        assertFalse("应当失败", r.ok)
        assertEquals("不该启动子代理", 0, calls.get())
        assertTrue("要说清矛盾: " + show(r.error), r.error!!.contains("contradicts"))
    }

    @Test
    fun markdownFencesAreTolerated() = runBlocking {
        // 多说一句 "Here you go" 或包个围栏, 不该被判成不合规反复重试
        val r = run(
            "return await agent(\"list\", { schema: " + filesSchema + " })",
            runtime(onAgent = { _, _, _ -> "Here you go:\n```json\n{\"files\":[\"x\"]}\n```" }),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertTrue("应当解析出 JSON: " + r.resultJson, r.resultJson!!.contains("x"))
    }

    // ---- 落库/UI 契约 ----

    /** 记录每一次调用的起止。UI 的右栏就是照这个写出来的, 所以它的语义要锁住。 */
    private class RecordingTracker : WorkflowAgentTracker {
        val started = mutableListOf<Pair<WorkflowAgentCall, Boolean>>()
        /** callIndex -> (reused, status) */
        val finished = mutableListOf<Triple<Int, Boolean, String>>()
        val sessions = mutableListOf<String>()

        override suspend fun start(call: WorkflowAgentCall, reused: Boolean): WorkflowAgentObserver {
            started += call to reused
            return object : WorkflowAgentObserver {
                override fun onSession(sessionId: String) { sessions += sessionId }
            }
        }

        override suspend fun finish(
            call: WorkflowAgentCall,
            reused: Boolean,
            status: String,
            resultText: String?,
            error: String?,
        ) {
            finished += Triple(call.callIndex, reused, status)
        }
    }

    @Test
    fun trackerSeesPhaseAndReuse() = runBlocking {
        // UI 左栏按 phase 分组、右栏标"复用" —— 这两件事都靠这里传出去的信息。
        val journal = InMemoryWorkflowJournal()
        val tracker = RecordingTracker()
        val calls = AtomicInteger(0)
        val script = WorkflowScriptParser.parse(
            "phase(\"sweep\")\nconst a = await agent(\"a\")\n" +
                "phase(\"verify\")\nconst b = await agent(\"b\")\nreturn [a, b]",
        )
        suspend fun go(): WorkflowExecutionResult = runtime(
            onAgent = { _, _, _ -> "r" + calls.incrementAndGet() },
            journal = journal,
            tracker = tracker,
        ).execute(script)

        val first = go()
        assertTrue("应成功: " + show(first.error), first.ok)
        assertEquals("phase 顺序要能报出来（左栏顺序）", listOf("sweep", "verify"), first.phases)
        assertEquals(
            "每个 agent 要带上它起跑时所在的 phase",
            listOf("sweep", "verify"),
            tracker.started.map { it.first.phase },
        )
        assertEquals(listOf(false, false), tracker.started.map { it.second })
        assertEquals(2, tracker.finished.size)

        val second = go()
        assertEquals("第二趟应当全部命中存档", 2, second.agentsReused)
        assertEquals(
            "复用命中的调用也要被记下来（否则 UI 上看不出省了工作）",
            listOf(true, true),
            tracker.started.drop(2).map { it.second },
        )
        assertEquals("复用不该再真的跑 agent", 2, calls.get())
    }

    @Test
    fun trackerClosesFailedCalls() = runBlocking {
        val tracker = RecordingTracker()
        val r = run(
            "return await agent(\"x\", { schema: { type: \"object\" } })",
            runtime(maxSchemaAttempts = 2, onAgent = { _, _, _ -> "永远不是 JSON" }, tracker = tracker),
        )
        assertFalse("应当失败", r.ok)
        assertEquals("失败的调用也必须收尾, 否则 UI 上会永远停在\"运行中\"", 1, tracker.finished.size)
        assertEquals("FAILED", tracker.finished.single().third)
    }

    // ---- 推理档位 ----

    @Test
    fun reasoningIsPerAgentWithRunDefault() = runBlocking {
        // 最常见的形态: 扇出一堆便宜的读手, 再用一个高强度的综合者 ——
        // "整趟一个档位"表达不了它, 所以逐 agent 的覆盖是必须的。
        val tracker = RecordingTracker()
        val r = run(
            "const a = await agent(\"a\", { reasoning: \"high\" })\n" +
                "const b = await agent(\"b\")\nreturn [a, b]",
            runtime(onAgent = { _, _, _ -> "x" }, tracker = tracker, defaultReasoning = "low"),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals(
            "自己的档位优先, 没写的用整趟默认",
            listOf("high", "low"),
            tracker.started.map { it.first.reasoning },
        )
    }

    @Test
    fun noReasoningAnywhereMeansInherit() = runBlocking {
        val tracker = RecordingTracker()
        val r = run("return await agent(\"a\")", runtime(onAgent = { _, _, _ -> "x" }, tracker = tracker))
        assertTrue(r.ok)
        assertEquals(
            "不指定就是 null = 继承主会话那一档",
            listOf(null),
            tracker.started.map { it.first.reasoning },
        )
    }

    @Test
    fun unknownReasoningWarnsButDoesNotFailTheRun() = runBlocking {
        // 一个拼错的档位没道理让整趟白跑, 但也不能装作没看见 —— 要进进度时间线
        val logs = mutableListOf<String>()
        val sink = object : WorkflowProgressSink {
            override fun onPhase(title: String) {}
            override fun onLog(message: String, level: String) { logs += level + ":" + message }
        }
        val tracker = RecordingTracker()
        val r = run(
            "return await agent(\"a\", { reasoning: \"turbo\" })",
            runtime(onAgent = { _, _, _ -> "x" }, progress = sink, tracker = tracker),
        )
        assertTrue("不该因此失败: " + show(r.error), r.ok)
        assertEquals("认不出就退回继承", listOf(null), tracker.started.map { it.first.reasoning })
        assertTrue(
            "必须出声: " + logs,
            logs.any { it.startsWith("warn:") && it.contains("turbo") },
        )
    }

    // ---- 进度 ----

    @Test
    fun phasesAndLogsReachTheSink() = runBlocking {
        val phases = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val sink = object : WorkflowProgressSink {
            override fun onPhase(title: String) { phases += title }
            override fun onLog(message: String, level: String) { logs += message }
        }
        val r = run(
            "phase(\"search\")\nlog(\"开始\")\nawait agent(\"x\")\nphase(\"write\")\nreturn 1",
            runtime(onAgent = { _, _, _ -> "y" }, progress = sink),
        )
        assertTrue("应成功: " + show(r.error), r.ok)
        assertEquals(listOf("search", "write"), phases)
        assertNotNull(logs.firstOrNull { it.contains("开始") })
    }
}
