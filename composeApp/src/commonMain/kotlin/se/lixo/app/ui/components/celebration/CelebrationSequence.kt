package se.lixo.app.ui.components.celebration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.feedback.LixoHaptics
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics

sealed interface CelebrationStep {
    data object Stars : CelebrationStep
    data class Chest(val item: WardrobeItem, val source: RewardSource, val labelDetailSv: String? = null) : CelebrationStep
    data class EquipPrompt(val item: WardrobeItem) : CelebrationStep
    data class AvatarPrompt(val avatarType: AvatarType) : CelebrationStep
    data class NextChallenge(val next: Achievement) : CelebrationStep
}

/**
 * Pure step builder for a [LevelCompletionOutcome]: stars, then a chest + equip prompt per newly
 * unlocked reward (in [LevelCompletionOutcome.newlyUnlockedRewards] order, i.e. reward-source
 * order) -- an ACHIEVEMENT reward's equip prompt is followed by a [CelebrationStep.NextChallenge]
 * when [LevelCompletionOutcome.nextChallengeByRewardItemId] has a next tier for it (doc/plans/014)
 * -- then an unlock prompt per newly unlocked avatar (sorted by [AvatarType.unlockStarsRequired]
 * so the order is deterministic). An already-owned achievement reward still yields its
 * NextChallenge, with no chest (doc/plans/018).
 */
fun buildCelebrationSteps(outcome: LevelCompletionOutcome): List<CelebrationStep> {
    val steps = mutableListOf<CelebrationStep>(CelebrationStep.Stars)
    outcome.newlyUnlockedRewards.forEach { reward ->
        steps += CelebrationStep.Chest(reward.item, reward.source, reward.labelDetailSv)
        steps += CelebrationStep.EquipPrompt(reward.item)
        if (reward.source == RewardSource.ACHIEVEMENT) {
            outcome.nextChallengeByRewardItemId[reward.item.id]?.let { steps += CelebrationStep.NextChallenge(it) }
        }
    }
    outcome.ownedRewardNextChallenges.forEach { steps += CelebrationStep.NextChallenge(it) }
    outcome.newlyUnlockedAvatars.sortedBy { it.unlockStarsRequired }.forEach { avatar ->
        steps += CelebrationStep.AvatarPrompt(avatar)
    }
    return steps
}

/**
 * Runs [buildCelebrationSteps] as a full-screen overlay above the completion card: stars with
 * confetti, then a chest + equip dialog per unlocked item, then an avatar-unlock dialog per
 * unlocked avatar. Confetti always renders last so it draws above every dialog and never
 * consumes touches.
 */
@Composable
fun CelebrationSequenceHost(
    outcome: LevelCompletionOutcome,
    starsEarned: Int,
    onEquipItem: (WardrobeItem) -> Unit,
    onSelectAvatar: (AvatarType) -> Unit,
    onFinished: () -> Unit = {},
    ttsEngine: TextToSpeechEngine = NoOpTextToSpeechEngine,
    modifier: Modifier = Modifier
) {
    val steps = remember(outcome) { buildCelebrationSteps(outcome) }
    var index by remember(outcome) { mutableIntStateOf(0) }
    fun advance(from: Int) {
        if (index == from) index = from + 1
    }

    val confetti = rememberConfettiState()
    val haptics = rememberLixoHaptics()
    var hostSize by remember { mutableStateOf(IntSize.Zero) }

    PlatformBackHandler(enabled = steps.getOrNull(index) != null) { advance(index) }

    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(index, steps) {
        if (index >= steps.size) currentOnFinished()
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { hostSize = it }
    ) {
        key(index) {
            when (val step = steps.getOrNull(index)) {
                is CelebrationStep.Stars -> {
                    val i = index
                    LaunchedEffectStars(starsEarned, hostSize, confetti, haptics) { advance(i) }
                    if (steps.size > 1) {
                        // More steps follow Stars (chest/equip/avatar): block input so the
                        // caller's own UI underneath (e.g. the "Fortsätt" button) can't be
                        // tapped while this transient, input-transparent stage is showing.
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { }
                        )
                    }
                }

                is CelebrationStep.Chest -> {
                    val i = index
                    ChestRevealStage(
                        item = step.item,
                        source = step.source,
                        labelDetailSv = step.labelDetailSv,
                        onOpened = {
                            confetti.burst(
                                Offset(hostSize.width / 2f, hostSize.height / 2f),
                                confettiCountFor(step.item.rarity),
                                confettiPaletteFor(step.item.rarity)
                            )
                            haptics.heavy(SfxCue.ITEM_REVEAL)
                        },
                        onFinished = { advance(i) }
                    )
                }

                is CelebrationStep.EquipPrompt -> {
                    val i = index
                    ItemUnlockedDialog(
                        item = step.item,
                        onEquipNow = { onEquipItem(step.item) },
                        onClose = { advance(i) }
                    )
                }

                is CelebrationStep.AvatarPrompt -> {
                    val i = index
                    AvatarUnlockedDialog(
                        avatarType = step.avatarType,
                        onSwitchNow = { onSelectAvatar(step.avatarType) },
                        onClose = { advance(i) }
                    )
                }

                is CelebrationStep.NextChallenge -> {
                    val i = index
                    NextChallengeDialog(
                        next = step.next,
                        ttsEngine = ttsEngine,
                        onClose = { advance(i) }
                    )
                }

                null -> Unit
            }
        }

        ConfettiOverlay(confetti, Modifier.fillMaxSize())
    }
}

@Composable
private fun LaunchedEffectStars(
    starsEarned: Int,
    hostSize: IntSize,
    confetti: ConfettiState,
    haptics: LixoHaptics,
    onDone: () -> Unit
) {
    // hostSize is IntSize.Zero until onSizeChanged fires after first composition; capture it via
    // rememberUpdatedState so the LaunchedEffect(Unit) below always reads the latest value (and,
    // if it's still zero when the burst is due, waits for the real measurement) instead of the
    // zero value it was started with.
    val size by rememberUpdatedState(hostSize)
    LaunchedEffect(Unit) {
        delay(STAR_POP_START_DELAY_MS + starsEarned * STAR_POP_STAGGER_MS)
        val burstSize = if (size != IntSize.Zero) size else snapshotFlow { size }.first { it != IntSize.Zero }
        confetti.burst(
            Offset(burstSize.width / 2f, burstSize.height * 0.28f),
            confettiCountFor(null),
            confettiPaletteFor(null)
        )
        haptics.success(SfxCue.CONFETTI)
        delay(1400)
        onDone()
    }
}
