package me.rerere.rikkahub.data.job

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

private const val TAG = "ShellProcessControl"

/** PRoot 启动器的可执行文件名: 出现在 cmdline 里即可认出这是本应用拉起的沙箱进程。 */
internal const val PROOT_LAUNCHER_MARKER = "libproot_exec"

/**
 * PRoot 子进程的定位与结束。
 *
 * 这些是**纯 shell 执行体**的知识: 编排层（RunOrchestrator）不认识 pid、信号与 /proc,
 * 它只知道 RunHandle.terminate()。子代理执行体也不需要这些 —— 它停的是模型循环。
 *
 * 之所以单独成类: 这段逻辑踩过三个 Android 坑（详见各方法注释）, 值得被隔离并单独讲清楚。
 */
internal class ShellProcessControl(
    private val context: Context,
) {
    /**
     * 按落库的 pid 结束进程树, 返回是否真的杀掉了什么。
     *
     * 只杀直接子进程不够: 真正的活儿在 PRoot 子树里（proot -> bash -> python3）。PRoot 以
     * --kill-on-exit 启动, 正常路径下由它负责清子树; 但 App 被系统杀掉时它没机会执行,
     * 子树会变成孤儿继续跑。这里先收子孙, 再收自己。
     */
    fun killByPid(pid: Long?): Boolean {
        if (pid == null || pid <= 0L) return false
        val target = pid.toInt()
        var killedAny = false
        descendantsOf(target).forEach { child ->
            if (!isOwnProcess(child)) return@forEach
            Process.sendSignal(child, Process.SIGNAL_KILL)
            killedAny = true
        }
        // pid 可能已被系统复用给别的进程, 所以只杀确实属于本应用的进程。
        if (isOwnProcess(target)) {
            Process.sendSignal(target, Process.SIGNAL_KILL)
            killedAny = true
        }
        return killedAny
    }

    /**
     * 收掉由本进程直接启动、却已经没有对应任务的 PRoot 启动器。
     *
     * 老版本没有把 pid 落库, 只靠 pid 收不掉这些遗留进程; 但 PRoot 启动器一定是 App 主进程的
     * 直接子进程, 所以按父 pid 就能安全识别(不会误伤其它应用的进程)。它带着 --kill-on-exit,
     * 收掉这一层就会连带清掉自己的 bash/python 子树。
     */
    fun reapOrphanLaunchers(): Int {
        val myPid = Process.myPid()
        var reaped = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            if (!cmdline.contains(PROOT_LAUNCHER_MARKER)) return@forEach
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trimStart().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid != myPid) return@forEach
            if (!isOwnProcess(pid)) return@forEach
            descendantsOf(pid).forEach { child ->
                if (isOwnProcess(child)) Process.sendSignal(child, Process.SIGNAL_KILL)
            }
            Process.sendSignal(pid, Process.SIGNAL_KILL)
            Log.w(TAG, "reaped orphan proot launcher pid=" + pid)
            reaped++
        }
        return reaped
    }

    /**
     * 解析并重试: PRoot 子进程可能在 startJob 返回后才被 fork 出来, 扫一次常常扫不到,
     * 所以隔一点时间多扫几次, 尽量把 pid 落到库里(它是 App 重启后唯一还能停掉任务的线索)。
     */
    fun resolveLauncherPidWithRetry(fromProcess: Long): Long {
        if (fromProcess > 0L) return fromProcess
        repeat(5) { attempt ->
            val found = findLauncherChildPid()
            if (found != null) return found.toLong()
            runCatching { Thread.sleep(60L * (attempt + 1)) }
        }
        return -1L
    }

    /**
     * 取 PRoot 启动器 pid。
     *
     * android 的 java.lang.Process.pid() 是隐藏 API, 在 Android 16 上反射常拿到 -1, 所以
     * 拿不到时退回扫描 /proc: 由本进程直接拉起的 libproot_exec 子进程就是它。
     */
    fun resolveLauncherPid(fromProcess: Long): Long {
        if (fromProcess > 0L) return fromProcess
        return findLauncherChildPid()?.toLong() ?: -1L
    }

    /** 扫描 /proc, 找出父进程是自己、且 cmdline 是本应用 PRoot 启动器的那个子进程。 */
    fun findLauncherChildPid(): Int? {
        val myPid = Process.myPid()
        var scanned = 0
        var matchedMarker = 0
        var matchedPpid = 0
        var ownedOk = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            scanned++
            if (!cmdline.contains(PROOT_LAUNCHER_MARKER)) return@forEach
            matchedMarker++
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trimStart().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid == myPid) {
                matchedPpid++
                if (isOwnProcess(pid)) {
                    ownedOk++
                    diag("findLauncherChildPid: FOUND pid=" + pid + " scanned=" + scanned)
                    return pid
                }
            }
        }
        diag(
            "findLauncherChildPid: NOT FOUND myPid=" + myPid + " scanned=" + scanned +
                " marker=" + matchedMarker + " ppidMatch=" + matchedPpid + " ownOk=" + ownedOk
        )
        return null
    }

    /**
     * 该 pid 是否属于本应用, 防止 pid 被系统复用后误杀别人的进程。
     *
     * 从 /proc/<pid>/status 的 Uid: 行读取 —— 注意不能用
     * java.nio.file.Files.getAttribute("unix:uid"), Android 的 NIO 实现不支持该属性,
     * 会静默返回 null, 导致所有结束操作都被跳过(点"停止"没反应的元凶之一)。
     */
    fun isOwnProcess(pid: Int): Boolean {
        val myUid = Process.myUid()
        val status = runCatching { File("/proc/" + pid + "/status").readText() }.getOrNull()
        if (status == null) {
            // 读不到就放行: 调用点已确认它是"我们自己的 PRoot 启动器的子进程",
            // 这个归属关系比 uid 更强; 反过来一旦读失败就全部拒绝, 会连真正的目标都停不掉。
            diag("isOwnProcess pid=" + pid + " status UNREADABLE, allowing")
            return true
        }
        val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
        val uid = uidLine?.removePrefix("Uid:")?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
        if (uid == null) {
            diag("isOwnProcess pid=" + pid + " no Uid line, allowing")
            return true
        }
        if (uid != myUid) {
            diag("isOwnProcess pid=" + pid + " uid=" + uid + " myUid=" + myUid + " -> reject")
        }
        return uid == myUid
    }

    /** 遍历 /proc 找出 [pid] 的所有后代进程, 深度优先(先子后父的顺序返回)。 */
    fun descendantsOf(pid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { entry ->
            val childPid = entry.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            // 格式: pid (comm) state ppid ...  comm 可能含空格与括号, 取最后一个 ')' 之后解析。
            val afterComm = stat.substringAfterLast(')').trimStart()
            val ppid = afterComm.split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() }.add(childPid)
        }
        val result = mutableListOf<Int>()
        fun walk(current: Int) {
            children[current]?.forEach { child ->
                walk(child)
                result.add(child)
            }
        }
        walk(pid)
        return result
    }

    /** 诊断: 落盘记录 pid 解析过程(MIUI 上 logcat 常吞掉应用日志, 只能落盘排查)。 */
    fun diag(msg: String) {
        runCatching {
            val f = File(context.filesDir, "job-pid-debug.log")
            if (f.length() > 256 * 1024) f.delete()
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }
}
