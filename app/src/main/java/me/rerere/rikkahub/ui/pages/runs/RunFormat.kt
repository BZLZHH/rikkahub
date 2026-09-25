package me.rerere.rikkahub.ui.pages.runs

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.run.RunStatus

/**
 * 运行相关的一小组格式化与状态原子。
 *
 * 单独放一个文件是因为"可读性"这件事主要就落在这些细节上:
 * token 数写成 `138.4k` 而不是 `138412`, 时间写成 `1m35s` 而不是 `95234`。
 * 数字本身没错, 但人要花力气去读 —— 而这一版 UI 的全部目的就是省掉那份力气。
 */

/** 138412 -> "138.4k"; 1234567 -> "1.23M"。小于 1000 时原样。 */
fun formatTokens(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> oneDecimal(count / 1_000.0) + "k"
    else -> twoDecimals(count / 1_000_000.0) + "M"
}

/** 毫秒 -> "31s" / "1m35s" / "2h05m"。**不显示毫秒** —— 没人读得进去。 */
fun formatDuration(millis: Long): String {
    if (millis < 0) return "-"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> hours.toString() + "h" + minutes.toString().padStart(2, '0') + "m"
        minutes > 0 -> minutes.toString() + "m" + seconds.toString().padStart(2, '0') + "s"
        else -> seconds.toString() + "s"
    }
}

/**
 * 一个会走的"现在"。
 *
 * 运行中的耗时是 `now - startedAt` 算出来的 —— 如果只在组合时算一次, 它就**永远不会动**,
 * 界面上看就是"卡住了/不更新"。所以这里每秒推一次状态, 让依赖它的文本跟着走。
 *
 * 只在真的有东西在跑时才走: 全部结束之后没必要每秒重组一次列表。
 */
@Composable
fun rememberTickingNow(active: Boolean, periodMs: Long = 1000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

/** "3 分钟前" 这种人话; 刚发生的说"刚刚"。 */
fun formatAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - timestamp).coerceAtLeast(0)
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> (delta / 60_000).toString() + " 分钟前"
        delta < 86_400_000 -> (delta / 3_600_000).toString() + " 小时前"
        else -> (delta / 86_400_000).toString() + " 天前"
    }
}

/**
 * 状态 -> 颜色。
 *
 * 用同一套配色贯穿三个页面（总览 / 详情 / 子会话）, 这样用户学会一次就够:
 * 蓝色=在跑, 绿色=成功, 红色=失败, 灰色=被停/超时/中断。
 * 复用命中的行单独用 tertiary —— 它**没有花时间**, 和"跑成功"不是一回事, 值得区分。
 */
@Composable
fun statusColor(status: String, reused: Boolean = false): Color {
    if (reused) return MaterialTheme.colorScheme.tertiary
    return when (RunStatus.from(status)) {
        RunStatus.RUNNING, RunStatus.PENDING -> MaterialTheme.colorScheme.primary
        RunStatus.SUCCEEDED -> MaterialTheme.colorScheme.tertiary
        RunStatus.FAILED -> MaterialTheme.colorScheme.error
        RunStatus.KILLED, RunStatus.TIMED_OUT, RunStatus.INTERRUPTED, RunStatus.DEFERRED ->
            MaterialTheme.colorScheme.outline
    }
}

/** 状态 -> 中文短词。短是刻意的: 它出现在每一行上, 长了就会挤掉真正重要的东西。 */
fun statusLabel(status: String, reused: Boolean = false): String {
    if (reused) return "复用"
    return when (RunStatus.from(status)) {
        RunStatus.PENDING -> "排队"
        RunStatus.RUNNING -> "运行中"
        RunStatus.SUCCEEDED -> "完成"
        RunStatus.FAILED -> "失败"
        RunStatus.KILLED -> "已停止"
        RunStatus.TIMED_OUT -> "超时"
        RunStatus.INTERRUPTED -> "已中断"
        RunStatus.DEFERRED -> "已延后"
    }
}

/** 一个会呼吸的状态点: 在跑的会缓慢明暗, 停下来的就是一颗静点。 */
@Composable
fun StatusDot(
    status: String,
    modifier: Modifier = Modifier,
    reused: Boolean = false,
    size: Int = 10,
) {
    val color = statusColor(status, reused)
    val running = !reused && RunStatus.from(status) == RunStatus.RUNNING
    val alpha = if (running) {
        val transition = rememberInfiniteTransition(label = "dot")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        animated
    } else {
        1f
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

private fun oneDecimal(value: Double): String {
    val scaled = Math.round(value * 10)
    return (scaled / 10).toString() + "." + (scaled % 10).toString()
}

private fun twoDecimals(value: Double): String {
    val scaled = Math.round(value * 100)
    val whole = scaled / 100
    val frac = (scaled % 100).toString().padStart(2, '0')
    return whole.toString() + "." + frac
}
