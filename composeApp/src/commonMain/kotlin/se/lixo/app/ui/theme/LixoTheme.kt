package se.lixo.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lixo.composeapp.generated.resources.Res
import lixo.composeapp.generated.resources.lexend_bold
import lixo.composeapp.generated.resources.lexend_extrabold
import lixo.composeapp.generated.resources.lexend_medium
import lixo.composeapp.generated.resources.lexend_regular
import lixo.composeapp.generated.resources.lexend_semibold
import org.jetbrains.compose.resources.Font
import se.lixo.app.domain.model.Rarity

/**
 * Lixo design tokens inspired by Duolingo:
 * Clean white surfaces, subtle 2dp borders, 3D tactile buttons with distinct 4dp lips,
 * high-contrast legible typography, and zero emoji clutter in the chrome.
 */
object LixoColors {
    // Surfaces
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFF7F7F7)
    val SurfaceElevated = Color(0xFFEEEEEE)
    val Border = Color(0xFFE5E5E5)
    val BorderDarker = Color(0xFFCECECE)

    // Text
    val TextPrimary = Color(0xFF2B2B2B)
    val TextSecondary = Color(0xFF6B7280)
    val TextMuted = Color(0xFF9CA3AF)
    val TextDisabled = Color(0xFFAFAFAF)
    val OnColor = Color(0xFFFFFFFF)

    // Brand accents
    val Green = Color(0xFF58CC02)
    val GreenLip = Color(0xFF46A302)
    val GreenTint = Color(0xFFD7FFB8)

    val Blue = Color(0xFF1CB0F6)
    val BlueLip = Color(0xFF1899D6)
    val BlueTint = Color(0xFFDDF4FF)
    val BlueBorder = Color(0xFF84D8FF)

    val Gold = Color(0xFFFFC800)
    val GoldLip = Color(0xFFE5B400)
    val GoldTint = Color(0xFFFFF7D6)

    val Orange = Color(0xFFFF9600)
    val OrangeLip = Color(0xFFCC7800)
    val OrangeTint = Color(0xFFFFEAD1)

    val Red = Color(0xFFFF4B4B)
    val RedLip = Color(0xFFEA2B2B)
    val RedTint = Color(0xFFFFDFE0)

    val Purple = Color(0xFFCE82FF)
    val PurpleLip = Color(0xFFA560E8)
    val PurpleTint = Color(0xFFF6E8FF)

    // Disabled / Locked
    val LockedFace = Color(0xFFE5E7EB)
    val LockedLip = Color(0xFFD1D5DB)

    val Scrim = Color(0x66000000)

    // Compatibility aliases for legacy screen references during refactoring
    val SkyBackgroundTop = Color(0xFFF7F7F7)
    val SkyBackgroundBottom = Color(0xFFF7F7F7)
    val CardSurface = Color(0xFFFFFFFF)
    val CardSurfaceElevated = Color(0xFFF3F4F6)
    val PrimaryActionGreen = Green
    val PrimaryActionGreenLip = GreenLip
    val SecondaryBlue = Blue
    val SecondaryBlueLip = BlueLip
    val StarGold = Gold
    val AccentOrange = Orange
    val SoftTextWhite = TextPrimary
    val MutedText = TextSecondary

    fun rarityColor(rarity: Rarity): Color = Color(rarity.hexColorPrimary)

    fun rarityBrush(rarity: Rarity): Brush = Brush.verticalGradient(
        colors = listOf(Color(rarity.hexColorPrimary), Color(rarity.hexColorAccent))
    )
}

/** 4pt-based spacing scale */
object LixoSpacing {
    val XXS = 4.dp
    val XS = 8.dp
    val S = 12.dp
    val M = 16.dp
    val L = 20.dp
    val XL = 24.dp
    val XXL = 32.dp

    val Screen = 16.dp
}

@Composable
private fun rememberLexend(): FontFamily {
    val regular = Font(Res.font.lexend_regular, FontWeight.Normal)
    val medium = Font(Res.font.lexend_medium, FontWeight.Medium)
    val semi = Font(Res.font.lexend_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.lexend_bold, FontWeight.Bold)
    val extra = Font(Res.font.lexend_extrabold, FontWeight.ExtraBold)
    return remember(regular, medium, semi, bold, extra) {
        FontFamily(regular, medium, semi, bold, extra)
    }
}

@Composable
private fun rememberLixoTypography(): Typography {
    val f = rememberLexend()
    return remember(f) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp
            ),
            headlineLarge = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp
            ),
            headlineMedium = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp
            ),
            titleLarge = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.sp
            ),
            titleMedium = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp
            ),
            bodyLarge = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 26.sp,
                letterSpacing = 0.15.sp
            ),
            bodyMedium = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.15.sp
            ),
            labelLarge = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.6.sp
            ),
            labelMedium = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp
            ),
            labelSmall = TextStyle(
                fontFamily = f,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.3.sp
            )
        )
    }
}

@Composable
fun LixoTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = LixoColors.Green,
        secondary = LixoColors.Blue,
        tertiary = LixoColors.Orange,
        background = LixoColors.Background,
        surface = LixoColors.Surface,
        surfaceVariant = LixoColors.SurfaceElevated,
        onPrimary = LixoColors.OnColor,
        onSecondary = LixoColors.OnColor,
        onBackground = LixoColors.TextPrimary,
        onSurface = LixoColors.TextPrimary,
        error = LixoColors.Red
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = rememberLixoTypography()
    ) {
        CompositionLocalProvider(
            LocalContentColor provides LixoColors.TextPrimary,
            content = content
        )
    }
}
