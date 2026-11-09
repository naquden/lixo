package se.lixo.app.ui.components.avatar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.Rarity

/**
 * Interactive turntable viewport for a content-pack avatar (020): draws [content]'s
 * [appearance] with [drawAvatar], the same idle-animation clock and drag-to-rotate gesture as
 * the old `Avatar3DViewport`.
 */
@Composable
fun AvatarViewport(
    content: ActiveContent,
    appearance: AvatarAppearance,
    modifier: Modifier = Modifier,
    interactiveRotation: Boolean = true
) {
    var yawDeg by remember { mutableFloatStateOf(0f) }

    val drawList = remember(content, appearance) { buildAvatarDrawList(content, appearance) }
    val description = remember(content, appearance) { avatarContentDescription(content, appearance) }

    val infiniteTransition = rememberInfiniteTransition(label = "avatar_viewport_idle")
    val animTimeSeconds by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "avatar_viewport_clock"
    )

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .then(
                if (interactiveRotation) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            yawDeg = (yawDeg + dragAmount * 0.45f) % 360f
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pose = computeProceduralIdlePose(animTimeSeconds, drawList.hasLegendary)
            drawAvatar(content, appearance, pose, yawDeg, pose.legendaryAuraPhase, drawList)
        }
    }
}

/**
 * Small canvas that draws a single content-pack item in isolation, fitted and centered. Only
 * animates (SPARKLE twinkle) for SPARKLE/LEGENDARY items, same rationale as the old
 * `WardrobeItemPreview`.
 */
@Composable
fun ItemPreview(content: ActiveContent, item: ContentItem, modifier: Modifier = Modifier) {
    val needsAuraClock = item.ornament == ItemOrnament.SPARKLE || item.rarity == Rarity.LEGENDARY
    val auraPhaseState: State<Float> = if (needsAuraClock) {
        val infiniteTransition = rememberInfiniteTransition(label = "item_preview_sparkle")
        val animTimeSecondsState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 60f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 60_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "item_preview_clock"
        )
        remember(animTimeSecondsState) { derivedStateOf { (animTimeSecondsState.value * 0.5f) % 1.0f } }
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val drawList = remember(content, item) { buildItemDrawList(content, item) }
    val name = remember(content, item) { content.string(item.nameKey) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { contentDescription = name }
    ) {
        drawItemPreview(content, item, auraPhaseState.value, drawList)
    }
}
