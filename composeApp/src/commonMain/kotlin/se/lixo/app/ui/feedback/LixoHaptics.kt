package se.lixo.app.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Celebration / feedback sound cues (project.md "Känsla & feedback"). Sound assets land later;
 * for now every cue is wired to a haptic and a no-op [CelebrationSfx] hook so adding real audio
 * later touches only the [CelebrationSfx] implementation, not the call sites.
 */
enum class SfxCue {
    BUTTON_TAP,
    STAR_POP,
    CONFETTI,
    CHEST_WIGGLE,
    CHEST_OPEN,
    ITEM_REVEAL,
    EQUIP_CONFIRM,
    AVATAR_SWITCH,
    ANSWER_CORRECT,
    ANSWER_WRONG,
    /** doc/plans/030 §7 "kompis-kistan"; no dedicated sound asset yet, falls back to ITEM_REVEAL. */
    FRIEND_ADDED
}

/** Plays a sound effect for a given [SfxCue]. Implemented later; no-op for now. */
fun interface CelebrationSfx {
    fun play(cue: SfxCue)
}

val NoOpCelebrationSfx = CelebrationSfx { }

val LocalCelebrationSfx = staticCompositionLocalOf<CelebrationSfx> { NoOpCelebrationSfx }

/**
 * Wraps Compose's cross-platform [HapticFeedback] with the semantic cues the celebration system
 * needs, and plays the matching [CelebrationSfx] cue (no-op today, real sound later) at the same
 * call sites as the haptic.
 */
@Stable
class LixoHaptics(
    private val haptic: HapticFeedback,
    private val sfx: CelebrationSfx
) {
    fun tick(cue: SfxCue? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        cue?.let(sfx::play)
    }

    fun success(cue: SfxCue? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        cue?.let(sfx::play)
    }

    fun heavy(cue: SfxCue? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        cue?.let(sfx::play)
    }

    fun reject(cue: SfxCue? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        cue?.let(sfx::play)
    }
}

/**
 * doc/plans/030 §7: when the system "remove animations" setting is on (Android: `animator
 * duration scale == 0`), celebration stages like [se.lixo.app.ui.components.celebration.FriendRevealStage]
 * skip confetti/light-burst and just fade in. Exposed as a `CompositionLocal` (default `false`)
 * rather than threaded through every composable's parameter list, so instrumented tests can force
 * it with `CompositionLocalProvider(LocalReducedMotion provides true) { ... }`.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberLixoHaptics(): LixoHaptics {
    val haptic = LocalHapticFeedback.current
    val sfx = LocalCelebrationSfx.current
    return remember(haptic, sfx) { LixoHaptics(haptic, sfx) }
}
