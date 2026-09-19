package me.rerere.rikkahub.data.job

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "JobWakeCoordinator"

/** 多个任务在短时间内完成时合并成一条唤醒消息, 避免刷屏与重复烧 token。 */
private const val WAKE_COALESCE_MS = 3_000L

/**
 * 完成自动唤醒 AI 续聊。
 *
 * 只处理显式开启 auto_wake 的任务; 消息通过 [ChatService.sendMessage] 入队,
 * 因此会话正在生成时会自动排队, 空闲时立即开始新一轮生成。
 *
 * ChatService 用 Koin 的延迟注入获取, 避免 App 启动时就把整个聊天栈拉起来。
 */
class JobWakeCoordinator(
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
    private val dao: WorkspaceJobDAO,
) : KoinComponent {

    private val chatService: ChatService by inject()
    private val pending = mutableListOf<AppEvent.WorkspaceJobFinished>()

    @Volatile
    private var flushJob: Job? = null

    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                if (event !is AppEvent.WorkspaceJobFinished) return@collect
                if (!event.autoWake || event.conversationId.isNullOrBlank()) return@collect
                synchronized(pending) { pending += event }
                scheduleFlush()
            }
        }
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = appScope.launch(Dispatchers.Default) {
            delay(WAKE_COALESCE_MS)
            val batch = synchronized(pending) {
                val copy = pending.toList()
                pending.clear()
                copy
            }
            flushJob = null
            if (batch.isNotEmpty()) {
                runCatching { wake(batch) }
                    .onFailure { Log.e(TAG, "auto wake failed", it) }
            }
        }
    }

    private suspend fun wake(batch: List<AppEvent.WorkspaceJobFinished>) {
        batch.groupBy { it.conversationId }.forEach { (conversationId, jobs) ->
            val uuid = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@forEach
            val text = buildString {
                append("[后台任务] ")
                if (jobs.size == 1) {
                    append(describe(jobs.first()))
                } else {
                    append(jobs.size).append(" 个任务已结束:\n")
                    jobs.forEach { append("- ").append(describe(it)).append('\n') }
                }
                append("\n完整输出可用 job_logs 查看。")
            }
            runCatching {
                chatService.sendMessage(
                    conversationId = uuid,
                    content = listOf(UIMessagePart.Text(text)),
                    answer = true,
                )
            }.onSuccess {
                jobs.forEach { job ->
                    runCatching { dao.updateWakeState(job.jobId, JobWakeState.WOKEN.name) }
                }
            }.onFailure {
                jobs.forEach { job ->
                    runCatching { dao.updateWakeState(job.jobId, JobWakeState.SKIPPED.name) }
                }
                throw it
            }
        }
    }

    private fun describe(event: AppEvent.WorkspaceJobFinished): String {
        val duration = event.runtimeMs?.let { ms ->
            val minutes = ms / 60_000
            if (minutes < 1) (ms / 1000).toString() + "s" else minutes.toString() + "m" + ((ms % 60_000) / 1000) + "s"
        }
        val exit = event.exitCode?.let { " exit=" + it }.orEmpty()
        val suffix = if (duration == null) "" else " · " + duration
        return "'" + event.name + "' " + event.status.lowercase() + exit + suffix
    }
}
