package com.tianlin.aiarena

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat

/**
 * 皮肤（视觉风格）。每套皮肤同时决定配色、圆角、描边、阴影模型和字号基准，
 * 因此不同皮肤之间是"换了一套设计"，而不只是换了主色。
 */
enum class ArenaSkin(
    val displayName: String,
    val tagline: String,
) {
    PURE("净白", "白底留白，渐变标题"),
    CLEAR("清朗", "明亮通透，蓝绿顶栏"),
    INK("墨韵", "宣纸朱砂，中式沉稳"),
    NIGHT("夜航", "深色护眼，适合夜间"),
    ELDER("长辈", "大字粗描边，高对比"),
    SUNRISE("暖阳", "暖橙圆润，亲和轻快"),
    ;

    companion object {
        val default = PURE

        /** 反序列化持久化的皮肤名；无法识别时回退默认值，不抛异常。 */
        fun fromName(value: String?): ArenaSkin =
            entries.firstOrNull { it.name == value } ?: default
    }
}

@Immutable
data class ArenaPalette(
    val isDark: Boolean,
    val page: Color,
    val surface: Color,
    /**
     * 放在页面上的"容器"底色：输入框、示例、分组列表、回答卡片都用它。
     * 「净白」是浅灰色块压在白底上（豆包 / Kimi 的做法），其它皮肤是白卡片压在有色页面上。
     */
    val card: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val border: Color,
    val borderStrong: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val debate: Color,
    val debateSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val summarySurface: Color,
    val summaryBorder: Color,
    val navSurface: Color,
    /** 主标题渐变色；null 表示标题用纯色 ink。只有「净白」用到。 */
    val headingGradient: List<Color>? = null,
) {
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
}

@Immutable
data class ArenaMetrics(
    val cardCorner: Dp,
    val controlCorner: Dp,
    val chipCorner: Dp,
    val borderWidth: Dp,
    val cardElevation: Dp,
    val heroElevation: Dp,
    val gutter: Dp,
    val gap: Dp,
    val minTouch: Dp,
    /** 分组列表的行高下限（微信「我」页约 56dp，长辈皮肤更高）。 */
    val rowHeight: Dp,
    val primaryButtonHeight: Dp,
    val typeScale: Float,
    val heroGradient: Boolean,
    val headingFamily: FontFamily,
    /** 扁平化：顶栏与页面同色、容器只靠填色区分，不画描边、不投阴影。 */
    val flatSurfaces: Boolean = false,
)

val LocalArenaPalette = staticCompositionLocalOf { ArenaSkin.CLEAR.palette }
val LocalArenaMetrics = staticCompositionLocalOf { ArenaSkin.CLEAR.metrics }

/** 皮肤令牌的统一读取入口：`ArenaStyle.colors` / `ArenaStyle.metrics`。 */
object ArenaStyle {
    val colors: ArenaPalette
        @Composable @ReadOnlyComposable get() = LocalArenaPalette.current

    val metrics: ArenaMetrics
        @Composable @ReadOnlyComposable get() = LocalArenaMetrics.current
}

/**
 * 「净白」2.0。白底不变、渐变标题不变；容器从"白底 + 发丝描边"改成"浅灰填色、无描边"。
 * 灰阶按 iOS systemFill / 豆包输入框的明度取；muted 与 accent 都提到白底 5:1 以上的对比度，
 * 因为使用者里有长辈。
 */
private val PurePalette = ArenaPalette(
    isDark = false,
    page = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFF4F4F7),
    surfaceAlt = Color(0xFFE9E9EF),
    ink = Color(0xFF16171A),
    muted = Color(0xFF6B6F7A),
    accent = Color(0xFF6252F5),
    accentSoft = Color(0xFFEEEBFF),
    onAccent = Color(0xFFFFFFFF),
    border = Color(0xFFE6E6EC),
    borderStrong = Color(0xFFD6D6DE),
    success = Color(0xFF0B7A45),
    successSoft = Color(0xFFE3F5EA),
    warning = Color(0xFF9A5B18),
    warningSoft = Color(0xFFFFF3E3),
    debate = Color(0xFF7A5AF8),
    debateSoft = Color(0xFFF0EDFF),
    error = Color(0xFFB02121),
    errorSoft = Color(0xFFFDECEC),
    // 顶栏与页面同色，所以「净白」看不到任何色块顶栏
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFFFFFFF),
    onHero = Color(0xFF16171A),
    onHeroMuted = Color(0xFF6B6F7A),
    summarySurface = Color(0xFFF3F0FF),
    summaryBorder = Color(0xFFE2DCFF),
    navSurface = Color(0xFFFFFFFF),
    headingGradient = listOf(Color(0xFF6E5BFF), Color(0xFFB15CFF), Color(0xFFFF6E9C)),
)

