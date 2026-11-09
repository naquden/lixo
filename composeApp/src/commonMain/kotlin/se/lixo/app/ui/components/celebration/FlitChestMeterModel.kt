package se.lixo.app.ui.components.celebration

import se.lixo.app.ui.theme.LixoStrings

/**
 * Fill fraction (0f..1f) of the Flitkista chest meter for the given half-point counter.
 * [thresholdHalf] is the run's `content.dropRules.chestCostHalfPoints`
 * (doc/content/content-pack.md § Drop rules).
 */
fun chestFillFraction(halfPoints: Int, thresholdHalf: Int): Float {
    val threshold = thresholdHalf.coerceAtLeast(1)
    return halfPoints.coerceIn(0, threshold).toFloat() / threshold
}

/**
 * Describes how the meter should animate across a single level-completion run: from [from] to
 * [to], optionally [wrapped] around a full chest in between.
 */
data class ChestFillAnimation(val from: Float, val to: Float, val wrapped: Boolean)

/**
 * Builds the [ChestFillAnimation] for one run. The engine resets the counter even when
 * `rollChest` returns null because the candidate pool is empty, so the arithmetic (not just
 * `chestAwarded`) is the source of truth for whether the meter wrapped around a full chest.
 * [thresholdHalf] is the run's `content.dropRules.chestCostHalfPoints`
 * (doc/content/content-pack.md § Drop rules).
 */
fun chestFillAnimation(updatedHalfPoints: Int, awardedHalf: Int, chestAwarded: Boolean, thresholdHalf: Int): ChestFillAnimation {
    val threshold = thresholdHalf.coerceAtLeast(1)
    val wrapped = chestAwarded || updatedHalfPoints < awardedHalf
    val previous = updatedHalfPoints - awardedHalf + (if (wrapped) threshold else 0)
    return ChestFillAnimation(
        from = chestFillFraction(previous, thresholdHalf),
        to = chestFillFraction(updatedHalfPoints, thresholdHalf),
        wrapped = wrapped
    )
}

/** Word-only (no numbers) description of how full the chest is, for accessibility. */
fun chestFillLevelDescription(fraction: Float): String = when {
    fraction <= 0f -> LixoStrings.CHEST_METER_EMPTY
    fraction < 0.4f -> LixoStrings.CHEST_METER_LOW
    fraction < 0.8f -> LixoStrings.CHEST_METER_HALF
    else -> LixoStrings.CHEST_METER_ALMOST_FULL
}

/**
 * Delay before the meter's level-complete-card animation starts, matching the moment
 * [AnimatedStarRow]'s stars finish popping in and burst their confetti sparkle.
 */
fun chestMeterStartDelayMillis(starsEarned: Int): Long =
    STAR_POP_START_DELAY_MS + starsEarned.coerceIn(0, 3) * STAR_POP_STAGGER_MS
