package se.lixo.app

import androidx.compose.ui.graphics.Color
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.components.celebration.ConfettiParticle
import se.lixo.app.ui.components.celebration.ConfettiShape
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
}
