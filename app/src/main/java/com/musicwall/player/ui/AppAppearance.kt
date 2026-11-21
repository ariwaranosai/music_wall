package com.musicwall.player.ui

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicwall.player.AppTheme

internal data class MeterAppearance(
    val face: Brush,
    val outline: Color,
    val marks: Color,
    val warning: Color,
    val leftNeedle: Color,
    val rightNeedle: Color = leftNeedle,
    val hub: Color,
    val shape: Shape = RoundedCornerShape(10.dp),
    val aspectRatio: Float = 1.22f,
    val pivotHeight: Float = .88f,
    val borderWidth: Dp = 1.dp,
    val grid: Color? = null,
    val elevation: Dp = 0.dp,
)

internal data class SpectrumAppearance(
    val palette: List<Color>,
    val peak: Color,
    val reflectionTint: Color,
)

internal data class AppAppearance(
    val background: Brush,
    val colors: ColorScheme,
    val meters: MeterAppearance,
    val spectrum: SpectrumAppearance,
    val titleFont: FontFamily = FontFamily.Default,
    val coverCorner: Dp = 8.dp,
    val elevation: Dp = 0.dp,
) {
    val compactMeters = meters.copy(aspectRatio = 1.74f, pivotHeight = 1.014f)
}

private val AngledMeterFrame = GenericShape { size, _ ->
    val corner = size.minDimension * .045f
    moveTo(corner, 0f)
    lineTo(size.width - corner, 0f)
    lineTo(size.width, corner)
    lineTo(size.width, size.height - corner)
    lineTo(size.width - corner, size.height)
    lineTo(corner, size.height)
    lineTo(0f, size.height - corner)
    lineTo(0f, corner)
    close()
}

private val Mint = Color(0xff9de3c6)
private val Cyan = Color(0xff57f6f0)
private val Magenta = Color(0xffff68d7)
private val IceBlue = Color(0xff2a75e1)

private val MinimalAppearance = AppAppearance(
    background = Brush.radialGradient(listOf(Color(0xff1a2222), Color(0xff101415))),
    colors = darkColorScheme(
        primary = Mint,
        onPrimary = Color(0xff15241d),
        primaryContainer = Color(0xff2e463b),
        onPrimaryContainer = Color(0xffc8f0de),
        tertiary = Mint,
        background = Color(0xff101415),
        surface = Color(0xff1d2426),
        onSurface = Color(0xffecf2f1),
        onSurfaceVariant = Color(0xffa0b2b2),
        secondaryContainer = Color(0xff303c3c),
        onSecondaryContainer = Color(0xffd5e6e3),
        surfaceContainer = Color(0xff1f2526),
        surfaceContainerHigh = Color(0xff252e30),
        surfaceContainerHighest = Color(0xff30383a),
        outline = Color(0xff637476),
        outlineVariant = Color(0xff38474a),
        inverseSurface = Color(0xffd6e6e2),
        inverseOnSurface = Color(0xff273534),
        error = Color(0xffffa892),
    ),
    meters = MeterAppearance(
        face = Brush.radialGradient(listOf(Color(0xff2b3437), Color(0xff1d2325))),
        outline = Color(0xff444f51),
        marks = Color(0xffb1c2c1),
        warning = Color(0xffdd8b7a),
        leftNeedle = Mint,
        hub = Color(0xff3f5250),
    ),
    spectrum = SpectrumAppearance(
        palette = listOf(Color(0xffd0eee2), Color(0xff67c8b2), Color(0xff81bed9), Color(0xffbd9fe1)),
        peak = Color(0xfff16b9f),
        reflectionTint = Color(0xff63baaa),
    ),
)

