package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ChestTheme
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.ui.components.avatar.darken
import se.lixo.app.ui.components.avatar.drawGem
import se.lixo.app.ui.components.avatar.drawGlowHalo
import se.lixo.app.ui.components.avatar.drawSparkle
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

enum class ChestState { WAITING, OPENING, OPENED }

internal val CelebrationScrim = Color(0xB3000000)

/**
 * Test tag on the closed/opening/opened reward chest canvas itself, so instrumented tests can
 * tap through to it (the surrounding text in [ChestRevealStage] is not clickable).
 */
const val REWARD_CHEST_TAG = "reward_chest"

/** Plan 011 section 4: how loudly the CLOSED chest hints at its rarity. Escalates COMMON -> LEGENDARY. */
data class ChestTeaserSpec(
    val glowAlpha: Float,          // peak alpha of the radial glow behind the chest; 0 = no glow
    val pulseAmplitude: Float,     // 0..1, the share of glowAlpha/ringAlpha that pulses
    val pulsePeriodMs: Int,        // one pulse half-cycle (tween duration, Reverse)
    val ringAlpha: Float,          // peak alpha of the rarity-colored frame around the chest; 0 = none
    val ringWidthDp: Float,
    val sparkleCount: Int,         // twinkling sparkles orbiting the closed chest
    val goldGlow: Boolean,         // LEGENDARY uses LixoColors.Gold instead of rarityColor
    val shakeAmplitudeDp: Float,   // continuous horizontal shake while WAITING; 0 = none
    val openDurationScale: Float   // multiplier on every OPENING tween duration
)

fun chestTeaserSpecFor(rarity: Rarity): ChestTeaserSpec = when (rarity) {
    Rarity.COMMON -> ChestTeaserSpec(
        glowAlpha = 0f,
        pulseAmplitude = 0f,
        pulsePeriodMs = 1200,
        ringAlpha = 0f,
        ringWidthDp = 0f,
        sparkleCount = 0,
        goldGlow = false,
        shakeAmplitudeDp = 0f,
        openDurationScale = 1f
    )
    Rarity.UNCOMMON -> ChestTeaserSpec(
        glowAlpha = 0.25f,
        pulseAmplitude = 0.10f,
        pulsePeriodMs = 1200,
        ringAlpha = 0.35f,
        ringWidthDp = 3f,
        sparkleCount = 0,
        goldGlow = false,
        shakeAmplitudeDp = 0f,
        openDurationScale = 1f
    )
    Rarity.RARE -> ChestTeaserSpec(
        glowAlpha = 0.40f,
        pulseAmplitude = 0.30f,
        pulsePeriodMs = 1000,
        ringAlpha = 0.55f,
        ringWidthDp = 4f,
        sparkleCount = 0,
        goldGlow = false,
        shakeAmplitudeDp = 0f,
        openDurationScale = 1f
    )
    Rarity.EPIC -> ChestTeaserSpec(
        glowAlpha = 0.55f,
        pulseAmplitude = 0.45f,
        pulsePeriodMs = 800,
        ringAlpha = 0.75f,
        ringWidthDp = 5f,
        sparkleCount = 4,
        goldGlow = false,
        shakeAmplitudeDp = 0f,
        openDurationScale = 1f
    )
    Rarity.LEGENDARY -> ChestTeaserSpec(
        glowAlpha = 0.75f,
        pulseAmplitude = 0.50f,
        pulsePeriodMs = 600,
        ringAlpha = 0.90f,
        ringWidthDp = 6f,
        sparkleCount = 8,
        goldGlow = true,
        shakeAmplitudeDp = 2.5f,
        openDurationScale = 1.3f
    )
}

/**
 * Chest body/lid/lock drawn entirely on [Canvas]. [WAITING] breathes, wiggles and shows the
 * [rarity] teaser from [chestTeaserSpecFor] (glow, pulsing frame, sparkles, and for LEGENDARY a
 * shake); [OPENING] squashes and sends the lid flying, taking [ChestTeaserSpec.openDurationScale]
 * times as long; [OPENED] is static (the item reveal happens above it, see [ChestRevealStage]).
 */
