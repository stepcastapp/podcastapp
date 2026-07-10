package com.nsavage.stepcast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Ink & Signal" — Stepcast's own look. Cool near-black ink in the dark,
 * paper-white in the light, a neon-cyan signal primary with electric-violet
 * support (deliberately NOT green — too close to Spotify). The app owns its
 * identity by default; wallpaper dynamic color (Material You) is available
 * as an opt-in accent choice.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF00687B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFABEDFF),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF52634F), // quiet sage — lets violet be THE second accent
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF6442C9), // electric violet
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6DEFF),
    onTertiaryContainer = Color(0xFF1F0060),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F7EE),
    surfaceContainer = Color(0xFFEDF2E8),
    surfaceContainerHigh = Color(0xFFE7ECE2),
    surfaceContainerHighest = Color(0xFFE1E6DC)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4AE3FF), // the neon signal
    onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFABEDFF),
    secondary = Color(0xFFB9CCB2), // quiet sage
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3A4B37),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFCFBCFF), // electric violet
    onTertiary = Color(0xFF350092),
    tertiaryContainer = Color(0xFF4C24B0),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1C9BF),
    outline = Color(0xFF8B938A),
    surfaceContainerLowest = Color(0xFF0A0F0B),
    surfaceContainerLow = Color(0xFF171E18),
    surfaceContainer = Color(0xFF1B221C),
    surfaceContainerHigh = Color(0xFF212922),
    surfaceContainerHighest = Color(0xFF2C342C)
)

/** Extra-bold, slightly tightened titles; everything else stays Material. */
private val StepcastTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    )
)

private val StepcastShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** The 8 primary-role colors an accent choice swaps in (light + dark). */
private class AccentSpec(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color
)

private val accentSpecs = mapOf(
    AccentColor.CYAN to AccentSpec(
        Color(0xFF00687B), Color.White, Color(0xFFABEDFF), Color(0xFF001F26),
        Color(0xFF4AE3FF), Color(0xFF003641), Color(0xFF004E5B), Color(0xFFABEDFF)
    ),
    AccentColor.GREEN to AccentSpec(
        Color(0xFF006E1C), Color.White, Color(0xFF94F990), Color(0xFF002204),
        Color(0xFF46F76E), Color(0xFF003912), Color(0xFF00531D), Color(0xFF94F990)
    ),
    AccentColor.ORANGE to AccentSpec(
        Color(0xFFD84315), Color.White, Color(0xFFFFDBCF), Color(0xFF380D00),
        Color(0xFFFFB59A), Color(0xFF5B1B00), Color(0xFF7A2E12), Color(0xFFFFDBCF)
    ),
    AccentColor.VIOLET to AccentSpec(
        Color(0xFF6442C9), Color.White, Color(0xFFE6DEFF), Color(0xFF1F0060),
        Color(0xFFCFBCFF), Color(0xFF350092), Color(0xFF4C24B0), Color(0xFFE6DEFF)
    ),
    AccentColor.GOLD to AccentSpec(
        Color(0xFF825500), Color.White, Color(0xFFFFDDB0), Color(0xFF2A1800),
        Color(0xFFFFB955), Color(0xFF452B00), Color(0xFF633F00), Color(0xFFFFDDB0)
    ),
    AccentColor.PINK to AccentSpec(
        Color(0xFFB9006E), Color.White, Color(0xFFFFD8E7), Color(0xFF3D0021),
        Color(0xFFFF54A9), Color(0xFF640038), Color(0xFF8E0051), Color(0xFFFFD8E7)
    )
)

/**
 * Derives the 8 primary-role colors from one wheel-picked color. Tonal-ish:
 * light mode darkens the pick for contrast on paper, dark mode brightens it
 * against the ink; containers are heavy mixes toward each background.
 */
private fun customSpec(base: Color): AccentSpec {
    fun onColor(c: Color) = if (c.luminance() > 0.45f) Color.Black else Color.White
    val lightPrimary = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.20f)
    val lightContainer = androidx.compose.ui.graphics.lerp(base, Color.White, 0.78f)
    val darkPrimary = androidx.compose.ui.graphics.lerp(base, Color.White, 0.12f)
    val darkContainer = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.58f)
    return AccentSpec(
        lightPrimary = lightPrimary,
        lightOnPrimary = onColor(lightPrimary),
        lightContainer = lightContainer,
        lightOnContainer = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.75f),
        darkPrimary = darkPrimary,
        darkOnPrimary = onColor(darkPrimary),
        darkContainer = darkContainer,
        darkOnContainer = androidx.compose.ui.graphics.lerp(base, Color.White, 0.75f)
    )
}

/**
 * Every primary accent ships with a paired support (tertiary) color — the
 * "Auto" choice in the support-color picker. Users can override it with any
 * preset or a custom wheel pick.
 */
