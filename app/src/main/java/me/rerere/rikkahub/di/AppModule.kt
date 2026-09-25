package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.run.RunOrchestrator
import me.rerere.rikkahub.data.run.RunQuotaConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.JobWakeCoordinator
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.service.WorkspaceJobNotificationManager
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    // 注意: 这里**必须**按具体类型 AppScope 注册。其它组件(如 ChatNotificationManager、
    // UpdateChecker)注入的是 AppScope 本身; 若改成 single<CoroutineScope>, 它们就解析不到了。
    // 因此需要协程作用域的组件请直接依赖 AppScope, 不要依赖 CoroutineScope 抽象。
    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 执行编排层: shell 后台任务与子代理共用（配额分账 / 看门狗 / 取消收尾）
    single {
        RunOrchestrator(
            scope = get(),
            quotaReader = {
                val s = get<SettingsStore>().settingsFlowRaw.first()
                RunQuotaConfig(
                    maxConcurrentJobs = s.maxConcurrentJobs,
                    maxConcurrentJobsPerWorkspace = s.maxConcurrentJobsPerWorkspace,
                    maxConcurrentAgents = s.maxConcurrentAgents,
                    maxConcurrentAgentsPerWorkspace = s.maxConcurrentAgentsPerWorkspace,
                )
            },
        )
    }

    // ---- 子代理（与 shell 后台任务平级的另一种执行体）----
    single {
        me.rerere.rikkahub.data.agent.AgentTranscriptStore(
            baseDir = java.io.File(get<android.content.Context>().filesDir, "agent-runs"),
        )
    }

    single {
        me.rerere.rikkahub.data.agent.AgentRunner(
            generationLoop = get(),
            transcripts = get(),
        )
    }

    single {
        me.rerere.rikkahub.data.agent.AgentToolFactory(
            chatToolFactory = get(),
        )
    }

    // 注册进编排层: 至此 JOB 与 AGENT 两种执行体都挂在同一套配额/看门狗/取消收尾之下。
    // 用延迟工厂注册 —— 不依赖"谁先被构造", 也不怕没人注入（懒加载的单例不会被创建）。
    single(createdAtStart = true) {
        get<RunOrchestrator>().register(
            kind = me.rerere.rikkahub.data.run.RunKind.AGENT,
            executor = {
                me.rerere.rikkahub.data.agent.AgentRunExecutor(
                    scope = get(),
                    dao = get(),
                    runner = get(),
                    toolFactory = get(),
                    settingsStore = get(),
                    workspaceRepository = get(),
                    transcripts = get(),
                )
            },
            registry = { me.rerere.rikkahub.data.agent.AgentRunRegistry(get()) },
        )
    }

    // 后台任务: job_* 工具、UI、调度器共用的唯一入口
    single {
        WorkspaceJobManager(
            context = get(),
            appScope = get(),
            dao = get(),
            workspaceDao = get(),
            workspaceManager = get(),
            settingsStore = get(),
            eventBus = get(),
            orchestrator = get(),
        )
    }

    single {
        JobScheduleEngine(
            context = get(),
            appScope = get(),
            dao = get(),
            manager = get(),
            eventBus = get(),
        )
    }

    // 完成/延后通知（createdAtStart: 进程启动即订阅, 否则后台完成的事件会丢）
    single(createdAtStart = true) {
        WorkspaceJobNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            dao = get(),
        )
    }

    // 完成自动唤醒 AI 续聊
    single(createdAtStart = true) {
        JobWakeCoordinator(
            appScope = get(),
            eventBus = get(),
            dao = get(),
        )
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            jobManager = get(),
            scheduleEngine = get(),
            jobDao = get(),
            agentRunDao = get(),
            agentTranscripts = get(),
            runOrchestrator = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
