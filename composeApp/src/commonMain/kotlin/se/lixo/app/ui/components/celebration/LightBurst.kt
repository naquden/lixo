package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.theme.LixoColors

data class LightBurstSpec(
    val rayCount: Int,
    val rayLengthFraction: Float,
    val glowAlpha: Float,
    val goldGlow: Boolean
)

fun lightBurstSpecFor(rarity: Rarity): LightBurstSpec = when (rarity) {
    Rarity.COMMON -> LightBurstSpec(rayCount = 10, rayLengthFraction = 0.70f, glowAlpha = 0.35f, goldGlow = false)
    Rarity.UNCOMMON -> LightBurstSpec(rayCount = 12, rayLengthFraction = 0.78f, glowAlpha = 0.40f, goldGlow = false)
    Rarity.RARE -> LightBurstSpec(rayCount = 14, rayLengthFraction = 0.85f, glowAlpha = 0.45f, goldGlow = false)
    Rarity.EPIC -> LightBurstSpec(rayCount = 16, rayLengthFraction = 0.92f, glowAlpha = 0.55f, goldGlow = false)
    Rarity.LEGENDARY -> LightBurstSpec(rayCount = 20, rayLengthFraction = 1.0f, glowAlpha = 0.7f, goldGlow = true)
}

/** Rotating radial rays plus a radial glow behind a revealed item, tinted by rarity. */
@Composable
fun LightBurst(rarity: Rarity, modifier: Modifier = Modifier) {
    val spec = remember(rarity) { lightBurstSpecFor(rarity) }
    val rarityColor = LixoColors.rarityColor(rarity)

    val rotation: State<Float> = rememberInfiniteTransition(label = "lightBurstRotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lightBurstRotation"
    )

    val fade = remember(rarity) { Animatable(0f) }
    LaunchedEffect(rarity) {
        fade.animateTo(1f, tween(durationMillis = 300))
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f
        val glowColor = if (spec.goldGlow) LixoColors.Gold else rarityColor
        val glowAlpha = spec.glowAlpha * fade.value

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                center = center,
                radius = maxRadius
            )
        )

        rotate(rotation.value, pivot = center) {
            val rayLength = maxRadius * spec.rayLengthFraction
            val halfAngleRad = (kotlin.math.PI / spec.rayCount).toFloat() / 2f
            for (i in 0 until spec.rayCount) {
                val angle = (360f / spec.rayCount) * i * (kotlin.math.PI / 180f).toFloat()
                val alpha = (if (i % 2 == 0) 0.35f else 0.18f) * fade.value
                val tip = Offset(
                    center.x + kotlin.math.cos(angle) * rayLength,
                    center.y + kotlin.math.sin(angle) * rayLength
                )
                val leftAngle = angle - halfAngleRad
                val rightAngle = angle + halfAngleRad
                val left = Offset(
                    center.x + kotlin.math.cos(leftAngle) * rayLength,
                    center.y + kotlin.math.sin(leftAngle) * rayLength
                )
                val right = Offset(
                    center.x + kotlin.math.cos(rightAngle) * rayLength,
                    center.y + kotlin.math.sin(rightAngle) * rayLength
                )
                val path = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(left.x, left.y)
                    lineTo(tip.x, tip.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(path = path, color = (if (spec.goldGlow) LixoColors.Gold else rarityColor).copy(alpha = alpha))
            }
        }

        if (spec.goldGlow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(LixoColors.Gold.copy(alpha = 0.5f * fade.value), Color.Transparent),
                    center = center,
                    radius = maxRadius * 0.6f
                ),
                radius = maxRadius * 0.6f,
                center = center
            )
        }
    }
}
