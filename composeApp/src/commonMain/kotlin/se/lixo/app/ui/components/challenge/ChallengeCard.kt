package se.lixo.app.ui.components.challenge

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AchievementAction
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.requiresAiMode
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

const val CHALLENGE_CARD_TAG_PREFIX = "challenge_"
const val LADDER_TIER_TAG_PREFIX = "ladder_tier_"

/** The big picture icon for a challenge chain (doc/plans/014). */
fun AchievementAction.icon(): ImageVector = when (this) {
    AchievementAction.READ_STORY -> LixoIcons.Book
    AchievementAction.SOLVE_MATH -> LixoIcons.Calculator
    AchievementAction.COLLECT_STARS -> LixoIcons.Star
    AchievementAction.PERFECT_LEVEL -> LixoIcons.StarTriple
    AchievementAction.FIRST_TRY -> LixoIcons.Check
    AchievementAction.STREAK_DAY -> LixoIcons.Flame
    AchievementAction.CLOCK_MONEY -> LixoIcons.Timer
    AchievementAction.READ_FAST -> LixoIcons.Bolt
    AchievementAction.READ_ALOUD -> LixoIcons.Mic
    AchievementAction.EXPLORE_WORLD -> LixoIcons.Globe
}

/**
 * Big, picture-first icon for a challenge's current tier: a 64dp rarity-tinted circle with the
 * action icon, and a badge showing the target number (or a check when the whole chain is done).
 */
@Composable
fun ChallengeHeroIcon(action: AchievementAction, target: Int, rarity: Rarity, finished: Boolean) {
    val rarityColor = LixoColors.rarityColor(rarity)
    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(rarityColor.copy(alpha = 0.18f))
                .border(3.dp, rarityColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LixoIcon(icon = action.icon(), tint = rarityColor, size = 40.dp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .clip(CircleShape)
                .background(rarityColor),
            contentAlignment = Alignment.Center
        ) {
            if (finished) {
                LixoIcon(icon = LixoIcons.Check, tint = Color.White, size = 14.dp)
            } else {
                Text(
                    text = "$target",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }
    }
}

/** Picture equation row: `[action icon] × target → [trophy]`, with a Mic chip for AI chains. */
@Composable
fun ChallengeEquation(action: AchievementAction, target: Int, rarity: Rarity) {
    val rarityColor = LixoColors.rarityColor(rarity)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LixoIcon(icon = action.icon(), tint = LixoColors.TextPrimary, size = 28.dp)
        if (action == AchievementAction.CLOCK_MONEY) {
            Spacer(Modifier.width(4.dp))
            LixoIcon(icon = LixoIcons.Coin, tint = LixoColors.TextPrimary, size = 28.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "× $target",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LixoColors.TextPrimary
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "→", style = MaterialTheme.typography.titleMedium, color = LixoColors.TextSecondary)
        Spacer(Modifier.width(8.dp))
        LixoIcon(icon = LixoIcons.Trophy, tint = rarityColor, size = 28.dp)
        if (action.requiresAiMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(LixoColors.BlueTint),
                contentAlignment = Alignment.Center
            ) {
                LixoIcon(icon = LixoIcons.Mic, tint = LixoColors.Blue, size = 14.dp)
            }
        }
    }
}

/** Progress for the current tier: pips (target <= 10) or a bar with "current / target". */
@Composable
fun ChallengeProgress(action: AchievementAction, current: Int, target: Int, rarity: Rarity) {
    val rarityColor = LixoColors.rarityColor(rarity)
    val a11y = LixoStrings.challengeProgressA11y(current, target)
    if (target <= 10) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(target) { i ->
                val filled = i < current
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(
                            if (filled) {
                                Modifier.background(rarityColor)
                            } else {
                                Modifier.border(2.dp, LixoColors.Border, CircleShape)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (filled) {
                        LixoIcon(icon = action.icon(), tint = Color.White, size = 14.dp)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            verticalAlignment = Alignment.CenterVertically
        ) {
            LixoProgressBar(
                progress = current.toFloat() / target.toFloat(),
                fillColor = rarityColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$current / $target",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary
            )
        }
    }
}

/**
 * One small trophy per tier: filled+check when unlocked, pulsing outline for the current tier,
 * faded+lock for the rest. Wraps onto a second row when the tiers do not fit (10 COLLECT_STARS
 * tiers at 360dp, doc/plans/018).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LadderStrip(tiers: List<Achievement>, currentId: String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tiers.forEach { tier ->
            val rarityColor = LixoColors.rarityColor(tier.rarity)
            Box(
                modifier = Modifier
                    .testTag(LADDER_TIER_TAG_PREFIX + tier.id)
                    .size(26.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    tier.isUnlocked -> {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(rarityColor),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(icon = LixoIcons.Trophy, tint = Color.White, size = 16.dp)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(LixoColors.Background),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(icon = LixoIcons.Check, tint = LixoColors.GreenLip, size = 10.dp)
                        }
                    }

                    tier.id == currentId -> {
                        val infiniteTransition = rememberInfiniteTransition(label = "lixo_challenge_pulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.12f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 900),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "lixo_challenge_pulse_scale"
                        )
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                }
                                .clip(CircleShape)
                                .border(2.dp, rarityColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(icon = LixoIcons.Trophy, tint = rarityColor, size = 16.dp)
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier.alpha(0.35f).size(26.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(icon = LixoIcons.Trophy, tint = rarityColor, size = 16.dp)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(LixoColors.Background),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(icon = LixoIcons.Lock, tint = LixoColors.TextSecondary, size = 10.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Kid-friendly, picture-first card for one challenge chain, showing its current tier (doc/plans/014). */
@Composable
fun ChallengeCard(tiers: List<Achievement>, ttsEngine: TextToSpeechEngine, modifier: Modifier = Modifier) {
    val current = AchievementEngine.currentTier(tiers)
    val action = current.action ?: return
    val finished = tiers.all { it.isUnlocked }

    LixoCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CHALLENGE_CARD_TAG_PREFIX + action.name),
        cornerRadius = 18.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChallengeHeroIcon(action, current.targetCount, current.rarity, finished)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ChallengeEquation(action, current.targetCount, current.rarity)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (finished) LixoStrings.CHALLENGE_DONE else current.shortSv,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = LixoColors.TextPrimary
                    )
                }
                Spacer(Modifier.width(8.dp))
                TtsIconButton(
                    textToSpeak = if (finished) LixoStrings.CHALLENGE_DONE_SPOKEN else current.spokenSv,
                    ttsEngine = ttsEngine,
                    size = 48.dp,
                    contentDescription = LixoStrings.CHALLENGE_READ_ALOUD_CD
                )
            }

            Spacer(Modifier.height(14.dp))

            if (!finished) {
                ChallengeProgress(action, current.currentCount, current.targetCount, current.rarity)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LixoIcon(icon = LixoIcons.Check, tint = LixoColors.GreenLip, size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = LixoStrings.CHALLENGE_DONE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.GreenLip
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            LadderStrip(tiers, current.id)
        }
    }
}
