package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.ui.components.celebration.chestFillAnimation
import se.lixo.app.ui.components.celebration.chestFillFraction
import se.lixo.app.ui.components.celebration.chestFillLevelDescription
import se.lixo.app.ui.components.celebration.chestMeterStartDelayMillis
import se.lixo.app.ui.theme.LixoStrings

class FlitChestMeterModelTest {

    @Test
    fun chestFillFraction_clampsAndScalesToThreshold() {
        assertEquals(0f, chestFillFraction(0), 1e-6f)
        assertEquals(0.5f, chestFillFraction(10), 1e-6f)
        assertEquals(1f, chestFillFraction(20), 1e-6f)
        assertEquals(1f, chestFillFraction(25), 1e-6f)
        assertEquals(0f, chestFillFraction(-3), 1e-6f)
    }

    @Test
    fun chestFillAnimation_plainGain() {
        val anim = chestFillAnimation(updatedHalfPoints = 6, awardedHalf = 2, chestAwarded = false)
        assertEquals(0.2f, anim.from, 1e-6f)
        assertEquals(0.3f, anim.to, 1e-6f)
        assertEquals(false, anim.wrapped)
    }

    @Test
    fun chestFillAnimation_wrappedByChestAwarded() {
        val anim = chestFillAnimation(updatedHalfPoints = 1, awardedHalf = 2, chestAwarded = true)
        assertEquals(0.95f, anim.from, 1e-6f)
        assertEquals(0.05f, anim.to, 1e-6f)
        assertEquals(true, anim.wrapped)
    }

    @Test
    fun chestFillAnimation_wrappedByArithmeticEvenWithoutChest() {
        // rollChest returned null (empty candidate pool), but the counter still wrapped.
        val anim = chestFillAnimation(updatedHalfPoints = 1, awardedHalf = 2, chestAwarded = false)
        assertEquals(true, anim.wrapped)
        assertEquals(0.95f, anim.from, 1e-6f)
    }

    @Test
    fun chestFillAnimation_noGainStaysFlat() {
        val anim = chestFillAnimation(updatedHalfPoints = 4, awardedHalf = 0, chestAwarded = false)
        assertEquals(anim.from, anim.to, 1e-6f)
        assertEquals(0.2f, anim.from, 1e-6f)
        assertEquals(false, anim.wrapped)
    }

    @Test
    fun chestFillLevelDescription_buckets() {
        assertEquals(LixoStrings.CHEST_METER_EMPTY, chestFillLevelDescription(0f))
        assertEquals(LixoStrings.CHEST_METER_LOW, chestFillLevelDescription(0.3f))
        assertEquals(LixoStrings.CHEST_METER_HALF, chestFillLevelDescription(0.6f))
        assertEquals(LixoStrings.CHEST_METER_ALMOST_FULL, chestFillLevelDescription(0.9f))
    }

    @Test
    fun chestMeterStartDelayMillis_matchesStarPopTiming() {
        assertEquals(350L + 3 * 250L, chestMeterStartDelayMillis(3))
    }
}