private val defaultSecondary = mapOf(
    AccentColor.CYAN to AccentColor.VIOLET,
    AccentColor.GREEN to AccentColor.CYAN,
    AccentColor.ORANGE to AccentColor.CYAN,
    AccentColor.VIOLET to AccentColor.GOLD,
    AccentColor.GOLD to AccentColor.VIOLET,
    AccentColor.PINK to AccentColor.CYAN,
    AccentColor.DYNAMIC to AccentColor.VIOLET, // only used if explicitly overridden
    AccentColor.CUSTOM to AccentColor.VIOLET
)

/** The pairing that ships with the current primary (what "Auto" means now). */
fun pairedSecondaryAccent(): AccentColor =
    defaultSecondary[ThemePrefs.accent] ?: AccentColor.VIOLET

/** The support accent in effect: the user's pick, or the primary's pairing. */
fun resolvedSecondaryAccent(): AccentColor {
    val chosen = ThemePrefs.secondaryAccent
    if (chosen != null && chosen != AccentColor.DYNAMIC) return chosen
    return defaultSecondary[ThemePrefs.accent] ?: AccentColor.VIOLET
}

private fun specFor(accent: AccentColor, customArgb: Int): AccentSpec =
    if (accent == AccentColor.CUSTOM) {
        customSpec(Color(customArgb))
    } else {
        accentSpecs[accent] ?: accentSpecs.getValue(AccentColor.VIOLET)
    }

/** True when the Material You accent choice can actually work here. */
fun dynamicAccentAvailable(): Boolean = android.os.Build.VERSION.SDK_INT >= 31

/** Swatch color for the accent picker dots. */
@Composable
fun accentSwatch(accent: AccentColor, dark: Boolean): Color {
    if (accent == AccentColor.CUSTOM) {
        return Color(ThemePrefs.customAccentArgb)
    }
    if (accent == AccentColor.DYNAMIC) {
        // the wallpaper accent itself, so the dot previews what you'd get
        return if (dynamicAccentAvailable()) {
            androidx.compose.ui.res.colorResource(
                if (dark) android.R.color.system_accent1_200
                else android.R.color.system_accent1_600
            )
        } else {
            Color(0xFF9E9E9E)
        }
    }
    val spec = accentSpecs.getValue(accent)
    return if (dark) spec.darkPrimary else spec.lightPrimary
}

@Composable
fun StepcastTheme(content: @Composable () -> Unit) {
    val darkTheme = when (ThemePrefs.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val baseScheme = if (ThemePrefs.accent == AccentColor.DYNAMIC && dynamicAccentAvailable()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        if (darkTheme) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
    } else {
        // DYNAMIC picked on a pre-12 device falls back to the house cyan
        val spec = if (ThemePrefs.accent == AccentColor.CUSTOM) {
            customSpec(Color(ThemePrefs.customAccentArgb))
        } else {
            accentSpecs.getValue(
                ThemePrefs.accent.takeIf { it != AccentColor.DYNAMIC }
                    ?: AccentColor.CYAN
            )
        }
        if (darkTheme) {
            DarkColors.copy(
                primary = spec.darkPrimary,
                onPrimary = spec.darkOnPrimary,
                primaryContainer = spec.darkContainer,
                onPrimaryContainer = spec.darkOnContainer
            )
        } else {
            LightColors.copy(
                primary = spec.lightPrimary,
                onPrimary = spec.lightOnPrimary,
                primaryContainer = spec.lightContainer,
                onPrimaryContainer = spec.lightOnContainer
            )
        }
    }
    // support (tertiary) roles come from the chosen/paired secondary accent;
    // Material You on Auto keeps its own wallpaper tertiary
    val dynamicAuto = ThemePrefs.accent == AccentColor.DYNAMIC &&
        dynamicAccentAvailable() && ThemePrefs.secondaryAccent == null
    val scheme = if (dynamicAuto) {
        baseScheme
    } else {
        val secondarySpec = specFor(
            resolvedSecondaryAccent(), ThemePrefs.customSecondaryArgb
        )
        if (darkTheme) {
            baseScheme.copy(
                tertiary = secondarySpec.darkPrimary,
                onTertiary = secondarySpec.darkOnPrimary,
                tertiaryContainer = secondarySpec.darkContainer,
                onTertiaryContainer = secondarySpec.darkOnContainer
            )
        } else {
            baseScheme.copy(
                tertiary = secondarySpec.lightPrimary,
                onTertiary = secondarySpec.lightOnPrimary,
                tertiaryContainer = secondarySpec.lightContainer,
                onTertiaryContainer = secondarySpec.lightOnContainer
            )
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = StepcastTypography,
        shapes = StepcastShapes,
        content = content
    )
}

/** Swatch color for the SUPPORT-color picker dots (custom uses its own ARGB). */
@Composable
fun secondarySwatch(accent: AccentColor, dark: Boolean): Color =
    if (accent == AccentColor.CUSTOM) {
        Color(ThemePrefs.customSecondaryArgb)
    } else {
        accentSwatch(accent, dark)
    }
