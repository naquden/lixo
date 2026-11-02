package se.lixo.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lixo.app.domain.model.Rarity
import se.lixo.app.platform.TextToSpeechEngine

object LixoColors {
    val SkyBackgroundTop = Color(0xFF1A2942)
    val SkyBackgroundBottom = Color(0xFF0F172A)
    val CardSurface = Color(0xFF1E293B)
    val CardSurfaceElevated = Color(0xFF334155)

    val PrimaryActionGreen = Color(0xFF2ECC71)
    val PrimaryActionGreenLip = Color(0xFF27AE60)

    val SecondaryBlue = Color(0xFF3498DB)
    val SecondaryBlueLip = Color(0xFF2980B9)

    val StarGold = Color(0xFFFFD54F)
    val AccentOrange = Color(0xFFF39C12)
    val SoftTextWhite = Color(0xFFF8FAFC)
    val MutedText = Color(0xFF94A3B8)

    /**
     * Fortnite 2D Rarity background gradient (`project.md` §2 "Sällsynthet: Bakgrundsfärg i 2D enligt Fortnites färgskala").
     */
    fun rarityBrush(rarity: Rarity): Brush = Brush.verticalGradient(
        colors = listOf(
            Color(rarity.hexColorPrimary),
            Color(rarity.hexColorAccent)
        )
    )
}

/**
 * Dyslexia-friendly Lexend-inspired typography with generous letter spacing, clear line height,
 * and dynamic text scaling support (`project.md` §4 "dyslexivänligt typsnitt (t.ex. Lexend)").
 */
val LixoDyslexiaFriendlyTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.6.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.4.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.6.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun LixoTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = LixoColors.PrimaryActionGreen,
        secondary = LixoColors.SecondaryBlue,
        tertiary = LixoColors.AccentOrange,
        background = LixoColors.SkyBackgroundBottom,
        surface = LixoColors.CardSurface,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = LixoColors.SoftTextWhite,
        onSurface = LixoColors.SoftTextWhite
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LixoDyslexiaFriendlyTypography,
        content = content
    )
}

/**
 * Tactile child-friendly button with spring bounce animation and 3D lip (`project.md` §4:
 * "Tactile knappar, mikroanimationer... rymliga touch-ytor").
 */
@Composable
fun BouncyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = LixoColors.PrimaryActionGreen,
    lipColor: Color = LixoColors.PrimaryActionGreenLip,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.94f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bouncy_btn_scale"
    )

    val effectiveContainer = if (enabled) containerColor else Color(0xFF475569)
    val effectiveLip = if (enabled) lipColor else Color(0xFF334155)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(effectiveLip)
            .padding(bottom = if (isPressed && enabled) 1.dp else 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(effectiveContainer)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/**
 * Universal Swedish TTS Speaker Icon Button (`project.md` §2 & §4:
 * "TTS: Appen måste ha svensk röst/tal (högtalarikoner för instruktioner, UI och ordförklaringar)").
 * Gracefully hides itself if Swedish TTS is unavailable on the current platform (`PlatformFeatureFlags`).
 */
@Composable
fun TtsIconButton(
    textToSpeak: String,
    ttsEngine: TextToSpeechEngine,
    languageIso: String = "sv-SE",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(LixoColors.SecondaryBlue)
            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            .clickable {
                ttsEngine.speak(textToSpeak, languageIso = languageIso)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🔊",
            fontSize = 18.sp
        )
    }
}

/**
 * Animated progress bar with star sparkles (`project.md` §4: "framstegsmätare med partiklar").
 */
@Composable
fun ParticleProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "particle_progress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF0F172A))
            .border(2.dp, Color(0xFF334155), RoundedCornerShape(11.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(11.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(LixoColors.PrimaryActionGreen, LixoColors.StarGold)
                    )
                )
        )
        if (animatedProgress > 0.05f) {
            Text(
                text = "✨",
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
            )
        }
    }
}
