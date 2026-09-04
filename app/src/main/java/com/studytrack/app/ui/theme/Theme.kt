package com.studytrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.studytrack.app.domain.PaceStatus

// "Analyst's ledger" palette \u2014 deliberately not default Material
// purple. Ink/paper for structure, teal-to-brick for a status spectrum
// that reads at a glance (spec section 7's traffic-light indicator).
val Ink900 = Color(0xFF12181F)
val Ink700 = Color(0xFF232C38)
val Paper50 = Color(0xFFEDEBE3)
val PaperElevated = Color(0xFFF7F5EF)
val Teal = Color(0xFF1F6F5C)
val TealBright = Color(0xFF2E9A80)
val SteelBlue = Color(0xFF3B587A)
val SteelBlueBright = Color(0xFF6E8CB8)
val Gold = Color(0xFFA88B2E)
val GoldBright = Color(0xFFC7A64A)
val Ochre = Color(0xFFC17A2E)
val OchreBright = Color(0xFFD89246)
val Brick = Color(0xFFB14834)
val BrickBright = Color(0xFFD5634C)
val InkNearBlack = Color(0xFF0E1319)
val SurfaceDark = Color(0xFF171E27)
val PaperText = Color(0xFFE9E7E0)

val NumericFontFamily = FontFamily.Monospace
val LabelFontFamily = FontFamily.SansSerif

val StudyTrackTypography = Typography(
    headlineMedium = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = LabelFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp)
)

// Reserved for every numeric/data readout \u2014 timer, hours, percentages
// \u2014 a deliberate tabular treatment that separates "data" from
// "labels" (spec section 36: finance/professional-study aesthetic).
val NumericLarge = TextStyle(fontFamily = NumericFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 36.sp)
val NumericMedium = TextStyle(fontFamily = NumericFontFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp)
val NumericSmall = TextStyle(fontFamily = NumericFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = PaperElevated,
    secondary = SteelBlue, onSecondary = PaperElevated,
    tertiary = Ochre, onTertiary = PaperElevated,
    error = Brick, onError = PaperElevated,
    background = Paper50, onBackground = Ink900,
    surface = PaperElevated, onSurface = Ink900,
    surfaceVariant = Color(0xFFE2DFD4), onSurfaceVariant = Ink700,
    outline = Color(0xFFB9B6AA)
)

private val DarkColors = darkColorScheme(
    primary = TealBright, onPrimary = InkNearBlack,
    secondary = SteelBlueBright, onSecondary = InkNearBlack,
    tertiary = OchreBright, onTertiary = InkNearBlack,
    error = BrickBright, onError = InkNearBlack,
    background = InkNearBlack, onBackground = PaperText,
    surface = SurfaceDark, onSurface = PaperText,
    surfaceVariant = Color(0xFF232C38), onSurfaceVariant = PaperText,
    outline = Color(0xFF3C4552)
)

/** 🟢/🟡/🟠/🔴 from spec section 7, mapped to the ledger palette. */
fun paceStatusColor(status: PaceStatus, dark: Boolean): Color = when (status) {
    PaceStatus.AHEAD -> if (dark) TealBright else Teal
    PaceStatus.ON_TRACK -> if (dark) GoldBright else Gold
    PaceStatus.BEHIND -> if (dark) OchreBright else Ochre
    PaceStatus.SIGNIFICANTLY_BEHIND -> if (dark) BrickBright else Brick
}

fun paceStatusLabel(status: PaceStatus): String = when (status) {
    PaceStatus.AHEAD -> "Ahead"
    PaceStatus.ON_TRACK -> "On track"
    PaceStatus.BEHIND -> "Falling behind"
    PaceStatus.SIGNIFICANTLY_BEHIND -> "Significantly behind"
}

@Composable
fun StudyTrackTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = StudyTrackTypography, content = content)
}
