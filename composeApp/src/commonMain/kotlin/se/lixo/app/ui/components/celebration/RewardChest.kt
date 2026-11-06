package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

enum class ChestState { WAITING, OPENING, OPENED }

internal val CelebrationScrim = Color(0xB3000000)

/**
 * Chest body/lid/lock drawn entirely on [Canvas]. [WAITING] breathes, wiggles and glows;
 * [OPENING] squashes and sends the lid flying; [OPENED] is static (the item reveal happens above
 * it, see [ChestRevealStage]).
 */
@Composable
fun RewardChest(
    state: ChestState,
    onOpenAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberLixoHaptics()
    val density = LocalDensity.current

    val glow = rememberInfiniteTransition(label = "chestGlow").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chestGlow"
    )
    val breathe = rememberInfiniteTransition(label = "chestBreathe").animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chestBreathe"
    )

    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(state) {
        while (state == ChestState.WAITING) {
            delay(1300)
            haptics.tick(SfxCue.CHEST_WIGGLE)
            wiggle.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 500
                    8f at 70
                    -8f at 160
                    6f at 250
                    -6f at 330
                    3f at 400
                    0f at 500
                }
            )
        }
    }

    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val lidDy = remember { Animatable(0f) }
    val lidRot = remember { Animatable(0f) }
    val lidAlpha = remember { Animatable(1f) }

    LaunchedEffect(state) {
        if (state == ChestState.OPENING) {
            haptics.heavy(SfxCue.CHEST_OPEN)
            val squashJob = launch {
                squashX.animateTo(1.15f, tween(90))
                squashY.animateTo(0.85f, tween(90))
                squashX.animateTo(0.92f, tween(120))
                squashY.animateTo(1.12f, tween(120))
                launch { squashX.animateTo(1f, spring()) }
                squashY.animateTo(1f, spring())
            }
            val lidJob = launch {
                val lidTargetDy = with(density) { -260.dp.toPx() }
                launch { lidDy.animateTo(lidTargetDy, tween(450)) }
                launch { lidRot.animateTo(-35f, tween(450)) }
                lidAlpha.animateTo(0f, tween(450))
            }
            squashJob.join()
            lidJob.join()
            onOpenAnimationFinished()
        }
    }

    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = wiggle.value
            scaleX = breathe.value * squashX.value
            scaleY = breathe.value * squashY.value
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    ) {
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.42f
        val lidBottom = bodyTop
        val lidTop = h * 0.06f

        // Pulsing gold glow behind the chest.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LixoColors.Gold.copy(alpha = glow.value), Color.Transparent),
                center = Offset(w / 2f, h * 0.6f),
                radius = w * 0.8f
            ),
            radius = w * 0.8f,
            center = Offset(w / 2f, h * 0.6f)
        )

        val woodColor = Color(0xFF8B5A2B)
        val plankColor = Color(0xFF6B4220)

        // Body
        val bodyRect = androidx.compose.ui.geometry.Rect(0f, bodyTop, w, h)
        drawRoundRect(
            color = woodColor,
            topLeft = bodyRect.topLeft,
            size = bodyRect.size,
            cornerRadius = CornerRadius(14f, 14f)
        )
        val plankCount = 4
        for (i in 1 until plankCount) {
            val x = w * i / plankCount
            drawLine(
                color = plankColor,
                start = Offset(x, bodyTop + 4f),
                end = Offset(x, h - 4f),
                strokeWidth = 2f
            )
        }
        // Gold vertical bands
        val bandWidth = w * 0.08f
        drawRect(color = LixoColors.Gold, topLeft = Offset(w * 0.12f, bodyTop), size = androidx.compose.ui.geometry.Size(bandWidth, h - bodyTop))
        drawRect(color = LixoColors.Gold, topLeft = Offset(w * 0.80f, bodyTop), size = androidx.compose.ui.geometry.Size(bandWidth, h - bodyTop))
        drawRoundRect(
            color = LixoColors.GoldLip,
            topLeft = bodyRect.topLeft,
            size = bodyRect.size,
            cornerRadius = CornerRadius(14f, 14f),
            style = Stroke(width = 3f)
        )

        // Lid, animated via translate + rotate around the hinge (bottom-left of the lid).
        withTransform({
            translate(0f, lidDy.value)
            rotate(lidRot.value, pivot = Offset(0f, lidBottom))
        }) {
            val lidPath = Path().apply {
                moveTo(0f, lidBottom)
                lineTo(0f, lidTop + 14f)
                quadraticBezierTo(0f, lidTop, 14f, lidTop)
                lineTo(w - 14f, lidTop)
                quadraticBezierTo(w, lidTop, w, lidTop + 14f)
                lineTo(w, lidBottom)
                close()
            }
            drawPath(path = lidPath, color = woodColor.copy(alpha = lidAlpha.value))
            drawRect(
                color = LixoColors.Gold.copy(alpha = lidAlpha.value),
                topLeft = Offset(0f, lidBottom - h * 0.07f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.07f)
            )
            drawPath(
                path = lidPath,
                color = LixoColors.GoldLip.copy(alpha = lidAlpha.value),
                style = Stroke(width = 3f)
            )

            // Lock, hidden once the chest is fully open.
            if (state != ChestState.OPENED) {
                val lockW = w * 0.14f
                val lockH = h * 0.1f
                val lockLeft = w / 2f - lockW / 2f
                val lockTop = lidBottom - lockH / 2f
                drawRoundRect(
                    color = LixoColors.Gold.copy(alpha = lidAlpha.value),
                    topLeft = Offset(lockLeft, lockTop),
                    size = androidx.compose.ui.geometry.Size(lockW, lockH),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawCircle(
                    color = Color(0xFF4A3800).copy(alpha = lidAlpha.value),
                    radius = lockW * 0.18f,
                    center = Offset(w / 2f, lockTop + lockH / 2f)
                )
            }
        }
    }
}

