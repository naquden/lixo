package se.lixo.app.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Screen 2 (`project.md` §2 & §4):
 * - Scrollable 100-level linear Campaign Map (Candy Crush / Super Mario feel).
 * - Child's 3D avatar stands on the current active level node.
 * - Shows 1–3 stars per level, subject icon, timed-challenge indicator, and anti-farming / gift replay note.
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.SkyBackgroundBottom)
    ) {
        // Top HUD Bar: Active Avatar badge, Total Stars, Calendar-Day Streak
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LixoColors.CardSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(LixoColors.CardSurfaceElevated)
                    .clickable { onSwitchProfileClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "🐾 ${profile.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.SoftTextWhite
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⭐ ${profile.totalStars} ${LixoStrings.MAP_STARS_LABEL}",
                        style = MaterialTheme.typography.titleMedium,
                        color = LixoColors.StarGold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🔥 ${profile.currentStreakDays} ${LixoStrings.MAP_STREAK_LABEL}",
                        style = MaterialTheme.typography.titleMedium,
                        color = LixoColors.AccentOrange
                    )
                }
            }
        }

        // Scrollable Winding Adventure Path (Levels 1..100)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(levels, key = { it.id }) { level ->
                val isUnlocked = progressionEngine.isLevelUnlocked(
                    levelOrder = level.order,
                    progressByLevelId = progressByLevelId,
                    levels = levels
                )
                val progress = progressByLevelId[level.id]
                val stars = progress?.starsEarned ?: 0
                val isCurrentActiveNode = level.order == profile.currentLevelOrder

                // Winding Candy Crush / Mario horizontal wave offset
                val waveOffsetDp = (sin(level.order * 0.65) * 44.0).toInt().dp

                val subjectColor = when (level.subject) {
                    Subject.SWEDISH -> LixoColors.PrimaryActionGreen
                    Subject.MATH -> LixoColors.SecondaryBlue
                    Subject.ENGLISH -> Color(0xFF9B59B6)
                }
                val subjectEmoji = when (level.subject) {
                    Subject.SWEDISH -> "📖"
                    Subject.MATH -> "🧮"
                    Subject.ENGLISH -> "🇬🇧"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = waveOffsetDp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(290.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (isUnlocked) LixoColors.CardSurface else Color(0xFF162032)
                            )
                            .border(
                                width = if (isCurrentActiveNode) 3.dp else 1.5.dp,
                                color = when {
                                    isCurrentActiveNode -> LixoColors.StarGold
                                    isUnlocked -> subjectColor
                                    else -> Color(0xFF334155)
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable(enabled = isUnlocked) {
                                onSelectLevel(level)
                            }
                            .padding(14.dp)
                    ) {
                        // 3D Avatar standing directly on the active campaign node!
                        if (isCurrentActiveNode) {
                            Avatar3DViewport(
                                appearance = profile.appearance,
                                catalogById = catalogById,
                                modifier = Modifier.size(115.dp),
                                interactiveRotation = false
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isUnlocked) subjectColor else Color(0xFF334155)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isUnlocked) subjectEmoji else "🔒",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = level.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUnlocked) LixoColors.SoftTextWhite else LixoColors.MutedText
                                )
                                Text(
                                    text = "${LixoStrings.subjectName(level.subject)} • ⏱️ ${level.timeRequirementSeconds}s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.MutedText
                                )
                            }
                            if (showSwedishTtsButtons && isUnlocked) {
                                TtsIconButton(
                                    textToSpeak = level.title,
                                    ttsEngine = ttsEngine
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 1–3 Star Display
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 1..3) {
                                Text(
                                    text = if (i <= stars) "⭐" else "☆",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = if (i <= stars) LixoColors.StarGold else LixoColors.MutedText,
                                    modifier = Modifier.padding(horizontal = 3.dp)
                                )
                            }
                            if (progress?.completedTimedChallenge == true) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "⚡ ${LixoStrings.MAP_TIMED_BADGE}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.AccentOrange
                                )
                            }
                        }

                        if (isUnlocked) {
                            if (stars == 3) {
                                Text(
                                    text = "🎁 ${LixoStrings.MAP_MAXED_STARS_HINT}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.StarGold
                                )
                            } else if (stars in 1..2) {
                                Text(
                                    text = "🎁 ${LixoStrings.MAP_REPLAY_GIFT_HINT}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.PrimaryActionGreen
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            BouncyButton(
                                onClick = { onSelectLevel(level) },
                                containerColor = subjectColor,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(LixoStrings.MAP_PLAY_LEVEL_BTN, color = Color.White)
                            }
                        } else {
                            Text(
                                text = LixoStrings.MAP_LOCKED_HINT,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.MutedText
                            )
                        }
                    }
                }
            }
        }
    }
}