@Composable
fun RewardChest(
    state: ChestState,
    rarity: Rarity,
    onOpenAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier,
    chestTheme: ChestTheme? = null
) {
    val haptics = rememberLixoHaptics()
    val density = LocalDensity.current
    val spec = remember(rarity) { chestTeaserSpecFor(rarity) }
    val glowColor = if (spec.goldGlow) LixoColors.Gold else LixoColors.rarityColor(rarity)
    val bodyColor = chestTheme?.bodyHex?.let(::parseContentHex)?.let { Color(it) } ?: Color(0xFF8B5A2B)
    val plankColor = if (chestTheme != null) darken(bodyColor, 0.75f) else Color(0xFF6B4220)
    val trimColor = chestTheme?.trimHex?.let(::parseContentHex)?.let { Color(it) } ?: LixoColors.Gold
    val trimLipColor = if (chestTheme != null) darken(trimColor, 0.8f) else LixoColors.GoldLip

    // Each teaser transition only runs its infinite clock when the spec actually uses it; COMMON
    // (and the lower tiers for shake) get a constant state instead, so idle cards don't pay for
    // an animation invalidation every frame (see ItemPreview for the same pattern).
    val pulse: State<Float> = if (spec.glowAlpha > 0f || spec.ringAlpha > 0f) {
        rememberInfiniteTransition(label = "chestTeaserPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = spec.pulsePeriodMs),
                repeatMode = RepeatMode.Reverse
            ),
            label = "chestTeaserPulse"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val sparklePhase: State<Float> = if (spec.sparkleCount > 0 || chestTheme?.ornament == ItemOrnament.SPARKLE) {
        rememberInfiniteTransition(label = "chestTeaserSparkle").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "chestTeaserSparkle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val shake: State<Float> = if (spec.shakeAmplitudeDp > 0f) {
        rememberInfiniteTransition(label = "chestTeaserShake").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 70, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "chestTeaserShake"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
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
            fun ms(base: Int) = (base * spec.openDurationScale).roundToInt()
            haptics.heavy(SfxCue.CHEST_OPEN)
            val squashJob = launch {
                squashX.animateTo(1.15f, tween(ms(90)))
                squashY.animateTo(0.85f, tween(ms(90)))
                squashX.animateTo(0.92f, tween(ms(120)))
                squashY.animateTo(1.12f, tween(ms(120)))
                launch { squashX.animateTo(1f, spring()) }
                squashY.animateTo(1f, spring())
            }
            val lidJob = launch {
                val lidTargetDy = with(density) { -260.dp.toPx() }
                launch { lidDy.animateTo(lidTargetDy, tween(ms(450))) }
                launch { lidRot.animateTo(-35f, tween(ms(450))) }
                lidAlpha.animateTo(0f, tween(ms(450)))
            }
            squashJob.join()
            lidJob.join()
            onOpenAnimationFinished()
        }
    }

    Canvas(
        modifier = modifier
            .graphicsLayer {
                rotationZ = wiggle.value
                scaleX = breathe.value * squashX.value
                scaleY = breathe.value * squashY.value
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationX = if (state == ChestState.WAITING && spec.shakeAmplitudeDp > 0f) {
                    shake.value * spec.shakeAmplitudeDp.dp.toPx()
                } else {
                    0f
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val bodyTop = chestBodyRect(size).top
        val lidBottom = bodyTop
        val lidTop = h * CHEST_LID_TOP_FRACTION
        val pulseFactor = 1f - spec.pulseAmplitude + spec.pulseAmplitude * pulse.value

        // Rarity teaser glow behind the chest; COMMON has none.
        if (spec.glowAlpha > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = spec.glowAlpha * pulseFactor), Color.Transparent),
                    center = Offset(w / 2f, h * 0.6f),
                    radius = w * 0.8f
                ),
                radius = w * 0.8f,
                center = Offset(w / 2f, h * 0.6f)
            )
        }

        val woodColor = bodyColor

        // Body
        val bodyRect = chestBodyRect(size)
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
        // Trim vertical bands
        val bandWidth = w * 0.08f
        drawRect(color = trimColor, topLeft = Offset(w * 0.12f, bodyTop), size = androidx.compose.ui.geometry.Size(bandWidth, h - bodyTop))
        drawRect(color = trimColor, topLeft = Offset(w * 0.80f, bodyTop), size = androidx.compose.ui.geometry.Size(bandWidth, h - bodyTop))
        drawRoundRect(
            color = trimLipColor,
            topLeft = bodyRect.topLeft,
            size = bodyRect.size,
            cornerRadius = CornerRadius(14f, 14f),
            style = Stroke(width = 3f)
        )

        // Themed ornament decoration (025), on top of the body, under the lid.
        if (chestTheme != null) {
            when (chestTheme.ornament) {
                ItemOrnament.GLOW -> drawGlowHalo(Offset(w / 2f, h * 0.6f), w * 0.8f, trimColor)
                ItemOrnament.TRIM -> drawRoundRect(
                    color = trimColor,
                    topLeft = Offset(bodyRect.left + 6f, bodyRect.top + 6f),
                    size = androidx.compose.ui.geometry.Size(bodyRect.width - 12f, bodyRect.height - 12f),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 4f)
                )
                ItemOrnament.GEM -> drawGem(
                    androidx.compose.ui.geometry.Rect(w * 0.4f, bodyTop, w * 0.6f, h),
                    trimColor,
                    w / 60f
                )
                ItemOrnament.SPARKLE -> drawSparkle(bodyRect, Color.White, w / 60f, sparklePhase.value)
                ItemOrnament.NONE -> Unit
            }
        }

        // Lid, animated via translate + rotate around the hinge (bottom-left of the lid).
        withTransform({
            translate(0f, lidDy.value)
            rotate(lidRot.value, pivot = Offset(0f, lidBottom))
        }) {
            val lidPath = chestLidPath(size, 14f)
            drawPath(path = lidPath, color = woodColor.copy(alpha = lidAlpha.value))
            drawRect(
                color = trimColor.copy(alpha = lidAlpha.value),
                topLeft = Offset(0f, lidBottom - h * 0.07f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.07f)
            )
            drawPath(
                path = lidPath,
                color = trimLipColor.copy(alpha = lidAlpha.value),
                style = Stroke(width = 3f)
            )

            // Lock, hidden once the chest is fully open.
            if (state != ChestState.OPENED) {
                val lockW = w * 0.14f
                val lockH = h * 0.1f
                val lockLeft = w / 2f - lockW / 2f
                val lockTop = lidBottom - lockH / 2f
                drawRoundRect(
                    color = trimColor.copy(alpha = lidAlpha.value),
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

        // Rarity teaser ring + sparkles around the closed chest; the lid flies away during
        // OPENING, so a detached silhouette ring would look wrong once it starts moving.
        if (state == ChestState.WAITING) {
            if (spec.ringAlpha > 0f) {
                drawPath(
                    path = chestSilhouettePath(size, 14f),
                    color = glowColor.copy(alpha = spec.ringAlpha * pulseFactor),
                    style = Stroke(width = spec.ringWidthDp.dp.toPx())
                )
            }
            for (i in 0 until spec.sparkleCount) {
                val angle = sparklePhase.value * 2f * PI.toFloat() + i * 2f * PI.toFloat() / spec.sparkleCount
                val center = Offset(
                    w / 2f + cos(angle) * w * 0.62f,
                    h * 0.55f + sin(angle) * h * 0.55f
                )
                val twinkle = sin(sparklePhase.value * 2f * PI.toFloat() * 3f + i) * 0.5f + 0.5f
                val r = (2.5f + 2f * twinkle).dp.toPx()
                drawCircle(color = glowColor.copy(alpha = 0.5f * twinkle), radius = r * 2f, center = center)
                drawCircle(color = Color.White.copy(alpha = 0.4f + 0.6f * twinkle), radius = r, center = center)
            }
        }
    }
}

/**
 * Full-screen chest-opening stage: scrim, title, the chest itself with its "tap to open" hint,
 * and once opened, [LightBurst] behind + [ItemReveal] on top. Auto-finishes 2.2s after opening,
 * or sooner on a scrim tap. The title shows [source]'s label (e.g. "Banbelöning") until opened,
 * plus [labelDetailSv] when present (e.g. "Prestation: Blixtläsaren"), then switches to
 * [LixoStrings.ITEM_UNLOCKED_TITLE].
 */
@Composable
fun ChestRevealStage(
    content: ActiveContent,
    item: ContentItem,
    source: RewardSource,
    labelDetailSv: String? = null,
    chestTheme: ChestTheme? = null,
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
                    LixoStrings.rewardSourceLabel(source, labelDetailSv)
                },
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
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
                            ItemRevealTile(content, item)
                        }
                    }
                    RewardChest(
                        state = chestState,
                        rarity = item.rarity,
                        onOpenAnimationFinished = {
                            chestState = ChestState.OPENED
                            onOpened()
                        },
                        chestTheme = chestTheme,
                        modifier = Modifier
                            .size(180.dp)
                            .testTag(REWARD_CHEST_TAG)
                            .let {
                                if (chestTheme != null) {
                                    it.semantics { contentDescription = LixoStrings.EVENT_CHEST_DESCRIPTION }
                                } else {
                                    it
                                }
                            }
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
                            ChestState.OPENED -> ItemRevealDetails(content, item)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
