package se.lixo.app

import androidx.compose.ui.graphics.Color
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.components.celebration.ConfettiParticle
import se.lixo.app.ui.components.celebration.ConfettiShape
import se.lixo.app.ui.components.celebration.chestTeaserSpecFor
import se.lixo.app.ui.components.celebration.confettiCountFor
import se.lixo.app.ui.components.celebration.lightBurstSpecFor
import se.lixo.app.ui.components.celebration.spawnConfettiBurst
import se.lixo.app.ui.components.celebration.stepParticles

class ConfettiPhysicsTest {

    private fun particle(
        x: Float = 0f,
        y: Float = 0f,
        vx: Float = 0f,
        vy: Float = 0f,
        ageSec: Float = 0f,
        lifeSec: Float = 2f
    ) = ConfettiParticle(
        x = x,
        y = y,
        vx = vx,
        vy = vy,
        rotationDeg = 0f,
        spinDegPerSec = 0f,
        widthPx = 10f,
        heightPx = 10f,
        color = Color.Red,
        shape = ConfettiShape.RECT,
        ageSec = ageSec,
        lifeSec = lifeSec
    )

    @Test
    fun gravity_increasesVelocityAndY() {
        val p = particle(vy = 0f)
        val result = stepParticles(listOf(p), 0.016f, gravityPxPerSec2 = 1000f, dragPerSec = 0f)
        val updated = result.single()
        assertTrue(updated.vy in 15f..17f, "expected vy near 16, got ${updated.vy}")
        assertTrue(updated.y > 0f)
    }

    @Test
    fun dt_isClampedToMax() {
        val p = particle(vy = 0f)
        val clamped = stepParticles(listOf(p), 0.05f, gravityPxPerSec2 = 1000f, dragPerSec = 0f).single()
        val overshoot = stepParticles(listOf(particle(vy = 0f)), 1f, gravityPxPerSec2 = 1000f, dragPerSec = 0f).single()
        assertTrue(kotlin.math.abs(clamped.y - overshoot.y) < 0.001f)
        assertTrue(kotlin.math.abs(clamped.vy - overshoot.vy) < 0.001f)
    }

    @Test
    fun age_expiresNearEndOfLife() {
        val p = particle(ageSec = 1.999f, lifeSec = 2f)
        val result = stepParticles(listOf(p), 0.016f, gravityPxPerSec2 = 1000f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun floorRemoval_removesParticlesPastMaxY() {
        val p = particle(y = 1000f, vy = 0f)
        val result = stepParticles(listOf(p), 0.016f, gravityPxPerSec2 = 0f, dragPerSec = 0f, maxY = 500f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun alpha_fadesToZeroAtEndOfLife() {
        val start = particle(ageSec = 0f, lifeSec = 2f)
        assertEquals(1f, start.alpha)
        val end = particle(ageSec = 2f, lifeSec = 2f)
        assertEquals(0f, end.alpha)
    }

    @Test
    fun spawnCount_matchesRequestedCount() {
        val palette = listOf(Color.Red, Color.Blue)
        val burst = spawnConfettiBurst(0f, 0f, 50, palette, 1f, Random(42))
        assertEquals(50, burst.size)
        assertTrue(burst.all { it.vy < 0f })
    }

    @Test
    fun confettiCount_increasesWithRarity() {
        val counts = Rarity.entries.map { confettiCountFor(it) }
        for (i in 1 until counts.size) {
            assertTrue(counts[i] > counts[i - 1], "expected increasing counts, got $counts")
        }
        assertTrue(confettiCountFor(Rarity.LEGENDARY) > confettiCountFor(Rarity.COMMON))
    }

    @Test
    fun lightBurstSpec_scalesWithRarity() {
        val common = lightBurstSpecFor(Rarity.COMMON)
        val legendary = lightBurstSpecFor(Rarity.LEGENDARY)
        assertTrue(legendary.rayCount > common.rayCount)
        assertTrue(legendary.goldGlow)
        for (r in Rarity.entries) {
            if (r != Rarity.LEGENDARY) {
                assertTrue(!lightBurstSpecFor(r).goldGlow)
            }
        }
    }

    @Test
    fun chestTeaserSpec_escalatesWithRarity() {
        val specs = Rarity.entries.map { chestTeaserSpecFor(it) }

        for (i in 1 until specs.size) {
            assertTrue(
                specs[i].glowAlpha > specs[i - 1].glowAlpha,
                "expected strictly increasing glowAlpha, got ${specs.map { it.glowAlpha }}"
            )
            assertTrue(
                specs[i].ringAlpha > specs[i - 1].ringAlpha,
                "expected strictly increasing ringAlpha, got ${specs.map { it.ringAlpha }}"
            )
            assertTrue(
                specs[i].pulseAmplitude >= specs[i - 1].pulseAmplitude,
                "pulseAmplitude should never decrease, got ${specs.map { it.pulseAmplitude }}"
            )
            assertTrue(
                specs[i].ringWidthDp >= specs[i - 1].ringWidthDp,
                "ringWidthDp should never decrease, got ${specs.map { it.ringWidthDp }}"
            )
            assertTrue(
                specs[i].sparkleCount >= specs[i - 1].sparkleCount,
                "sparkleCount should never decrease, got ${specs.map { it.sparkleCount }}"
            )
            assertTrue(
                specs[i].pulsePeriodMs <= specs[i - 1].pulsePeriodMs,
                "pulsePeriodMs should never increase, got ${specs.map { it.pulsePeriodMs }}"
            )
        }

        val common = chestTeaserSpecFor(Rarity.COMMON)
        assertEquals(0f, common.glowAlpha)
        assertEquals(0f, common.ringAlpha)
        assertEquals(0, common.sparkleCount)

        val rare = chestTeaserSpecFor(Rarity.RARE)
        val epic = chestTeaserSpecFor(Rarity.EPIC)
        val legendary = chestTeaserSpecFor(Rarity.LEGENDARY)
        assertEquals(0, rare.sparkleCount)
        assertTrue(epic.sparkleCount > 0)
        assertTrue(legendary.sparkleCount > epic.sparkleCount)

        for (r in Rarity.entries) {
            val spec = chestTeaserSpecFor(r)
            if (r == Rarity.LEGENDARY) {
                assertTrue(spec.goldGlow)
                assertTrue(spec.shakeAmplitudeDp > 0f)
                assertTrue(
                    spec.openDurationScale in 1.25f..1.35f,
                    "expected LEGENDARY openDurationScale in 1.25..1.35, got ${spec.openDurationScale}"
                )
            } else {
                assertTrue(!spec.goldGlow)
                assertEquals(0f, spec.shakeAmplitudeDp)
                assertEquals(1f, spec.openDurationScale)
            }
        }
    }
}
