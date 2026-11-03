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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lixo.app.platform.TextToSpeechEngine

enum class LixoButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    GHOST,
    DANGER,
    GOLD
}

/**
 * Duolingo-style 3D tactile button with a 4dp bottom lip that pushes down when pressed.
 */
@Composable
fun LixoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LixoButtonVariant = LixoButtonVariant.PRIMARY,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (faceColor, lipColor, textColor) = when {
        !enabled -> Triple(LixoColors.LockedFace, LixoColors.LockedLip, LixoColors.TextDisabled)
        variant == LixoButtonVariant.PRIMARY -> Triple(LixoColors.Green, LixoColors.GreenLip, Color.White)
        variant == LixoButtonVariant.SECONDARY -> Triple(LixoColors.Blue, LixoColors.BlueLip, Color.White)
        variant == LixoButtonVariant.GOLD -> Triple(LixoColors.Gold, LixoColors.GoldLip, Color(0xFF4A3800))
        variant == LixoButtonVariant.DANGER -> Triple(LixoColors.Red, LixoColors.RedLip, Color.White)
        variant == LixoButtonVariant.OUTLINE -> Triple(Color.White, LixoColors.BorderDarker, LixoColors.Blue)
        variant == LixoButtonVariant.GHOST -> Triple(Color.Transparent, Color.Transparent, LixoColors.TextSecondary)
        else -> Triple(LixoColors.Green, LixoColors.GreenLip, Color.White)
    }

    val isGhost = variant == LixoButtonVariant.GHOST
    val isOutline = variant == LixoButtonVariant.OUTLINE
    val lipHeight = if (isGhost) 0.dp else 4.dp
    val pressOffset = if (isPressed && enabled && !isGhost) 3.dp else 0.dp

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isGhost) Color.Transparent else lipColor)
            .padding(bottom = lipHeight)
            .offset(y = pressOffset)
            .clip(RoundedCornerShape(14.dp))
            .background(faceColor)
            .then(
                if (isOutline) Modifier.border(2.dp, LixoColors.Border, RoundedCornerShape(14.dp))
                else Modifier
            )
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

/** Backward-compatible adapter for existing screen references */
@Composable
fun BouncyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = LixoColors.Green,
    lipColor: Color = LixoColors.GreenLip,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset = if (isPressed && enabled) 3.dp else 0.dp

    val effectiveFace = if (enabled) containerColor else LixoColors.LockedFace
    val effectiveLip = if (enabled) lipColor else LixoColors.LockedLip

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(effectiveLip)
            .padding(bottom = 3.dp)
            .offset(y = pressOffset)
            .clip(RoundedCornerShape(14.dp))
            .background(effectiveFace)
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
 * Duolingo-style Card: 2dp neutral border, 3dp bottom lip, crisp white background.
 */
@Composable
fun LixoCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    borderColor: Color = if (selected) LixoColors.Blue else LixoColors.Border,
    backgroundColor: Color = if (selected) LixoColors.BlueTint else LixoColors.Background,
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val lipColor = if (selected) LixoColors.BlueBorder else LixoColors.BorderDarker
    val baseModifier = modifier
        .clip(RoundedCornerShape(cornerRadius))
        .background(lipColor)
        .padding(bottom = 3.dp)
        .clip(RoundedCornerShape(cornerRadius - 2.dp))
        .background(backgroundColor)
        .border(2.dp, borderColor, RoundedCornerShape(cornerRadius - 2.dp))

    val finalModifier = if (onClick != null) {
        baseModifier.clickable { onClick() }
    } else {
        baseModifier
    }

    Box(
        modifier = finalModifier.padding(contentPadding),
        content = content
    )
}

/**
 * Subtle TTS Speaker Icon Button
 */
@Composable
fun TtsIconButton(
    textToSpeak: String,
    ttsEngine: TextToSpeechEngine,
    languageIso: String = "sv-SE",
    modifier: Modifier = Modifier,
    tint: Color = LixoColors.Blue
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(LixoColors.BlueTint)
            .border(1.5.dp, LixoColors.BlueBorder, CircleShape)
            .clickable {
                ttsEngine.speak(textToSpeak, languageIso = languageIso)
            },
        contentAlignment = Alignment.Center
    ) {
        LixoIcon(
            icon = LixoIcons.Speaker,
            tint = tint,
            size = 18.dp
        )
    }
}

/**
 * Duolingo-style Lesson Progress Bar: 14dp height, smooth rounded fill with top highlight.
 */
@Composable
fun LixoProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    fillColor: Color = LixoColors.Green,
    backgroundColor: Color = LixoColors.Border
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "lixo_progress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(backgroundColor)
    ) {
        if (animatedProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(fillColor)
            ) {
                // Subtle top highlight stripe for glossy 3D feel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }
        }
    }
}

/** Backward-compatible adapter for existing ParticleProgressBar references */
@Composable
fun ParticleProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    LixoProgressBar(progress = progress, modifier = modifier)
}

/**
 * Top HUD Stat Pill: icon + value (e.g. ⭐ 24, 🔥 5).
 */
@Composable
fun StatPill(
    icon: ImageVector,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
        .border(1.5.dp, LixoColors.Border, RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)

    val finalMod = if (onClick != null) base.clickable { onClick() } else base

    Row(
        modifier = finalMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LixoIcon(icon = icon, tint = tint, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = tint
        )
    }
}

/**
 * Choice Card for quiz answers, reading modes, and options.
 * Clear states: Neutral -> Selected (Blue) -> Correct (Green) -> Wrong (Red).
 */
@Composable
fun ChoiceCard(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    isCorrect: Boolean? = null,
    badgeNumber: String? = null,
    onClick: () -> Unit
) {
    val (faceColor, borderColor, lipColor, textColor) = when (isCorrect) {
        true -> Quad(LixoColors.GreenTint, LixoColors.Green, LixoColors.GreenLip, LixoColors.GreenLip)
        false -> Quad(LixoColors.RedTint, LixoColors.Red, LixoColors.RedLip, LixoColors.RedLip)
        null -> if (selected) {
            Quad(LixoColors.BlueTint, LixoColors.Blue, LixoColors.BlueLip, LixoColors.Blue)
        } else {
            Quad(Color.White, LixoColors.Border, LixoColors.BorderDarker, LixoColors.TextPrimary)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(lipColor)
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(faceColor)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (badgeNumber != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.5.dp, borderColor, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeNumber,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Segmented Tabs with equal widths and clean sliding/pill selection.
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LixoColors.SurfaceElevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(1.dp, LixoColors.Border, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) LixoColors.TextPrimary else LixoColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

data class LixoNavItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit
)

@Composable
fun LixoBottomNavBar(
    items: List<LixoNavItem>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LixoColors.Background)
            .border(width = 1.dp, color = LixoColors.Border)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val pressOffset = if (isPressed) 2.dp else 0.dp

            Column(
                modifier = Modifier
                    .weight(1f)
                    .offset(y = pressOffset)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = item.onClick
                    )
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (item.selected) LixoColors.BlueTint else Color.Transparent)
                        .then(
                            if (item.selected) Modifier.border(2.dp, LixoColors.BlueBorder, RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(
                        icon = item.icon,
                        tint = if (item.selected) LixoColors.Blue else LixoColors.TextMuted,
                        size = 22.dp
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (item.selected) FontWeight.ExtraBold else FontWeight.Bold,
                    color = if (item.selected) LixoColors.Blue else LixoColors.TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
