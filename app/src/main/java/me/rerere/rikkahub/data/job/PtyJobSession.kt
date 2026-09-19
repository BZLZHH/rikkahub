package me.rerere.rikkahub.data.job

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import me.rerere.rikkahub.ui.pages.extensions.workspace.createWorkspaceTerminalSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.prepareWorkspaceTerminalSession

/**
 * 交互式（pty）后台任务。
 *
 * 复用 termux 的 [TerminalSession]: 它通过 JNI 在 PRoot 里起一个真 pty,
 * 因此 [send] 能像人一样敲键盘, [transcript] 能读到终端屏幕/回滚内容。
 *
 * 日志方案说明: termux 的 TerminalSession 不暴露"原始输出流"钩子, 输出直接进终端模拟器,
 * 所以 pty 任务的日志是**屏幕快照序列**（内容变化时追加增量, 变化不连续时追加整屏快照）,
 * 而不是 pipe 模式那种精确的字节流。
 */
class PtyJobSession internal constructor(
    private val session: TerminalSession,
    private val screenFile: java.io.File,
    private val logStore: JobLogStore,
    private val onFinished: () -> Unit,
) {
    private var lastTranscript: String = ""

    @Volatile
    var finished: Boolean = false
        private set

    val transcript: String
        get() = runCatching { session.emulator?.screen?.transcriptText }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: lastTranscript

    val exitStatus: Int
        get() = runCatching { session.exitStatus }.getOrDefault(-1)

    val pid: Int
        get() = runCatching { session.pid }.getOrDefault(-1)

    /** 由管理器按固定间隔调用: 记录屏幕变化并检测退出。 */
    fun poll() {
        val current = transcript
        if (current != lastTranscript) {
            val delta = when {
                current.startsWith(lastTranscript) -> current.substring(lastTranscript.length)
                else -> "\n--- [screen] ---\n$current"
            }
            if (delta.isNotEmpty()) logStore.appendText(screenFile, delta)
            lastTranscript = current
        }
        if (!session.isRunning && !finished) {
            finished = true
            onFinished()
        }
    }

    /** 写入输入（默认补一个换行, 相当于敲回车）。 */
    fun send(input: String, appendNewline: Boolean = true) {
        val payload = if (appendNewline) input + "\n" else input
        write(payload)
    }

    /** 发送控制键/方向键等按键。 */
    fun sendKey(key: String) {
        val payload = when (key.lowercase()) {
            "ctrl-c", "ctrlc", "int", "^c" -> "\u0003"
            "ctrl-d", "^d" -> "\u0004"
            "ctrl-z", "^z" -> "\u001a"
            "enter", "return", "cr" -> "\r"
            "tab" -> "\t"
            "esc", "escape" -> "\u001b"
            "up" -> "\u001b[A"
            "down" -> "\u001b[B"
            "right" -> "\u001b[C"
            "left" -> "\u001b[D"
            else -> key
        }
        write(payload)
    }

    private fun write(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        runCatching { session.write(bytes, 0, bytes.size) }
            .onFailure { Log.w(TAG, "pty write failed", it) }
    }

    fun kill() {
        runCatching { session.finishIfRunning() }
    }

    companion object {
        private const val TAG = "PtyJobSession"
        const val DEFAULT_COLS = 100
        const val DEFAULT_ROWS = 40

        fun create(
            context: Context,
            workspaceRoot: String,
            jobId: String,
            command: String,
            cwd: String,
            env: Map<String, String>,
            shellCompatibilityMode: Boolean,
            logStore: JobLogStore,
            cols: Int = DEFAULT_COLS,
            rows: Int = DEFAULT_ROWS,
            onFinished: () -> Unit = {},
        ): PtyJobSession {
            prepareWorkspaceTerminalSession(context, workspaceRoot)
            val session = createWorkspaceTerminalSession(
                context = context,
                root = workspaceRoot,
                client = HeadlessTerminalClient(),
                shellCompatibilityMode = shellCompatibilityMode,
                command = command,
                cwd = cwd,
                extraEnv = env,
            )
            session.initializeEmulator(cols, rows)
            val screenFile = logStore.streamFile(workspaceRoot, jobId, JobLogStream.SCREEN)
            return PtyJobSession(session, screenFile, logStore, onFinished)
        }
    }
}

/** 无界面的终端会话回调: 只保留日志能力, 屏幕刷新/剪贴板等一律忽略。 */
private class HeadlessTerminalClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit

    override fun onPasteTextFromClipboard(session: TerminalSession) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "terminal error", e)
    }
}