private val ClearPalette = ArenaPalette(
    isDark = false,
    page = Color(0xFFF2F6FA),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEDF3F7),
    ink = Color(0xFF0F1E29),
    muted = Color(0xFF556B7A),
    accent = Color(0xFF0E7490),
    accentSoft = Color(0xFFDFF0F4),
    onAccent = Color(0xFFFFFFFF),
    border = Color(0xFFDCE6EC),
    borderStrong = Color(0xFFB9CBD6),
    success = Color(0xFF1C7A52),
    successSoft = Color(0xFFE3F4EC),
    warning = Color(0xFF9A5B18),
    warningSoft = Color(0xFFFFF2E2),
    debate = Color(0xFF6A4E92),
    debateSoft = Color(0xFFEFE9F7),
    error = Color(0xFFB3261E),
    errorSoft = Color(0xFFFBE9E7),
    heroStart = Color(0xFF0B6076),
    heroEnd = Color(0xFF1B93A8),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xCCFFFFFF),
    summarySurface = Color(0xFFFFFCF3),
    summaryBorder = Color(0xFFE8DDB8),
    navSurface = Color(0xFFFBFDFE),
)

private val InkPalette = ArenaPalette(
    isDark = false,
    page = Color(0xFFF4EFE4),
    surface = Color(0xFFFCFAF4),
    card = Color(0xFFFCFAF4),
    surfaceAlt = Color(0xFFEDE6D8),
    ink = Color(0xFF1B1915),
    muted = Color(0xFF6B6253),
    accent = Color(0xFF9E3B2C),
    accentSoft = Color(0xFFF6E5E0),
    onAccent = Color(0xFFFFFBF5),
    border = Color(0xFFD9CFBB),
    borderStrong = Color(0xFF9A8C72),
    success = Color(0xFF3D6B48),
    successSoft = Color(0xFFE6EEE3),
    warning = Color(0xFF8A6320),
    warningSoft = Color(0xFFF7ECD6),
    debate = Color(0xFF3F5A63),
    debateSoft = Color(0xFFE4EBEC),
    error = Color(0xFF9E3B2C),
    errorSoft = Color(0xFFF6E2DD),
    heroStart = Color(0xFF2C2A24),
    heroEnd = Color(0xFF544C3C),
    onHero = Color(0xFFF6F0E2),
    onHeroMuted = Color(0xCCF6F0E2),
    summarySurface = Color(0xFFFBF3E2),
    summaryBorder = Color(0xFFD9C7A2),
    navSurface = Color(0xFFF9F5EB),
)

private val NightPalette = ArenaPalette(
    isDark = true,
    page = Color(0xFF0D1117),
    surface = Color(0xFF161C24),
    card = Color(0xFF161C24),
    surfaceAlt = Color(0xFF1E2732),
    ink = Color(0xFFE7EEF5),
    muted = Color(0xFF97A5B5),
    accent = Color(0xFF56B0E4),
    accentSoft = Color(0xFF17303F),
    onAccent = Color(0xFF06131C),
    border = Color(0xFF2A3441),
    borderStrong = Color(0xFF3D4B5C),
    success = Color(0xFF4FC08A),
    successSoft = Color(0xFF14312A),
    warning = Color(0xFFE0A458),
    warningSoft = Color(0xFF362B1B),
    debate = Color(0xFFAE94DE),
    debateSoft = Color(0xFF2A2440),
    error = Color(0xFFEF7A72),
    errorSoft = Color(0xFF3A1F1E),
    heroStart = Color(0xFF13293D),
    heroEnd = Color(0xFF1F4A6B),
    onHero = Color(0xFFEAF3FA),
    onHeroMuted = Color(0xCCEAF3FA),
    summarySurface = Color(0xFF20261C),
    summaryBorder = Color(0xFF4A5235),
    navSurface = Color(0xFF121821),
)

private val ElderPalette = ArenaPalette(
    isDark = false,
    page = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEFF3F8),
    ink = Color(0xFF000000),
    muted = Color(0xFF35414D),
    accent = Color(0xFF0B4FA8),
    accentSoft = Color(0xFFDCE8F9),
    onAccent = Color(0xFFFFFFFF),
    border = Color(0xFF2A3440),
    borderStrong = Color(0xFF000000),
    success = Color(0xFF14653C),
    successSoft = Color(0xFFDCF0E5),
    warning = Color(0xFF8A4B00),
    warningSoft = Color(0xFFFDEBD6),
    debate = Color(0xFF553287),
    debateSoft = Color(0xFFE9DFF7),
    error = Color(0xFFA1160F),
    errorSoft = Color(0xFFFBE2E0),
    heroStart = Color(0xFF083B7E),
    heroEnd = Color(0xFF0B4FA8),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xFFE4EDFA),
    summarySurface = Color(0xFFFFFBEE),
    summaryBorder = Color(0xFF8A6A1C),
    navSurface = Color(0xFFFFFFFF),
)

