package com.sabid.applock.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.sabid.applock.core.model.ThemeSkin

/**
 * "Liquid glass" palettes: a deep base, two slow-moving colour blobs behind everything, and
 * translucent surfaces on top. Each skin changes only colour, never structure, so the lock
 * screen is equally readable in all of them.
 */
private data class Skin(
    val accent: Color,
    val accentDim: Color,
    val bg: Color,
    val blobA: Color,
    val blobB: Color
)

private fun skinFor(theme: ThemeSkin): Skin = when (theme) {
    ThemeSkin.MIDNIGHT -> Skin(Color(0xFF8CB8FF), Color(0xFF3F67B5), Color(0xFF0A0E1A), Color(0xFF2E5BFF), Color(0xFF8A3FFC))
    ThemeSkin.BRASS -> Skin(Color(0xFFFFC46B), Color(0xFF9A6A1E), Color(0xFF110D07), Color(0xFFFF9A2E), Color(0xFFC2410C))
    ThemeSkin.MOSS -> Skin(Color(0xFF7FE0A6), Color(0xFF2F7A52), Color(0xFF07110B), Color(0xFF10B981), Color(0xFF0EA5E9))
    ThemeSkin.SLATE -> Skin(Color(0xFFB8C4D8), Color(0xFF5F6C80), Color(0xFF0B0D12), Color(0xFF64748B), Color(0xFF6366F1))
    ThemeSkin.INK_ROSE -> Skin(Color(0xFFFF9EB5), Color(0xFF9D3E5C), Color(0xFF14080D), Color(0xFFF43F5E), Color(0xFFA855F7))
}

/** Full-screen viewers set this to true so the floating tab bar steps out of their way. */
val LocalHideChrome = compositionLocalOf { androidx.compose.runtime.mutableStateOf(false) }

/** Glass-surface tints shared by [glass] and the cards. */
object Glass {
    val fill = Color(0x14FFFFFF)
    val fillStrong = Color(0x24FFFFFF)
    val edgeTop = Color(0x55FFFFFF)
    val edgeBottom = Color(0x12FFFFFF)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Frosted-glass card: translucent fill plus a light-catching hairline edge. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false): Modifier =
    this
        .background(if (strong) Glass.fillStrong else Glass.fill, shape)
        .border(1.dp, Brush.verticalGradient(listOf(Glass.edgeTop, Glass.edgeBottom)), shape)

@Composable
fun AppLockTheme(
    theme: ThemeSkin = ThemeSkin.MIDNIGHT,
    forceDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val skin = skinFor(theme)

    val colors = darkColorScheme(
        primary = skin.accent,
        onPrimary = Color(0xFF06080D),
        primaryContainer = skin.accentDim,
        onPrimaryContainer = Color(0xFFF5F7FB),
        secondary = skin.accentDim,
        // Transparent on purpose: every screen sits on the liquid backdrop drawn below, and a
        // opaque background colour anywhere would hide it.
        background = Color.Transparent,
        onBackground = Color(0xFFF1F3F8),
        surface = Glass.fill,
        onSurface = Color(0xFFF1F3F8),
        // surfaceVariant and outline are deliberately opaque. Screens call .copy(alpha = ...) on
        // them, and copy() *replaces* alpha rather than scaling it, so a translucent white here
        // turned into a pale-grey chip with unreadable text.
        surfaceVariant = Color(0xFF2B3452),
        onSurfaceVariant = Color(0xFFA9B1C1),
        surfaceContainerHigh = Color(0xFF1B2030),
        surfaceContainerHighest = Color(0xFF232A3D),
        surfaceContainer = Color(0xFF151A28),
        outline = Color(0xFF7D86A6),
        outlineVariant = Color(0x1FFFFFFF),
        error = Color(0xFFFF8A80)
    )

    MaterialTheme(colorScheme = colors, typography = AppLockTypography, shapes = AppShapes) {
        Box(Modifier.fillMaxSize()) {
            LiquidBackdrop(skin.bg, skin.blobA, skin.blobB)
            content()
        }
    }
}

/** Opaque base plus two slowly drifting colour blobs. Opaque so overlay windows never show through. */
@Composable
private fun LiquidBackdrop(base: Color, a: Color, b: Color) {
    val t = rememberInfiniteTransition(label = "liquid")
    val x by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
        label = "x"
    )
    val y by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse),
        label = "y"
    )
    Canvas(Modifier.fillMaxSize().background(base)) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.75f
        val ca = Offset(w * (0.1f + 0.5f * x), h * (0.05f + 0.3f * y))
        val cb = Offset(w * (0.95f - 0.5f * y), h * (0.95f - 0.35f * x))
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.42f), Color.Transparent), ca, r), r, ca)
        drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.34f), Color.Transparent), cb, r), r, cb)
    }
}
