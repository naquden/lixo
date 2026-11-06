package se.lixo.app.ui.components.celebration

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.random.Random
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.theme.LixoColors

enum class ConfettiShape { RECT, CIRCLE }

data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val rotationDeg: Float,
    val spinDegPerSec: Float,
    val widthPx: Float,
    val heightPx: Float,
    val color: Color,
    val shape: ConfettiShape,
    val ageSec: Float,
    val lifeSec: Float
) {
    /** Fades linearly to 0 over the last 30% of its life. */
    val alpha: Float
        get() {
            val fadeStart = lifeSec * 0.7f
            if (ageSec <= fadeStart) return 1f
            val fadeDuration = (lifeSec - fadeStart).coerceAtLeast(0.0001f)
            return (1f - (ageSec - fadeStart) / fadeDuration).coerceIn(0f, 1f)
        }
}

object ConfettiPhysics {
    const val GRAVITY_DP_PER_SEC2 = 1400f
    const val DRAG_PER_SEC = 0.9f
    const val MAX_DT_SEC = 0.05f
}

/** Pure physics step: gravity, drag, spin, aging, and expiry. Safe to unit test directly. */
fun stepParticles(
    particles: List<ConfettiParticle>,
    dtSec: Float,
    gravityPxPerSec2: Float,
    dragPerSec: Float = ConfettiPhysics.DRAG_PER_SEC,
    maxY: Float = Float.POSITIVE_INFINITY
): List<ConfettiParticle> {
    val dt = dtSec.coerceIn(0f, ConfettiPhysics.MAX_DT_SEC)
    val damp = (1f - dragPerSec * dt).coerceAtLeast(0f)
    return particles.mapNotNull { p ->
        val newVx = p.vx * damp
        val newVy = (p.vy + gravityPxPerSec2 * dt) * damp
        val newX = p.x + newVx * dt
        val newY = p.y + newVy * dt
        val newRotation = p.rotationDeg + p.spinDegPerSec * dt
        val newAge = p.ageSec + dt
        if (newAge >= p.lifeSec || newY > maxY) {
            null
        } else {
            p.copy(x = newX, y = newY, vx = newVx, vy = newVy, rotationDeg = newRotation, ageSec = newAge)
        }
    }
}

fun spawnConfettiBurst(
    originX: Float,
    originY: Float,
    count: Int,
    palette: List<Color>,
    density: Float,
    random: Random = Random.Default
): List<ConfettiParticle> {
    return List(count) {
        val angleDeg = -150f + random.nextFloat() * 120f // [-150, -30]
        val angleRad = angleDeg * (kotlin.math.PI / 180.0).toFloat()
        val speed = (500f + random.nextFloat() * 600f) * density
        val vx = kotlin.math.cos(angleRad) * speed
        val vy = kotlin.math.sin(angleRad) * speed
        val spin = (180f + random.nextFloat() * 540f) * if (random.nextBoolean()) 1f else -1f
        val widthPx = (6f + random.nextFloat() * 6f) * density
        val isRect = random.nextFloat() < 0.7f
        val heightPx = if (isRect) widthPx * 0.55f else widthPx
        val life = 1.8f + random.nextFloat() * 1.0f
        ConfettiParticle(
            x = originX,
            y = originY,
            vx = vx,
            vy = vy,
            rotationDeg = random.nextFloat() * 360f,
            spinDegPerSec = spin,
            widthPx = widthPx,
            heightPx = heightPx,
            color = palette[random.nextInt(palette.size)],
            shape = if (isRect) ConfettiShape.RECT else ConfettiShape.CIRCLE,
            ageSec = 0f,
            lifeSec = life
        )
    }
}

fun confettiCountFor(rarity: Rarity?): Int = when (rarity) {
    null -> 90
    Rarity.COMMON -> 40
    Rarity.UNCOMMON -> 55
    Rarity.RARE -> 75
    Rarity.EPIC -> 100
    Rarity.LEGENDARY -> 140
}

fun confettiPaletteFor(rarity: Rarity?): List<Color> {
    if (rarity == null) {
        return listOf(LixoColors.Gold, LixoColors.Green, LixoColors.Blue, LixoColors.Red, LixoColors.Purple, LixoColors.Orange)
    }
    val base = listOf(
        LixoColors.rarityColor(rarity),
        Color(rarity.hexColorAccent),
        LixoColors.Gold,
        Color.White
    )
    return if (rarity == Rarity.LEGENDARY) base + listOf(LixoColors.Gold, LixoColors.Gold) else base
}

@Stable
class ConfettiState(private val density: Float) {
    var particles: List<ConfettiParticle> by mutableStateOf(emptyList())
        internal set

    internal var floorY: Float = Float.POSITIVE_INFINITY

    val hasParticles: Boolean by derivedStateOf { particles.isNotEmpty() }

    fun burst(origin: Offset, count: Int, palette: List<Color>) {
        particles = particles + spawnConfettiBurst(origin.x, origin.y, count, palette, density)
    }

    internal fun step(dt: Float) {
        particles = stepParticles(particles, dt, ConfettiPhysics.GRAVITY_DP_PER_SEC2 * density, maxY = floorY)
    }
}

@Composable
fun rememberConfettiState(): ConfettiState {
    val density = LocalDensity.current.density
    return remember(density) { ConfettiState(density) }
}

/**
 * Touch-transparent confetti canvas. Runs a [withFrameNanos] loop only while particles exist, and
 * reads [ConfettiState.particles] only inside the draw lambda so the overlay never recomposes.
 */
@Composable
fun ConfettiOverlay(state: ConfettiState, modifier: Modifier = Modifier) {
    LaunchedEffect(state, state.hasParticles) {
        if (!state.hasParticles) return@LaunchedEffect
        var last = -1L
        while (state.particles.isNotEmpty()) {
            withFrameNanos { now ->
                if (last >= 0) {
                    state.step((now - last) / 1_000_000_000f)
                }
                last = now
            }
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { state.floorY = it.height + 40f }
    ) {
        state.particles.forEach { p ->
            rotate(p.rotationDeg, Offset(p.x, p.y)) {
                val color = p.color.copy(alpha = p.alpha)
                when (p.shape) {
                    ConfettiShape.RECT -> drawRect(
                        color = color,
                        topLeft = Offset(p.x - p.widthPx / 2f, p.y - p.heightPx / 2f),
                        size = androidx.compose.ui.geometry.Size(p.widthPx, p.heightPx)
                    )
                    ConfettiShape.CIRCLE -> drawCircle(
                        color = color,
                        radius = p.widthPx / 2f,
                        center = Offset(p.x, p.y)
                    )
                }
            }
        }
    }
}