private val SunrisePalette = ArenaPalette(
    isDark = false,
    page = Color(0xFFFFF7F1),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFDEDE2),
    ink = Color(0xFF2C1D14),
    muted = Color(0xFF7C6455),
    accent = Color(0xFFC2410C),
    accentSoft = Color(0xFFFDE7D8),
    onAccent = Color(0xFFFFFFFF),
    border = Color(0xFFF0DDD0),
    borderStrong = Color(0xFFD8B79E),
    success = Color(0xFF2F7A4F),
    successSoft = Color(0xFFE3F3E9),
    warning = Color(0xFF9A5B18),
    warningSoft = Color(0xFFFDF0DC),
    debate = Color(0xFF8A4FA3),
    debateSoft = Color(0xFFF6E8F8),
    error = Color(0xFFB3261E),
    errorSoft = Color(0xFFFCE7E4),
    heroStart = Color(0xFFEA6A15),
    heroEnd = Color(0xFFF7A44A),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xE6FFFFFF),
    summarySurface = Color(0xFFFFF9EC),
    summaryBorder = Color(0xFFEBD3A6),
    navSurface = Color(0xFFFFFCF9),
)

val ArenaSkin.palette: ArenaPalette
    get() = when (this) {
        ArenaSkin.PURE -> PurePalette
        ArenaSkin.CLEAR -> ClearPalette
        ArenaSkin.INK -> InkPalette
        ArenaSkin.NIGHT -> NightPalette
        ArenaSkin.ELDER -> ElderPalette
        ArenaSkin.SUNRISE -> SunrisePalette
    }

val ArenaSkin.metrics: ArenaMetrics
    get() = when (this) {
        ArenaSkin.PURE -> ArenaMetrics(
            cardCorner = 20.dp,
            controlCorner = 18.dp,
            chipCorner = 999.dp,
            borderWidth = 1.dp,
            cardElevation = 0.dp,
            heroElevation = 0.dp,
            gutter = 20.dp,
            gap = 12.dp,
            minTouch = 48.dp,
            rowHeight = 58.dp,
            primaryButtonHeight = 56.dp,
            typeScale = 1.0f,
            heroGradient = false,
            headingFamily = FontFamily.SansSerif,
            flatSurfaces = true,
        )
        ArenaSkin.CLEAR -> ArenaMetrics(
            cardCorner = 18.dp,
            controlCorner = 16.dp,
            chipCorner = 999.dp,
            borderWidth = 1.dp,
            cardElevation = 1.dp,
            heroElevation = 0.dp,
            gutter = 16.dp,
            gap = 12.dp,
            minTouch = 48.dp,
            rowHeight = 56.dp,
            primaryButtonHeight = 56.dp,
            typeScale = 1.0f,
            heroGradient = true,
            headingFamily = FontFamily.SansSerif,
        )
        ArenaSkin.INK -> ArenaMetrics(
            cardCorner = 6.dp,
            controlCorner = 6.dp,
            chipCorner = 4.dp,
            borderWidth = 1.dp,
            cardElevation = 0.dp,
            heroElevation = 0.dp,
            gutter = 18.dp,
            gap = 12.dp,
            minTouch = 48.dp,
            rowHeight = 56.dp,
            primaryButtonHeight = 56.dp,
            typeScale = 1.02f,
            heroGradient = true,
            headingFamily = FontFamily.Serif,
        )
        ArenaSkin.NIGHT -> ArenaMetrics(
            cardCorner = 18.dp,
            controlCorner = 16.dp,
            chipCorner = 999.dp,
            borderWidth = 1.dp,
            cardElevation = 0.dp,
            heroElevation = 0.dp,
            gutter = 16.dp,
            gap = 12.dp,
            minTouch = 48.dp,
            rowHeight = 56.dp,
            primaryButtonHeight = 56.dp,
            typeScale = 1.0f,
            heroGradient = true,
            headingFamily = FontFamily.SansSerif,
        )
        ArenaSkin.ELDER -> ArenaMetrics(
            cardCorner = 14.dp,
            controlCorner = 12.dp,
            chipCorner = 10.dp,
            borderWidth = 2.dp,
            cardElevation = 0.dp,
            heroElevation = 0.dp,
            gutter = 16.dp,
            gap = 14.dp,
            minTouch = 60.dp,
            rowHeight = 68.dp,
            primaryButtonHeight = 68.dp,
            typeScale = 1.18f,
            heroGradient = false,
            headingFamily = FontFamily.SansSerif,
        )
        ArenaSkin.SUNRISE -> ArenaMetrics(
            cardCorner = 22.dp,
            controlCorner = 20.dp,
            chipCorner = 999.dp,
            borderWidth = 1.dp,
            cardElevation = 2.dp,
            heroElevation = 0.dp,
            gutter = 16.dp,
            gap = 12.dp,
            minTouch = 50.dp,
            rowHeight = 58.dp,
            primaryButtonHeight = 58.dp,
            typeScale = 1.04f,
            heroGradient = true,
            headingFamily = FontFamily.SansSerif,
        )
    }

