package io.paimon.fx991.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

val Mono = FontFamily.Monospace

// ---------------------------------------------------------------------------
// Signature colors: Orange SHIFT / Purple ALPHA — must be preserved in both light and dark modes
// ---------------------------------------------------------------------------

val ShiftOrange = Color(0xFFF2A23C)
val ShiftOrangeDeep = Color(0xFFD9822B)
val ShiftOrangeInk = Color(0xFF2A1B08)

val AlphaPurple = Color(0xFF9B7BE0)
val AlphaPurpleDeep = Color(0xFF8A63D2)
val AlphaPurpleInk = Color(0xFF1B1030)

val AccentOrange = Color(0xFFE0873A)   // DEL / AC
val AccentOrangeInk = Color(0xFF2A1806)

// ---------------------------------------------------------------------------
// Calculator-specific color tokens (supplements to Material color schemes, all derived from these two themes)
// ---------------------------------------------------------------------------

data class CalcColors(
    val body: Color,
    val bodyEdge: Color,
    val bodyInk: Color,
    val lcdBg: Color,
    val lcdFg: Color,
    val lcdDim: Color,
    val lcdEdge: Color,
    val lcdError: Color,
    val keyNeutral: Color,
    val keyNeutralInk: Color,
    val keyDigit: Color,
    val keyDigitInk: Color,
    val keyOp: Color,
    val keyOpInk: Color,
    val keyAccent: Color,
    val keyAccentInk: Color,
    val keyShift: Color,
    val keyShiftInk: Color,
    val keyAlpha: Color,
    val keyAlphaInk: Color,
    val keyEquals: Color,
    val keyEqualsInk: Color,
    val keyEdge: Color,
    val chrome: Color,
    val chromeInk: Color,
    val chromeActive: Color,
)

private val LightCalc = CalcColors(
    body = Color(0xFFE5E9EE),
    bodyEdge = Color(0xFFD2D8E0),
    bodyInk = Color(0xFF23272E),
    lcdBg = Color(0xFFC9D6B4),
    lcdFg = Color(0xFF141C0F),
    lcdDim = Color(0xFF5C6B4A),
    lcdEdge = Color(0xFF8A9A76),
    lcdError = Color(0xFF9B2C1C),
    keyNeutral = Color(0xFFDCE1E8),
    keyNeutralInk = Color(0xFF23272E),
    keyDigit = Color(0xFFF1F4F8),
    keyDigitInk = Color(0xFF1A1D22),
    keyOp = Color(0xFFD2D8E0),
    keyOpInk = Color(0xFF1A1D22),
    keyAccent = AccentOrange,
    keyAccentInk = AccentOrangeInk,
    keyShift = ShiftOrange,
    keyShiftInk = ShiftOrangeInk,
    keyAlpha = AlphaPurpleDeep,
    keyAlphaInk = Color(0xFFF6F2FF),
    keyEquals = Color(0xFF4C5560),
    keyEqualsInk = Color(0xFFF5F7FA),
    keyEdge = Color(0x22000000),
    chrome = Color(0x00000000),
    chromeInk = Color(0xFF3A4048),
    chromeActive = Color(0xFF3A4048),
)

private val DarkCalc = CalcColors(
    body = Color(0xFF111418),
    bodyEdge = Color(0xFF1C2126),
    bodyInk = Color(0xFFEDF0F4),
    lcdBg = Color(0xFF2C3427),
    lcdFg = Color(0xFFE4EFD6),
    lcdDim = Color(0xFF7C8C6C),
    lcdEdge = Color(0xFF3B4630),
    lcdError = Color(0xFFE2705C),
    keyNeutral = Color(0xFF2B3138),
    keyNeutralInk = Color(0xFFE7EAEF),
    keyDigit = Color(0xFF363D46),
    keyDigitInk = Color(0xFFF3F6F9),
    keyOp = Color(0xFF31383F),
    keyOpInk = Color(0xFFF0F3F6),
    keyAccent = AccentOrange,
    keyAccentInk = AccentOrangeInk,
    keyShift = ShiftOrange,
    keyShiftInk = ShiftOrangeInk,
    keyAlpha = AlphaPurple,
    keyAlphaInk = AlphaPurpleInk,
    keyEquals = Color(0xFFDCE3EB),
    keyEqualsInk = Color(0xFF14181D),
    keyEdge = Color(0x1AFFFFFF),
    chrome = Color(0x00000000),
    chromeInk = Color(0xFFC7CED6),
    chromeActive = Color(0xFFC7CED6),
)

val LocalCalcColors = staticCompositionLocalOf { DarkCalc }

// ---------------------------------------------------------------------------
// Material 3 Shape Scale: Calculator mostly uses medium — keycaps medium (14dp), panels large (20dp)
// ---------------------------------------------------------------------------

private val CalcShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Deeply adaptive theme: follows system dark/light mode; both color sets preserve orange SHIFT / purple ALPHA */
@Composable
fun CalcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val calc = if (darkTheme) DarkCalc else LightCalc
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = ShiftOrange,
            onPrimary = ShiftOrangeInk,
            secondary = AlphaPurple,
            onSecondary = AlphaPurpleInk,
            tertiary = AccentOrange,
            onTertiary = AccentOrangeInk,
            background = calc.body,
            onBackground = calc.bodyInk,
            surface = calc.body,
            onSurface = calc.bodyInk,
            surfaceVariant = calc.keyNeutral,
            onSurfaceVariant = calc.keyNeutralInk,
            error = calc.lcdError,
            // MD3 tonal elevation (dark): layered by tone steps, without stroke borders
            surfaceContainerLowest = Color(0xFF0B0F13),
            surfaceContainerLow = Color(0xFF141A20),
            surfaceContainer = Color(0xFF1A2128),
            surfaceContainerHigh = Color(0xFF222A33),
            surfaceContainerHighest = Color(0xFF2B343E),
            inverseSurface = Color(0xFFE3E8EE),
            inverseOnSurface = Color(0xFF14181D),
        )
    } else {
        lightColorScheme(
            primary = ShiftOrange,
            onPrimary = ShiftOrangeInk,
            primaryContainer = Color(0xFFFFDDB3),
            onPrimaryContainer = Color(0xFF2A1B08),
            secondary = AlphaPurpleDeep,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE9DDFF),
            onSecondaryContainer = Color(0xFF1B1030),
            tertiary = AccentOrange,
            onTertiary = AccentOrangeInk,
            background = calc.body,
            onBackground = calc.bodyInk,
            surface = calc.body,
            onSurface = calc.bodyInk,
            surfaceVariant = calc.keyNeutral,
            onSurfaceVariant = calc.keyNeutralInk,
            outline = Color(0xFF767C85),
            outlineVariant = Color(0xFFC6CCD4),
            error = calc.lcdError,
            // MD3 tonal elevation: derived from light grey-blue base to avoid purple-grey clash with custom skins
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF4F7FA),
            surfaceContainer = Color(0xFFEDF1F5),
            surfaceContainerHigh = Color(0xFFE4E9EF),
            surfaceContainerHighest = Color(0xFFDCE2E9),
            inverseSurface = Color(0xFF2F3640),
            inverseOnSurface = Color(0xFFF5F7FA),
        )
    }
    // Under edge-to-edge, status bar / navigation bar icon luminance must be explicitly managed:
    // Follow in-app theme (may be a manual override, not necessarily system dark mode): light bg uses dark icons and vice versa.
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
    CompositionLocalProvider(LocalCalcColors provides calc) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            shapes = CalcShapes,
            content = content,
        )
    }
}

/** Finds the host Activity from Compose Context (which might be wrapped inside a ContextWrapper). */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
