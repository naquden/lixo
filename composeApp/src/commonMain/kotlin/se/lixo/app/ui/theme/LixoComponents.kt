package se.lixo.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lixo.composeapp.generated.resources.Res
import lixo.composeapp.generated.resources.allDrawableResources
import org.jetbrains.compose.resources.painterResource
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics

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
    val haptics = rememberLixoHaptics()
    val currentEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is PressInteraction.Press && currentEnabled) {
                haptics.tick(SfxCue.BUTTON_TAP)
            }
        }
    }

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
    tint: Color = LixoColors.Blue,
    size: Dp = 36.dp,
    contentDescription: String? = null
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Button
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LixoColors.BlueTint)
            .border(1.5.dp, LixoColors.BlueBorder, CircleShape)
            .clickable {
                ttsEngine.speak(textToSpeak, languageIso = languageIso)
            }
            .then(semanticsModifier),
        contentAlignment = Alignment.Center
    ) {
        LixoIcon(
            icon = LixoIcons.Speaker,
            tint = tint,
            size = size / 2
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
    onClick: () -> Unit
) {
    val (faceColor, borderColor, lipColor, textColor) = when (isCorrect) {
        true -> Quad(LixoColors.GreenTint, LixoColors.Green, LixoColors.GreenLip, LixoColors.GreenLip)
        false -> Quad(LixoColors.GoldTint, LixoColors.Gold, LixoColors.GoldLip, LixoColors.GoldLip)
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

/** Test-tag prefix for geography flag choice cards, keyed by lowercase iso2 (e.g. "flag_choice_se"). */
const val FLAG_CHOICE_TAG_PREFIX = "flag_choice_"

/**
 * One flag image choice card for the geography world (plan 037), rendered 2x2 by
 * `MathActivityScreen` for `MathProblemType.FLAG` questions. Mirrors [ChoiceCard]'s face/lip/border
 * colour states (always unselected, since the answer is an image, not pre-selected text). The
 * flag image has no `contentDescription` and the card's own description is the generic
 * [LixoStrings.FLAG_CHOICE_A11Y] so the accessible name never reveals the correct answer.
 */
@Composable
fun FlagChoiceCard(
    iso2: String,
    modifier: Modifier = Modifier,
    isCorrect: Boolean? = null,
    onClick: () -> Unit
) {
    val (face, border, lip) = when (isCorrect) {
        true -> Triple(LixoColors.GreenTint, LixoColors.Green, LixoColors.GreenLip)
        false -> Triple(LixoColors.GoldTint, LixoColors.Gold, LixoColors.GoldLip)
        null -> Triple(Color.White, LixoColors.Border, LixoColors.BorderDarker)
    }

    Box(
        modifier = modifier
            .testTag(FLAG_CHOICE_TAG_PREFIX + iso2.lowercase())
            .semantics {
                contentDescription = LixoStrings.FLAG_CHOICE_A11Y
                role = Role.Button
            }
            .clip(RoundedCornerShape(16.dp))
            .background(lip)
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(face)
            .border(2.dp, border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        val res = remember(iso2) { Res.allDrawableResources["flag_${iso2.lowercase()}"] }
        val imgMod = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, LixoColors.Border, RoundedCornerShape(6.dp))
        if (res != null) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = imgMod
            )
        } else {
            println("LixoFlags: WARN missing flag_$iso2")
            Box(imgMod.background(LixoColors.Surface))
        }
    }
}

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

/**
 * Reusable Duolingo-style bottom sheet: fullscreen scrim + rounded modal card that slides up
 * from the bottom. Tapping the scrim or any caller-provided close action dismisses it.
 */
@Composable
fun LixoBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    PlatformBackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LixoColors.Scrim)
                .clickable { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(LixoColors.Background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(24.dp),
                content = content
            )
        }
    }
}

/**
 * Small round tactile icon button with the same 3D "lip" look as [LixoButton]: a white face,
 * a darker bottom lip that doubles as the press-shadow, and a neutral border.
 */
@Composable
fun LixoCircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = LixoColors.TextSecondary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset = if (isPressed) 2.dp else 0.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LixoColors.BorderDarker)
            .padding(bottom = 3.dp)
            .offset(y = pressOffset)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.5.dp, LixoColors.Border, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LixoIcon(icon = icon, tint = tint, size = size * 0.5f)
    }
}

/**
 * Dashed rounded outline for unread yellow (needs-practice) word chips.
 */
internal fun Modifier.dashedBorder(color: Color, cornerRadius: Dp, strokeWidth: Dp = 1.5.dp): Modifier =
    this.drawWithCache {
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
        )
        val radiusPx = cornerRadius.toPx()
        onDrawWithContent {
            drawContent()
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = stroke
            )
        }
    }