/**
 * 字号基准整体比 0.6 抬了一档：正文 17sp（iOS 正文的尺寸，也是支付宝默认正文的量级），
 * 次要文字 15sp，说明文字 13sp。长辈皮肤在此基础上再乘 1.18，正文到 20sp，
 * 达到工信部适老化规范对"大字"的要求。
 */
private fun arenaTypography(metrics: ArenaMetrics): Typography {
    val s = metrics.typeScale
    val heading = metrics.headingFamily
    fun sp(value: Float) = (value * s).sp
    return Typography(
        // 提问页的渐变大标题。中文在系统字体下只有 400 一个字重，
        // 这里靠字号和渐变拉层次，不靠字重。
        headlineLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Medium,
            fontSize = sp(30f),
            lineHeight = sp(38f),
        ),
        headlineMedium = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = sp(26f),
            lineHeight = sp(33f),
        ),
        titleLarge = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.Bold,
            fontSize = sp(22f),
            lineHeight = sp(28f),
        ),
        titleMedium = TextStyle(
            fontFamily = heading,
            fontWeight = FontWeight.SemiBold,
            fontSize = sp(17f),
            lineHeight = sp(24f),
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = sp(15.5f),
            lineHeight = sp(22f),
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(17f),
            lineHeight = sp(26f),
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(15f),
            lineHeight = sp(22f),
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(13f),
            lineHeight = sp(19f),
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = sp(16f),
            lineHeight = sp(22f),
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = sp(13.5f),
            lineHeight = sp(18f),
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = sp(12f),
            lineHeight = sp(16f),
        ),
    )
}

private fun ArenaPalette.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = debate,
        background = page,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = muted,
        outline = border,
        error = error,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = debate,
        background = page,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = muted,
        outline = border,
        error = error,
        onError = Color.White,
    )
}

@Composable
fun ArenaTheme(
    skin: ArenaSkin = ArenaSkin.default,
    content: @Composable () -> Unit,
) {
    val palette = skin.palette
    val metrics = skin.metrics
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivityWindow() ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.isDark
                isAppearanceLightNavigationBars = !palette.isDark
            }
        }
    }
    CompositionLocalProvider(
        LocalArenaPalette provides palette,
        LocalArenaMetrics provides metrics,
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = arenaTypography(metrics),
            content = content,
        )
    }
}

private fun Context.findActivityWindow(): android.view.Window? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current.window
        current = current.baseContext
    }
    return null
}

/** 皮肤选择的本地持久化。与登录态无关，卸载前一直保留。 */
class ArenaSkinPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_appearance",
        Context.MODE_PRIVATE,
    )

    /**
     * 读取皮肤。0.5.0 把默认皮肤从「清朗」换成了「净白」，做一次性迁移：
     * 只有当存的值正好是**旧的默认值**时才搬过去 —— 这些用户等同于"从没选过"。
     * 主动选过其它皮肤的人不受影响，迁移后再改回清朗也不会被再次覆盖。
     */
    fun loadSkin(): ArenaSkin {
        val stored = preferences.getString(KEY_SKIN, null)
        if (!preferences.getBoolean(KEY_DEFAULT_MIGRATED, false)) {
            preferences.edit { putBoolean(KEY_DEFAULT_MIGRATED, true) }
            if (stored == null || stored == LEGACY_DEFAULT_SKIN) {
                saveSkin(ArenaSkin.default)
                return ArenaSkin.default
            }
        }
        return ArenaSkin.fromName(stored)
    }

    fun saveSkin(skin: ArenaSkin) {
        preferences.edit { putString(KEY_SKIN, skin.name) }
    }

    private companion object {
        const val KEY_SKIN = "skin"
        const val KEY_DEFAULT_MIGRATED = "default_skin_migrated_v5"
        const val LEGACY_DEFAULT_SKIN = "CLEAR"
    }
}
