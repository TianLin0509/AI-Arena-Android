package com.tianlin.aiarena

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource

/** 基础卡片。阴影 / 描边 / 圆角全部跟随当前皮肤。 */
@Composable
fun ArenaCard(
    modifier: Modifier = Modifier,
    color: Color = ArenaStyle.colors.surface,
    borderColor: Color = ArenaStyle.colors.border,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val metrics = ArenaStyle.metrics
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        color = color,
        shape = RoundedCornerShape(metrics.cardCorner),
        border = BorderStroke(metrics.borderWidth, borderColor),
        shadowElevation = metrics.cardElevation,
        content = content,
    )
}

/** 主行动按钮。高度、圆角随皮肤变化，长辈皮肤会明显更大。 */
@Composable
fun ArenaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = ArenaStyle.colors.accent,
    contentColor: Color = ArenaStyle.colors.onAccent,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = metrics.primaryButtonHeight),
        shape = RoundedCornerShape(metrics.controlCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = colors.surfaceAlt,
            disabledContentColor = colors.muted,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
    }
}

@Composable
fun ArenaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = metrics.minTouch),
        shape = RoundedCornerShape(metrics.controlCorner),
        border = BorderStroke(metrics.borderWidth, if (enabled) colors.accent else colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.accent,
            disabledContentColor = colors.muted,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 文字型操作。始终满足最小触摸目标，长辈皮肤自动变大。 */
@Composable
fun ArenaTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = ArenaStyle.colors.accent,
    contentDescriptionText: String? = null,
) {
    val metrics = ArenaStyle.metrics
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = metrics.minTouch)
            .then(
                if (contentDescriptionText != null) {
                    Modifier.semantics { contentDescription = contentDescriptionText }
                } else {
                    Modifier
                }
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (enabled) color else ArenaStyle.colors.muted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 状态胶囊：小圆点 + 文案。dot=false 时只显示文案。 */
@Composable
fun ArenaPill(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
    pulsing: Boolean = false,
) {
    val metrics = ArenaStyle.metrics
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pill-pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 760, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pill-pulse-alpha",
        )
        value
    } else {
        1f
    }
    Surface(
        modifier = modifier,
        color = background,
        shape = RoundedCornerShape(metrics.chipCorner),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(foreground.copy(alpha = alpha)),
                )
            }
            Text(
                text = text,
                color = foreground,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** 顶部主视觉。渐变皮肤走 heroBrush，长辈皮肤走纯色以保证对比度。 */
@Composable
fun ArenaHero(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val background = if (metrics.heroGradient) {
        Modifier.background(colors.heroBrush)
    } else {
        Modifier.background(colors.heroStart)
    }
    Box(modifier = modifier.then(background)) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * 主标题。皮肤给了渐变色就用渐变填字（「净白」的签名元素），
 * 否则退回纯色，其余皮肤观感不变。
 */
@Composable
fun ArenaHeading(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = ArenaStyle.colors.onHero,
    maxLines: Int = Int.MAX_VALUE,
) {
    val gradient = ArenaStyle.colors.headingGradient
    if (gradient != null && gradient.size >= 2) {
        Text(
            text = text,
            modifier = modifier,
            style = style.merge(TextStyle(brush = Brush.linearGradient(gradient))),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 品牌头像。有矢量图标用图标，没有的用品牌色 + 单字。 */
@Composable
fun BrandAvatar(
    service: ArenaService,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val iconRes = service.iconRes
    if (iconRes != null) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = service.displayName,
            modifier = modifier.size(size),
            tint = Color.Unspecified,
        )
    } else {
        Surface(
            modifier = modifier
                .size(size)
                .semantics { contentDescription = service.displayName },
            color = Color(service.brandColor),
            shape = RoundedCornerShape(size / 3),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = service.brandGlyph.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 一组重叠的成员头像，用于紧凑展示"谁在圆桌上"。 */
@Composable
fun BrandAvatarStack(
    services: List<ArenaService>,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        services.take(ArenaService.MAX_MEMBERS).forEach { service ->
            BrandAvatar(service = service, size = size)
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = ArenaStyle.colors.ink,
            style = MaterialTheme.typography.titleMedium,
        )
        trailing?.invoke()
    }
}

/** 细进度条。progress 为 null 时显示不确定态的循环动画。 */
@Composable
fun ArenaProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = ArenaStyle.colors.surfaceAlt,
    indicator: Color = ArenaStyle.colors.accent,
) {
    val shape = RoundedCornerShape(99.dp)
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier
                .height(height)
                .clip(shape),
            color = indicator,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 320),
            label = "progress-value",
        )
        Box(
            modifier = modifier
                .height(height)
                .clip(shape)
                .background(track),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxSize()
                    .clip(shape)
                    .background(indicator),
            )
        }
    }
}

/** 皮肤选择器：每个皮肤画一张 mini 预览，所见即所得。 */
@Composable
fun SkinPicker(
    selected: ArenaSkin,
    onSelect: (ArenaSkin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArenaSkin.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { skin ->
                    val isSelected = skin == selected
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected) colors.accent else colors.border,
                        label = "skin-border",
                    )
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(skin) }
                            .semantics {
                                contentDescription =
                                    "${skin.displayName}风格，${if (isSelected) "已选择" else "未选择"}"
                            },
                        color = colors.surface,
                        shape = RoundedCornerShape(metrics.cardCorner),
                        border = BorderStroke(if (isSelected) 2.dp else metrics.borderWidth, borderColor),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SkinSwatch(skin)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = skin.displayName,
                                    color = colors.ink,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (isSelected) {
                                    ArenaPill(
                                        text = "使用中",
                                        foreground = colors.accent,
                                        background = colors.accentSoft,
                                        dot = false,
                                    )
                                }
                            }
                            Text(
                                text = skin.tagline,
                                color = colors.muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 皮肤缩略图：用该皮肤自己的颜色画一个迷你圆桌界面。 */
@Composable
private fun SkinSwatch(skin: ArenaSkin) {
    val p = skin.palette
    val m = skin.metrics
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(RoundedCornerShape(m.cardCorner / 1.6f))
            .background(p.page)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(15.dp)
                .clip(RoundedCornerShape(m.cardCorner / 2.4f))
                .then(
                    if (m.heroGradient) {
                        Modifier.background(p.heroBrush)
                    } else {
                        Modifier.background(p.heroStart)
                    }
                ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 26.dp)
                    .weight(1f)
                    .height(17.dp)
                    .clip(RoundedCornerShape(m.cardCorner / 2.4f))
                    .background(p.surface),
            )
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(17.dp)
                    .clip(RoundedCornerShape(m.cardCorner / 2.4f))
                    .background(p.accentSoft),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .clip(RoundedCornerShape(m.controlCorner / 2.2f))
                .background(p.accent),
        )
    }
}
