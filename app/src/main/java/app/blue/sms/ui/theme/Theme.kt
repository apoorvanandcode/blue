package app.blue.sms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.blue.sms.R

/** The calm palette from the design: mostly ink and grey; blue only for the main action, green only for money in. */
object Blue {
    val Ink = Color(0xFF0E1726)
    val Grey = Color(0xFF6B7280)
    val Grey2 = Color(0xFF4B5563)
    val Faint = Color(0xFF9CA3AF)
    val Line = Color(0xFFECEEF2)
    val Border = Color(0xFFE5E7EB)
    val Wash = Color(0xFFF6F7F9)
    val Accent = Color(0xFF1F5FD6)
    val MoneyIn = Color(0xFF157347)
    val Error = Color(0xFFB42318)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: FontWeight) =
    Font(res, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))

val Inter = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.inter, it) }
)
val Grotesk = FontFamily(
    listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.space_grotesk, it) }
)

private val Light = lightColorScheme(
    primary = Blue.Accent,
    onPrimary = Color.White,
    primaryContainer = Blue.Wash,
    onPrimaryContainer = Blue.Ink,
    // Selected chips are black, not coloured.
    secondaryContainer = Blue.Ink,
    onSecondaryContainer = Color.White,
    background = Color.White,
    onBackground = Blue.Ink,
    surface = Color.White,
    onSurface = Blue.Ink,
    onSurfaceVariant = Blue.Grey,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Blue.Wash,
    surfaceContainerHighest = Blue.Wash,
    outline = Blue.Border,
    outlineVariant = Blue.Line,
    error = Blue.Error,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF1A202B),
    onPrimaryContainer = Color(0xFFF3F4F6),
    secondaryContainer = Color(0xFFF3F4F6),
    onSecondaryContainer = Color(0xFF0E1117),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFFA1A8B3),
    surfaceContainerLowest = Color(0xFF0E1117),
    surfaceContainerLow = Color(0xFF0E1117),
    surfaceContainer = Color(0xFF0E1117),
    surfaceContainerHigh = Color(0xFF1A202B),
    surfaceContainerHighest = Color(0xFF1A202B),
    outline = Color(0xFF2E3542),
    outlineVariant = Color(0xFF232A35),
)

private fun t(family: FontFamily, weight: FontWeight, size: Int, line: Int, spacing: Double = 0.0) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val BlueType = Typography(
    displaySmall = t(Grotesk, FontWeight.SemiBold, 40, 46, -1.0),
    headlineLarge = t(Grotesk, FontWeight.SemiBold, 36, 42, -1.0),
    headlineMedium = t(Grotesk, FontWeight.SemiBold, 30, 36, -0.6),
    headlineSmall = t(Grotesk, FontWeight.SemiBold, 24, 30, -0.4),
    titleLarge = t(Grotesk, FontWeight.SemiBold, 22, 28, -0.3),
    titleMedium = t(Inter, FontWeight.Medium, 16, 22),
    titleSmall = t(Inter, FontWeight.SemiBold, 14, 20),
    bodyLarge = t(Inter, FontWeight.Normal, 16, 24),
    bodyMedium = t(Inter, FontWeight.Normal, 15, 22),
    bodySmall = t(Inter, FontWeight.Normal, 13, 19),
    labelLarge = t(Inter, FontWeight.SemiBold, 15, 20),
    labelMedium = t(Inter, FontWeight.Medium, 13, 18),
    labelSmall = t(Inter, FontWeight.Medium, 12, 16),
)

@Composable
fun BlueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = BlueType, content = content)
}
