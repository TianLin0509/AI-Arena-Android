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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 基础容器。底色默认取皮肤的 `card`；扁平皮肤（净白）不画描边、不投阴影，
 * 其它皮肤保留描边和阴影。传入的 borderColor 在扁平皮肤下会被忽略——
 * 需要表达状态时用填色、圆点或文字，不要依赖描边。
 */
@Composable
fun ArenaCard(
    modifier: Modifier = Modifier,
    color: Color = ArenaStyle.colors.card,
    borderColor: Color = ArenaStyle.colors.border,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val metrics = ArenaStyle.metrics
    val shape = RoundedCornerShape(metrics.cardCorner)
    Surface(
        modifier = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier,
        color = color,
        shape = shape,
        border = if (metrics.flatSurfaces) null else BorderStroke(metrics.borderWidth, borderColor),
        shadowElevation = if (metrics.flatSurfaces) 0.dp else metrics.cardElevation,
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
    leading: (@Composable () -> Unit)? = null,
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
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
    }
}

/**
 * 次要按钮。扁平皮肤用淡色填充（豆包 / 微信的"次按钮"做法），
 * 其它皮肤保留描边样式。
 */
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
    if (metrics.flatSurfaces) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = metrics.minTouch),
            shape = RoundedCornerShape(metrics.controlCorner),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentSoft,
                contentColor = colors.accent,
                disabledContainerColor = colors.card,
                disabledContentColor = colors.muted,
            ),
            elevation = null,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    } else {
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

/** 矢量图标的统一入口：资源 + 着色 + 尺寸。 */
@Composable
fun ArenaIcon(
    resId: Int,
    modifier: Modifier = Modifier,
    tint: Color = ArenaStyle.colors.ink,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** 返回按钮：箭头 + 文字，触摸区足够大，读屏描述固定为「返回上一页」。 */
@Composable
fun ArenaBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "返回",
    color: Color = ArenaStyle.colors.accent,
    contentDescriptionText: String = "返回上一页",
) {
    val metrics = ArenaStyle.metrics
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = metrics.minTouch)
            .semantics { contentDescription = contentDescriptionText },
        contentPadding = PaddingValues(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    ) {
        ArenaIcon(R.drawable.ic_arrow_back, tint = color, size = 24.dp)
        Spacer(Modifier.width(2.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 分组列表容器（iOS 设置 / 微信「我」页的样式）：可选的小标题 + 一个圆角容器，
 * 行与行之间用 [ArenaRowDivider] 分隔。
 */
@Composable
fun ArenaGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    color: Color = ArenaStyle.colors.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ArenaStyle.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        ArenaCard(modifier = Modifier.fillMaxWidth(), color = color) {
            Column(content = content)
        }
    }
}

/** 分组列表中行与行之间的发丝线，左侧缩进与文字对齐。 */
@Composable
fun ArenaRowDivider(startIndent: Dp = 16.dp) {
    HorizontalDivider(
        color = ArenaStyle.colors.border,
        modifier = Modifier.padding(start = startIndent),
    )
}

/**
 * 分组列表的一行：标题 + 说明 + 右侧内容（默认是右箭头）。
 * 行高不低于皮肤的 rowHeight，整行可点。
 */
@Composable
fun ArenaRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColor: Color = ArenaStyle.colors.muted,
    titleColor: Color = ArenaStyle.colors.ink,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingColor: Color = ArenaStyle.colors.muted,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentDescriptionText: String? = null,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescriptionText != null) {
                    Modifier.semantics { contentDescription = contentDescriptionText }
                } else {
                    Modifier
                }
            )
            .heightIn(min = metrics.rowHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = detailColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                color = trailingColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing()
        if (chevron && onClick != null) {
            ArenaIcon(R.drawable.ic_chevron_right, tint = colors.muted.copy(alpha = 0.7f), size = 22.dp)
        }
    }
}

/** 带开关的行。开关的读屏描述由调用方给出，与旧版「开启/关闭大字模式」保持一致。 */
@Composable
fun ArenaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    leading: (@Composable () -> Unit)? = null,
    contentDescriptionText: String? = null,
) {
    val colors = ArenaStyle.colors
    ArenaRow(
        title = title,
        detail = detail,
        leading = leading,
        modifier = modifier,
        chevron = false,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = if (contentDescriptionText != null) {
                    Modifier.semantics { contentDescription = contentDescriptionText }
                } else {
                    Modifier
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.surfaceAlt,
                    uncheckedBorderColor = colors.borderStrong,
                ),
            )
        },
    )
}