/**
 * Full-screen chest-opening stage: scrim, title, the chest itself with its "tap to open" hint,
 * and once opened, [LightBurst] behind + [ItemReveal] on top. Auto-finishes 2.2s after opening,
 * or sooner on a scrim tap.
 */
@Composable
internal fun ChestRevealStage(
    item: WardrobeItem,
    onOpened: () -> Unit,
    onFinished: () -> Unit
) {
    var chestState by remember { mutableStateOf(ChestState.WAITING) }

    LaunchedEffect(chestState) {
        if (chestState == ChestState.OPENED) {
            delay(2200)
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CelebrationScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (chestState == ChestState.OPENED) onFinished()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (chestState == ChestState.OPENED) {
                    LixoStrings.ITEM_UNLOCKED_TITLE
                } else {
                    LixoStrings.GIFT_UNLOCKED_TITLE
                },
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )

            val bounce = rememberInfiniteTransition(label = "chestHintBounce").animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "chestHintBounce"
            )

            // Fixed layout so nothing jumps when the chest opens:
            // [item tile area] above [chest] above [hint / item details].
            Box(modifier = Modifier.width(300.dp), contentAlignment = Alignment.TopCenter) {
                if (chestState == ChestState.OPENED) {
                    // Centered on the revealed tile (tile area 150dp tall, tile 132dp at its bottom).
                    LightBurst(
                        item.rarity,
                        Modifier.size(300.dp).offset(y = (-66).dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.height(150.dp).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (chestState == ChestState.OPENED) {
                            ItemRevealTile(item)
                        }
                    }
                    RewardChest(
                        state = chestState,
                        onOpenAnimationFinished = {
                            chestState = ChestState.OPENED
                            onOpened()
                        },
                        modifier = Modifier
                            .size(180.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = chestState == ChestState.WAITING
                            ) { chestState = ChestState.OPENING }
                    )
                    // Room for the chest's breathing pulse + wiggle and the hint's upward bounce.
                    Spacer(Modifier.height(28.dp))
                    Box(
                        modifier = Modifier.height(90.dp).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        when (chestState) {
                            ChestState.WAITING -> Text(
                                text = LixoStrings.CHEST_TAP_TO_OPEN,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                modifier = Modifier.graphicsLayer { translationY = bounce.value }
                            )
                            ChestState.OPENED -> ItemRevealDetails(item)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
