package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons

const val STAR_POP_START_DELAY_MS = 350L
const val STAR_POP_STAGGER_MS = 250L

/**
 * 3 stars that pop in one by one with an overshoot spring, a sparkle burst, and a haptic tick
 * each. Stars beyond [starsEarned] stay gray and static.
 */
@Composable
fun AnimatedStarRow(
    starsEarned: Int,
    modifier: Modifier = Modifier,
    starSize: androidx.compose.ui.unit.Dp = 44.dp
) {
    val haptics = rememberLixoHaptics()
    val scales = remember(starsEarned) {
        List(3) { i -> Animatable(if (i < starsEarned) 0f else 1f) }
    }
    val sparkles = remember(starsEarned) {
        List(3) { Animatable(0f) }
    }

    LaunchedEffect(starsEarned) {
        delay(STAR_POP_START_DELAY_MS)
        for (i in 0 until starsEarned.coerceIn(0, 3)) {
            launch {
                scales[i].animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.35f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                sparkles[i].animateTo(1f, tween(400))
            }
            haptics.tick(SfxCue.STAR_POP)
            delay(STAR_POP_STAGGER_MS)
        }
    }

    Row(modifier = modifier) {
        for (i in 0 until 3) {
            Box(
                modifier = Modifier
                    .size(starSize)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(starSize)) {
                    val progress = sparkles[i].value
                    if (progress > 0f && progress < 1f) {
                        val center = Offset(this.size.width / 2f, this.size.height / 2f)
                        val radius = this.size.minDimension * (0.4f + 0.3f * progress)
                        val alpha = 1f - progress
                        for (ray in 0 until 6) {
                            val angle = (ray * 60f) * (kotlin.math.PI / 180f).toFloat()
                            val start = Offset(
                                center.x + kotlin.math.cos(angle) * radius * 0.5f,
                                center.y + kotlin.math.sin(angle) * radius * 0.5f
                            )
                            val end = Offset(
                                center.x + kotlin.math.cos(angle) * radius,
                                center.y + kotlin.math.sin(angle) * radius
                            )
                            drawLine(
                                color = LixoColors.Gold.copy(alpha = alpha),
                                start = start,
                                end = end,
                                strokeWidth = 3f
                            )
                        }
                    }
                }
                LixoIcon(
                    icon = LixoIcons.Star,
                    tint = if (i < starsEarned) LixoColors.Gold else LixoColors.BorderDarker,
                    size = starSize,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scales[i].value
                        scaleY = scales[i].value
                    }
                )
            }
        }
    }
}

/** Counts up to [target] starting [startDelayMillis] after composition, over 600ms. */
@Composable
fun CountUpText(
    target: Int,
    format: (Int) -> String,
    style: TextStyle,
    color: Color,
    startDelayMillis: Long = STAR_POP_START_DELAY_MS,
    fontWeight: FontWeight? = FontWeight.Bold
) {
    var shown by remember(target) { mutableIntStateOf(0) }

    LaunchedEffect(target) {
        delay(startDelayMillis)
        val anim = Animatable(0f)
        anim.animateTo(target.toFloat(), tween(600)) {
            shown = value.roundToInt()
        }
        shown = target
    }

    Text(
        text = format(shown),
        style = style,
        color = color,
        fontWeight = fontWeight
    )
}
