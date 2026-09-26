package me.rerere.rikkahub.ui.pages.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 向导里所有动画的统一时长（用户指定 200ms）。 */
internal const val ONBOARD_ANIM_MS = 200

/**
 * 向导的视觉外壳: 渐变底 + 顶部步骤条 + 可滚动内容 + 底部操作区。
 *
 * 每一步只关心"内容是什么", 外壳负责让六步看起来是同一个东西。
 */
@Composable
fun OnboardingScaffold(
    step: Int,
    totalSteps: Int,
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f),
                    )
                )
            ),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            StepBar(step = step, total = totalSteps)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            BottomBar(
                onBack = onBack,
                onSkip = onSkip,
                primaryLabel = primaryLabel,
                primaryEnabled = primaryEnabled,
                onPrimary = onPrimary,
            )
        }
    }
}

/** 顶部步骤条: 当前那一段更宽, 走过的段上色。两处都是动画的。 */
@Composable
private fun StepBar(step: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { index ->
            val weight by animateFloatAsState(
                targetValue = if (index == step) 1.8f else 1f,
                animationSpec = tween(ONBOARD_ANIM_MS),
                label = "stepWeight",
            )
            val color by animateColorAsState(
                targetValue = if (index <= step) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                },
                animationSpec = tween(ONBOARD_ANIM_MS),
                label = "stepColor",
            )
            Box(
                Modifier
                    .weight(weight)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun BottomBar(
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("上一步") }
        }
        Box(Modifier.weight(1f))
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("跳过") }
        }
        Button(onClick = onPrimary, enabled = primaryEnabled) {
            // 文案会变（下一步 / 安装中… / 开始使用）, 直接换字会"跳"一下
            AnimatedContent(
                targetState = primaryLabel,
                transitionSpec = {
                    fadeIn(tween(ONBOARD_ANIM_MS)) togetherWith fadeOut(tween(ONBOARD_ANIM_MS))
                },
                label = "primaryLabel",
            ) { label ->
                Text(label)
            }
        }
    }
}

@Composable
fun StepTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 可选卡片: 选中时边框与底色都是渐变的, 按下时轻微缩小。 */
@Composable
fun SelectCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "cardScale",
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "cardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0f)
        },
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "cardBorder",
    )
    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = background,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "chipBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "chipContent",
    )
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = background,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
fun Bullet(text: String, marker: String = "·") {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
