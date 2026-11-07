package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoColors

private val HoloCyan = Color(0xFF5CE1FF)
private val HoloViolet = Color(0xFFB388FF)
private val HoloPink = Color(0xFFFF8AD8)

/**
 * Small holographic chest that fills up with gold as `halfPoints` approach the Flitkista
 * threshold. In static mode (no [animation]) it simply reflects [halfPoints]; pass an
 * [animation] to play the fill transition for one level-completion run, including the
 * flash-and-drain sequence when the chest wraps around (overflowed into a new chest).
 */
@Composable
fun FlitChestMeter(
    halfPoints: Int,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    animation: ChestFillAnimation? = null,
    startDelayMillis: Long = 0L,
    onClick: (() -> Unit)? = null
) {
    val fill = remember(animation) { Animatable(animation?.from ?: 0f) }
    val flash = remember { Animatable(0f) }
    val haptics = rememberLixoHaptics()
    val fraction = if (animation == null) chestFillFraction(halfPoints) else fill.value

    LaunchedEffect(animation) {
        val anim = animation ?: return@LaunchedEffect
        delay(startDelayMillis)
        if (anim.wrapped) {
            fill.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            haptics.tick()
            launch {
                flash.animateTo(1f, tween(120))
                flash.animateTo(0f, tween(380))
            }
            delay(150)
            fill.animateTo(0f, tween(250))
            fill.animateTo(anim.to, tween(350, easing = LinearOutSlowInEasing))
        } else if (anim.from != anim.to) {
            fill.animateTo(anim.to, tween(900, easing = FastOutSlowInEasing))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "flitChest")
    val shimmer = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flitChestShimmer"
    )
    val wavePhase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flitChestWave"
    )
    val pulse = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flitChestPulse"
    )

    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (onClick != null) {
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = chestFillLevelDescription(chestFillFraction(halfPoints))
                if (onClick != null) role = Role.Button
            }
            .graphicsLayer {
                val s = (if (fraction >= 0.8f) pulse.value else 1f) * (1f + 0.15f * flash.value)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
    ) {
        val w = this.size.width
        val h = this.size.height

        // a. Glow behind.
        val glowAlpha = (0.12f + 0.5f * fraction + 0.5f * flash.value).coerceAtMost(1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LixoColors.Gold.copy(alpha = glowAlpha), Color.Transparent),
                center = Offset(w / 2f, h * 0.6f),
                radius = w * 0.65f
            ),
            radius = w * 0.65f,
            center = Offset(w / 2f, h * 0.6f)
        )

        val silhouette = chestSilhouettePath(this.size, w * 0.14f)

        // c. Glass.
        drawPath(
            path = silhouette,
            brush = Brush.linearGradient(
                colors = listOf(HoloCyan, HoloViolet, HoloPink).map { it.copy(alpha = 0.35f) },
                start = Offset.Zero,
                end = Offset(w, h)
            )
        )

        // d. Fill + shimmer, clipped to the chest silhouette.
        clipPath(silhouette) {
            if (fraction > 0f) {
                val top = h - fraction * (h - h * CHEST_LID_TOP_FRACTION)
                val amp = if (fraction < 1f) h * 0.03f else 0f
                val wavePath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, top)
                    val steps = 12
                    for (i in 0..steps) {
                        val x = w * i / steps
                        val y = top + amp * sin(wavePhase.value + x / w * (2 * PI).toFloat())
                        lineTo(x, y)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wavePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(LixoColors.Gold, LixoColors.GoldLip),
                        startY = top,
                        endY = h
                    )
                )
            }

            val cx = -0.5f * w + shimmer.value * 2f * w
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    start = Offset(cx - w * 0.3f, 0f),
                    end = Offset(cx + w * 0.3f, h)
                ),
                size = this.size
            )
        }

        // e. Lid seam.
        val seamY = h * CHEST_BODY_TOP_FRACTION
        drawLine(
            color = Color.White.copy(alpha = 0.7f),
            start = Offset(0f, seamY),
            end = Offset(w, seamY),
            strokeWidth = w * 0.035f
        )

        // f. Lock.
        val lockW = w * 0.14f
        val lockH = h * 0.1f
        drawRoundRect(
            color = HoloViolet,
            topLeft = Offset(w / 2f - lockW / 2f, seamY - lockH / 2f),
            size = Size(lockW, lockH),
            cornerRadius = CornerRadius(4f, 4f),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // g. Outline.
        drawPath(
            path = silhouette,
            brush = Brush.linearGradient(colors = listOf(LixoColors.Blue, LixoColors.PurpleLip)),
            style = Stroke(width = max(1.5.dp.toPx(), w * 0.045f))
        )
        drawPath(
            path = silhouette,
            color = Color.White.copy(alpha = 0.8f),
            style = Stroke(width = 1.dp.toPx() * 0.6f)
        )
    }
}