/** 提示条的语气，决定配色和图标。 */
enum class NoticeTone { INFO, SUCCESS, WARNING, ERROR }

/**
 * 提示条：一句话说明 + 可选的一个动作。用于断网、登录成功、上次异常退出等
 * "需要用户知道、但不该打断他"的场合。永远不是弹窗。
 */
@Composable
fun ArenaNotice(
    text: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    actionContentDescription: String? = null,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val (foreground, background, icon) = when (tone) {
        NoticeTone.INFO -> Triple(colors.accent, colors.accentSoft, R.drawable.ic_info)
        NoticeTone.SUCCESS -> Triple(colors.success, colors.successSoft, R.drawable.ic_check_circle)
        NoticeTone.WARNING -> Triple(colors.warning, colors.warningSoft, R.drawable.ic_warning)
        NoticeTone.ERROR -> Triple(colors.error, colors.errorSoft, R.drawable.ic_error)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(metrics.cardCorner),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ArenaIcon(icon, tint = foreground, size = 22.dp, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (title != null) {
                        Text(
                            text = title,
                            color = foreground,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        text = text,
                        color = if (title != null) colors.ink else foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        ArenaTextAction(
                            text = secondaryLabel,
                            onClick = onSecondary,
                            color = colors.muted,
                        )
                    }
                    ArenaTextAction(
                        text = actionLabel,
                        onClick = onAction,
                        color = foreground,
                        contentDescriptionText = actionContentDescription,
                    )
                }
            }
        }
    }
}

/** 步骤序号：圆圈里的数字；完成后换成对勾。用在登录引导和首次使用说明里。 */
@Composable
fun ArenaStepBadge(
    number: Int,
    done: Boolean = false,
    active: Boolean = false,
    size: Dp = 30.dp,
) {
    val colors = ArenaStyle.colors
    val background = when {
        done -> colors.success
        active -> colors.accent
        else -> colors.surfaceAlt
    }
    val foreground = when {
        done || active -> colors.onAccent
        else -> colors.muted
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(99.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            ArenaIcon(R.drawable.ic_check, tint = foreground, size = size * 0.6f)
        } else {
            Text(
                text = number.toString(),
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 分段选择器：两三个互斥选项，当前项用白色浮起。 */
@Composable
fun ArenaSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    captions: List<String>? = null,
    contentDescriptions: List<String>? = null,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceAlt,
        shape = RoundedCornerShape(metrics.controlCorner),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                val background by animateColorAsState(
                    targetValue = if (isSelected) colors.surface else Color.Transparent,
                    label = "segment-bg",
                )
                val description = contentDescriptions?.getOrNull(index)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = metrics.minTouch)
                        .then(
                            if (description != null) {
                                Modifier.semantics { contentDescription = description }
                            } else {
                                Modifier
                            }
                        )
                        .clip(RoundedCornerShape(metrics.controlCorner - 4.dp))
                        .clickable(enabled = enabled) { onSelect(index) },
                    color = background,
                    shape = RoundedCornerShape(metrics.controlCorner - 4.dp),
                    shadowElevation = if (isSelected) 1.dp else 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) colors.accent else colors.muted,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val caption = captions?.getOrNull(index)
                        if (caption != null) {
                            Text(
                                text = caption,
                                color = if (isSelected) colors.accent else colors.muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
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
                        targetValue = if (isSelected) colors.accent else colors.card,
                        label = "skin-border",
                    )
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(metrics.cardCorner))
                            .clickable { onSelect(skin) }
                            .semantics {
                                contentDescription =
                                    "${skin.displayName}风格，${if (isSelected) "已选择" else "未选择"}"
                            },
                        color = colors.card,
                        shape = RoundedCornerShape(metrics.cardCorner),
                        border = BorderStroke(2.dp, if (isSelected) borderColor else if (metrics.flatSurfaces) colors.card else colors.border),
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
            .then(if (m.flatSurfaces) Modifier.background(p.page) else Modifier)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(15.dp)
                .clip(RoundedCornerShape(m.cardCorner / 2.4f))
                .then(
                    when {
                        m.heroGradient -> Modifier.background(p.heroBrush)
                        p.headingGradient != null -> Modifier.background(Brush.linearGradient(p.headingGradient))
                        else -> Modifier.background(p.heroStart)
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
                    .background(p.card)
                    .then(if (m.flatSurfaces) Modifier else Modifier.background(p.surface)),
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
