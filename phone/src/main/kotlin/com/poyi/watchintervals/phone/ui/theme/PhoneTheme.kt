package com.poyi.watchintervals.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 步序手机端设计系统令牌。
 *
 * 这里的数值是双端共享规范在手机端的表达。手表端使用同一套语义命名,
 * 但按 378x496 小屏另行缩放,见 watch 模块的 WatchTokens。
 */

object PhoneColor {
    // 画布与卡片层
    val Canvas = Color(0xFFF4F5F7)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceDeep = Color(0xFFE7EAEE)
    val SurfaceHigh = Color(0xFFF0F2F4)
    val Border = Color(0xFFD9DEE4)
    val Navigation = Color(0xFF191C20)
    val NavigationLine = Color(0xFF30343A)
    val NavigationText = Color(0xFFF5F7FA)
    val NavigationMuted = Color(0xFFA9B1BA)

    // 文字层
    val Text = Color(0xFF15181C)
    val TextDim = Color(0xFF52606C)
    val Hint = Color(0xFF5E6D7B)
    val OnAccent = Color(0xFFFFFFFF)

    // 语义强调色（现代高饱和高质感运动色系）
    val Move = Color(0xFFE11D48)           // 活力玫瑰红 #E11D48
    val Exercise = Color(0xFF10B981)       // 翡翠运动绿 #10B981
    val Stand = Color(0xFF0284C7)          // 活力天蓝 #0284C7
    val Caution = Color(0xFFD97706)        // 警示暖橙 #D97706
    val Warning = Color(0xFFF59E0B)        // 明亮橙黄 #F59E0B
    val Danger = Color(0xFFEF4444)         // 高亮警示红 #EF4444
    val Success = Color(0xFF10B981)        // 成功绿 #10B981
    val ExerciseBright = Color(0xFF34D399) // 亮绿 #34D399
    val StandBright = Color(0xFF38BDF8)    // 亮青 #38BDF8
    val DangerBright = Color(0xFFF87171)   // 亮红 #F87171
    val WarningBright = Color(0xFFFBBF24)  // 亮黄 #FBBF24

    // 浅色语义填充
    val FillRun = Color(0xFFECFDF5)
    val FillWalk = Color(0xFFF0F9FF)
    val FillRest = Color(0xFFFFFBEB)
    val FillDanger = Color(0xFFFEF2F2)
    val FillSelected = Color(0xFFFFF1F2)

    // 睡眠阶段（优雅清晰的多层光谱）
    val SleepDeep = Color(0xFF3B82F6)      // 经典深睡蓝 #3B82F6
    val SleepLight = Color(0xFF60A5FA)     // 浅睡天蓝 #60A5FA
    val SleepRem = Color(0xFF8B5CF6)       // REM 梦境紫 #8B5CF6
    val SleepAwake = Color(0xFFF59E0B)     // 清醒暖橙 #F59E0B

    // 玻璃功能层
    val GlassTop = Color(0xFFFFFFFF)
    val GlassBottom = Color(0xFFFFFFFF)
    val GlassBorder = Border
    val GlassSelected = FillSelected
}

object PhoneType {
    val Display = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    val Headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    val Subhead = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
    val Body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    val Metric = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
}

val PhoneTypography = Typography(
    displayLarge = PhoneType.Display,
    headlineSmall = PhoneType.Headline,
    titleLarge = PhoneType.Title,
    titleMedium = PhoneType.Subhead,
    bodyLarge = PhoneType.Body,
    bodyMedium = PhoneType.Body,
    labelLarge = PhoneType.Label,
    bodySmall = PhoneType.Caption
)

private val LightColors = lightColorScheme(
    primary = PhoneColor.Move,
    onPrimary = PhoneColor.OnAccent,
    primaryContainer = PhoneColor.FillSelected,
    onPrimaryContainer = PhoneColor.Move,
    secondary = PhoneColor.Exercise,
    onSecondary = PhoneColor.OnAccent,
    secondaryContainer = PhoneColor.FillRun,
    onSecondaryContainer = PhoneColor.Exercise,
    tertiary = PhoneColor.Stand,
    onTertiary = PhoneColor.OnAccent,
    tertiaryContainer = PhoneColor.FillWalk,
    onTertiaryContainer = PhoneColor.Stand,
    background = PhoneColor.Canvas,
    onBackground = PhoneColor.Text,
    surface = PhoneColor.Surface,
    onSurface = PhoneColor.Text,
    surfaceVariant = PhoneColor.SurfaceHigh,
    onSurfaceVariant = PhoneColor.TextDim,
    surfaceContainerHighest = PhoneColor.SurfaceDeep,
    outline = PhoneColor.Border,
    outlineVariant = PhoneColor.Border,
    error = PhoneColor.Danger,
    onError = PhoneColor.OnAccent,
    errorContainer = PhoneColor.FillDanger,
    onErrorContainer = PhoneColor.Danger
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8CA3),
    onPrimary = Color(0xFF5E0F22),
    secondary = Color(0xFF8ED49A),
    onSecondary = Color(0xFF0E3B13),
    tertiary = Color(0xFF7FD4EC),
    onTertiary = Color(0xFF003543),
    background = Color(0xFF12161B),
    onBackground = Color(0xFFE4E9EF),
    surface = Color(0xFF171C22),
    onSurface = Color(0xFFE4E9EF),
    surfaceVariant = Color(0xFF222932),
    onSurfaceVariant = Color(0xFFB7C2CE),
    outline = Color(0xFF39434E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun PhoneTheme(
    /** The phone product uses a daylight reading surface; dark mode is opt-in by the host. */
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PhoneTypography,
        content = content
    )
}
