package se.lixo.app.ui.screens.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoSpacing
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.StatPill
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Duolingo-style Campaign Map:
 * - Clean top HUD with avatar badge, stars, and streak.
 * - Unit header card introducing the current module.
 * - Snaking path of circular 3D lesson nodes with stars, locks, and mascot placement.
 * - Interactive bottom sheet popup on node tap for starting the level without cluttering the map.
 */
@Composable
fun CampaignMapScreen(
    profile: ChildProfile,
    levels: List<CampaignLevel>,
    progressByLevelId: Map<String, LevelProgress>,
    catalogById: Map<String, WardrobeItem>,
    progressionEngine: ProgressionAndGiftEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectLevel: (CampaignLevel) -> Unit,
    onSwitchProfileClick: () -> Unit
) {
    var selectedLevelForSheet by remember { mutableStateOf<CampaignLevel?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top HUD Bar: Avatar profile selector (left) | Stars & Streak (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LixoColors.Background)
                    .border(width = 1.dp, color = LixoColors.Border)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(LixoColors.Surface)
                        .border(1.dp, LixoColors.Border, RoundedCornerShape(14.dp))
                        .clickable { onSwitchProfileClick() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    LixoIcon(icon = LixoIcons.Person, tint = LixoColors.Blue, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill(
                        icon = LixoIcons.Star,
                        value = "${profile.totalStars}",
                        tint = LixoColors.GoldLip
                    )
                    StatPill(
                        icon = LixoIcons.Flame,
                        value = "${profile.currentStreakDays}",
                        tint = LixoColors.Orange
                    )
                }
            }

            // Scrollable Adventure Path
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Duolingo Unit Header Card
                item(key = "unit_header") {
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = LixoColors.Green,
                        borderColor = LixoColors.GreenLip,
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AVSNITT 1",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Lixos Magiska Skogsstig",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Läsning, matematik & grundläggande ord",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                            if (showSwedishTtsButtons) {
                                TtsIconButton(
                                    textToSpeak = "Avsnitt 1: Lixos Magiska Skogsstig",
                                    ttsEngine = ttsEngine,
                                    tint = LixoColors.Green
                                )
                            }
                        }
                    }
                }

                // Level Nodes in a Gentle Sine-Waved Path
                items(levels, key = { it.id }) { level ->
                    val isUnlocked = progressionEngine.isLevelUnlocked(
                        levelOrder = level.order,
                        progressByLevelId = progressByLevelId,
                        levels = levels
                    )
                    val progress = progressByLevelId[level.id]
                    val stars = progress?.starsEarned ?: 0
                    val isCurrentActiveNode = level.order == profile.currentLevelOrder

                    // Gentle sine wave offset (max +/- 56dp)
                    val waveOffsetDp = (sin(level.order * 0.7) * 56.0).toInt().dp

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = waveOffsetDp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Current level mascot placed beside the active node
                        if (isCurrentActiveNode) {
                            Box(
                                modifier = Modifier
                                    .align(if (waveOffsetDp >= 0.dp) Alignment.CenterStart else Alignment.CenterEnd)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Avatar3DViewport(
                                    appearance = profile.appearance,
                                    catalogById = catalogById,
                                    modifier = Modifier.size(90.dp),
                                    interactiveRotation = false
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // "START" Speech Bubble above active node
                            if (isCurrentActiveNode) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(LixoColors.Surface)
                                        .border(2.dp, LixoColors.Green, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "START",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = LixoColors.GreenLip
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }

                            // 3D Circular Level Node
                            CampaignLevelNode(
                                level = level,
                                isUnlocked = isUnlocked,
                                isCurrent = isCurrentActiveNode,
                                stars = stars,
                                onClick = {
                                    if (isUnlocked) {
                                        selectedLevelForSheet = level
                                    }
                                }
                            )

                            // Stars under completed node
                            if (isUnlocked && stars > 0) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    for (i in 1..3) {
                                        LixoIcon(
                                            icon = LixoIcons.Star,
                                            tint = if (i <= stars) LixoColors.Gold else LixoColors.BorderDarker,
                                            size = 14.dp,
                                            modifier = Modifier.padding(horizontal = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Duolingo-style Level Details Bottom Sheet
        AnimatedVisibility(
            visible = selectedLevelForSheet != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedLevelForSheet?.let { level ->
                val progress = progressByLevelId[level.id]
                val stars = progress?.starsEarned ?: 0

                // Scrim backdrop
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LixoColors.Scrim)
                        .clickable { selectedLevelForSheet = null },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Modal Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(LixoColors.Background)
                            .clickable(enabled = false) {}
                            .padding(24.dp)
                    ) {
                        // Header row with title + close button
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = level.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = LixoColors.TextPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${LixoStrings.subjectName(level.subject)} · ${level.timeRequirementSeconds}s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.TextSecondary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (showSwedishTtsButtons) {
                                    TtsIconButton(
                                        textToSpeak = level.title,
                                        ttsEngine = ttsEngine
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(LixoColors.Surface)
                                        .clickable { selectedLevelForSheet = null },
                                    contentAlignment = Alignment.Center
                                ) {
                                    LixoIcon(
                                        icon = LixoIcons.Close,
                                        tint = LixoColors.TextSecondary,
                                        size = 18.dp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Star rating & badges row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(LixoColors.Surface)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (i in 1..3) {
                                    LixoIcon(
                                        icon = LixoIcons.Star,
                                        tint = if (i <= stars) LixoColors.Gold else LixoColors.BorderDarker,
                                        size = 22.dp,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }

                            if (progress?.completedTimedChallenge == true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LixoIcon(icon = LixoIcons.Bolt, tint = LixoColors.Orange, size = 18.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = LixoStrings.MAP_TIMED_BADGE,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LixoColors.Orange
                                    )
                                }
                            }
                        }

                        // Replay reward hint
                        if (stars == 3) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.GoldLip, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = LixoStrings.MAP_MAXED_STARS_HINT,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.TextSecondary
                                )
                            }
                        } else if (stars in 1..2) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.Green, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = LixoStrings.MAP_REPLAY_GIFT_HINT,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.GreenLip
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // Big Tactile Start Button
                        LixoButton(
                            onClick = {
                                selectedLevelForSheet = null
                                onSelectLevel(level)
                            },
                            variant = if (stars > 0) LixoButtonVariant.SECONDARY else LixoButtonVariant.PRIMARY,
                            height = 54.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (stars > 0) "SPELA IGEN" else "STARTA BANA",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Circular 72dp 3D lesson node with distinct bottom lip.
 */
@Composable
private fun CampaignLevelNode(
    level: CampaignLevel,
    isUnlocked: Boolean,
    isCurrent: Boolean,
    stars: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset = if (isPressed && isUnlocked) 4.dp else 0.dp

    val (faceColor, lipColor, iconColor) = when {
        !isUnlocked -> Triple(LixoColors.LockedFace, LixoColors.LockedLip, LixoColors.TextDisabled)
        stars == 3 -> Triple(LixoColors.Gold, LixoColors.GoldLip, Color.White)
        isCurrent -> Triple(LixoColors.Green, LixoColors.GreenLip, Color.White)
        else -> when (level.subject) {
            Subject.SWEDISH -> Triple(LixoColors.Green, LixoColors.GreenLip, Color.White)
            Subject.MATH -> Triple(LixoColors.Blue, LixoColors.BlueLip, Color.White)
            Subject.ENGLISH -> Triple(LixoColors.Purple, LixoColors.PurpleLip, Color.White)
        }
    }

    val icon = when {
        !isUnlocked -> LixoIcons.Lock
        level.subject == Subject.SWEDISH -> LixoIcons.Book
        level.subject == Subject.MATH -> LixoIcons.Calculator
        else -> LixoIcons.Star
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(lipColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = isUnlocked,
                onClick = onClick
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .offset(y = pressOffset)
                .padding(bottom = 5.dp)
                .clip(CircleShape)
                .background(faceColor),
            contentAlignment = Alignment.Center
        ) {
            LixoIcon(
                icon = icon,
                tint = iconColor,
                size = 32.dp
            )
        }
    }
}