private val CyberpunkAppearance = AppAppearance(
    background = Brush.radialGradient(listOf(Color(0xff201934), Color(0xff0d0c18))),
    colors = darkColorScheme(
        primary = Cyan,
        onPrimary = Color(0xff142425),
        primaryContainer = Color(0xff23474c),
        onPrimaryContainer = Color(0xffb6f8f5),
        tertiary = Cyan,
        background = Color(0xff0d0c18),
        surface = Color(0xff19182c),
        onSurface = Color(0xffebefff),
        onSurfaceVariant = Color(0xffb1b8da),
        secondaryContainer = Color(0xff303351),
        onSecondaryContainer = Color(0xffdce0ff),
        surfaceContainer = Color(0xff1e1d32),
        surfaceContainerHigh = Color(0xff24223b),
        surfaceContainerHighest = Color(0xff302e4b),
        outline = Color(0xff747ca6),
        outlineVariant = Color(0xff3d4165),
        inverseSurface = Color(0xffdfe4ff),
        inverseOnSurface = Color(0xff2a2a47),
        error = Color(0xfffd806e),
    ),
    meters = MeterAppearance(
        face = Brush.radialGradient(listOf(Color(0xff23233c), Color(0xff131628))),
        outline = Color(0xff4f5483),
        marks = Color(0xffb4bce1),
        warning = Color(0xfffd806e),
        leftNeedle = Cyan,
        rightNeedle = Magenta,
        hub = Color(0xff393859),
        shape = AngledMeterFrame,
        borderWidth = 1.5.dp,
        grid = Color(0xff4b557e).copy(alpha = .24f),
    ),
    spectrum = SpectrumAppearance(
        palette = listOf(Color(0xffd8ffff), Cyan, Color(0xff8e81ff), Color(0xffd66aee)),
        peak = Color(0xffff37b5),
        reflectionTint = Color(0xff609afa),
    ),
    titleFont = FontFamily.Monospace,
)

private val AppleAppearance = AppAppearance(
    background = Brush.radialGradient(listOf(Color(0xfff8fafe), Color(0xffe2e8f2))),
    colors = lightColorScheme(
        primary = Color(0xff2369ca),
        onPrimary = Color.White,
        primaryContainer = Color(0xffd8e8ff),
        onPrimaryContainer = Color(0xff153b6e),
        tertiary = IceBlue,
        background = Color(0xffe2e8f2),
        surface = Color(0xfff5f8fd),
        onSurface = Color(0xff151f31),
        onSurfaceVariant = Color(0xff54657a),
        secondaryContainer = Color(0xffd5e2f0),
        onSecondaryContainer = Color(0xff26394f),
        surfaceContainer = Color(0xffeaf0f8),
        surfaceContainerHigh = Color(0xffedf3fa),
        surfaceContainerHighest = Color(0xffdce5f0),
        outline = Color(0xff7b8ca3),
        outlineVariant = Color(0xffb8c7d9),
        inverseSurface = Color(0xff29394b),
        inverseOnSurface = Color(0xffe8eff9),
        error = Color(0xffa33734),
    ),
    meters = MeterAppearance(
        face = Brush.radialGradient(listOf(Color.White, Color(0xffebf2fc))),
        outline = Color(0xffc1cfe0),
        marks = Color(0xff53667e),
        warning = Color(0xffb34f4a),
        leftNeedle = IceBlue,
        hub = Color(0xffccdbef),
        shape = RoundedCornerShape(18.dp),
        elevation = 8.dp,
    ),
    spectrum = SpectrumAppearance(
        palette = listOf(Color(0xff9bb7dc), Color(0xff759ed1), Color(0xff6688c8), Color(0xff8e8bbd)),
        peak = Color(0xffc95f96),
        reflectionTint = Color(0xff779aca),
    ),
    coverCorner = 12.dp,
    elevation = 6.dp,
)

internal val AppTheme.appearance: AppAppearance
    get() = when (this) {
        AppTheme.Minimal -> MinimalAppearance
        AppTheme.Cyberpunk -> CyberpunkAppearance
        AppTheme.Apple -> AppleAppearance
    }
