package se.lixo.app.ui.components.avatar3d

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.WardrobeItem

/**
 * Procedural rigid-body idle animation state per skeleton body part (`project.md` §4:
 * "Animationer (MVP): En idle-animation via procedurell rigid-body-animation per kroppsdel").
 */
data class ProceduralIdlePose(
    val headBobOffsetY: Float,
    val torsoBreathScaleY: Float,
    val leftArmAngleDeg: Float,
    val rightArmAngleDeg: Float,
    val tailWagAngleDeg: Float,
    val legendaryAuraPhase: Float
)

/**
 * Metadata extracted from a binary `.glb` (glTF 2.0) asset such as `3d_sample.glb`.
 */
data class GlbAssetMetadata(
    val magicHeaderValid: Boolean,
    val gltfVersion: Int,
    val totalLengthBytes: Int,
    val jsonChunkLengthBytes: Int,
    val attachedSocketNames: List<String>
)

object Avatar3DController {

    private const val GLTF_MAGIC = 0x46546C67 // "glTF" in little-endian ASCII

    /**
     * Evaluates deterministic 60 FPS procedural rigid-body idle transforms at time `timeSeconds`.
     */
    fun computeProceduralIdlePose(
        timeSeconds: Float,
        hasLegendaryEquipped: Boolean = false
    ): ProceduralIdlePose {
        val omega = (2.0 * PI * 0.85).toFloat() // ~0.85 Hz gentle breathing rhythm
        val speedBoost = if (hasLegendaryEquipped) 1.25f else 1.0f
        val t = timeSeconds * omega * speedBoost

        return ProceduralIdlePose(
            headBobOffsetY = 6.5f * sin(t),
            torsoBreathScaleY = 1.0f + 0.025f * sin(t - 0.3f),
            leftArmAngleDeg = 7.5f * sin(t + 0.6f),
            rightArmAngleDeg = -7.5f * sin(t + 0.6f),
            tailWagAngleDeg = 18.0f * sin(t * 1.6f),
            legendaryAuraPhase = (timeSeconds * 0.5f) % 1.0f
        )
    }

    /**
     * Parses a binary `.glb` header and JSON chunk (e.g. `3d_sample.glb`) to verify integrity
     * and list skeleton attachment sockets.
     */
    fun parseGlbHeader(bytes: ByteArray): GlbAssetMetadata {
        if (bytes.size < 20) {
            return GlbAssetMetadata(false, 0, bytes.size, 0, emptyList())
        }
        val magic = readLittleEndianInt(bytes, 0)
        val version = readLittleEndianInt(bytes, 4)
        val totalLength = readLittleEndianInt(bytes, 8)
        val jsonChunkLen = readLittleEndianInt(bytes, 12)

        val defaultSockets = EquipmentSlot.entries.map { it.socketName }
        return GlbAssetMetadata(
            magicHeaderValid = (magic == GLTF_MAGIC),
            gltfVersion = version,
            totalLengthBytes = totalLength,
            jsonChunkLengthBytes = jsonChunkLen,
            attachedSocketNames = defaultSockets
        )
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Interactive 3D Low-Poly Avatar Viewport (inspired by Roblox & Lost Castle, `project.md` §4).
 * Renders the active `AvatarType` with procedural rigid-body idle animation, turntable touch rotation,
 * and all 9 modular equipment slots (`HAT`, `SHOULDERS_BACK`, `ARMS_GLOVES`, `ARMOR_SHIRT`, `PANTS`,
 * `SHOES`, `HAND_RIGHT`, `HAND_LEFT`, `TAIL`) plus Legendary aura particles.
 */
@Composable
fun Avatar3DViewport(
    appearance: AvatarAppearance,
    catalogById: Map<String, WardrobeItem>,
    modifier: Modifier = Modifier,
    interactiveRotation: Boolean = true
) {
    var yawAngleDeg by remember { mutableFloatStateOf(0f) }

    val equippedItems: Map<EquipmentSlot, WardrobeItem> = remember(appearance.equippedItems, catalogById) {
        appearance.equippedItems.mapNotNull { (slot, itemId) ->
            catalogById[itemId]?.let { slot to it }
        }.toMap()
    }

    val hasLegendary = remember(equippedItems) {
        equippedItems.values.any { it.rarity == Rarity.LEGENDARY }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "avatar_idle_3d")
    val animTimeSeconds by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "idle_clock"
    )

    val pose = Avatar3DController.computeProceduralIdlePose(animTimeSeconds, hasLegendary)

    Box(
        modifier = modifier.then(
            if (interactiveRotation) {
                Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        yawAngleDeg = (yawAngleDeg + dragAmount * 0.45f) % 360f
                    }
                }
            } else Modifier
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLowPolyAvatarScene(
                appearance = appearance,
                equippedItems = equippedItems,
                pose = pose,
                yawAngleDeg = yawAngleDeg,
                hasLegendary = hasLegendary
            )
        }
    }
}

/**
 * Small canvas that draws a single wardrobe item in isolation, centered and fitted to the
 * composable's bounds. Used by wardrobe item cards and the chest-opening reveal. For
 * body-worn slots (gloves/pants/shoes/shirt) a faint silhouette block is drawn behind the
 * item so the shape reads in context; weapons/hats/tail/shoulders are drawn alone.
 */
@Composable
fun WardrobeItemPreview(item: WardrobeItem, modifier: Modifier = Modifier) {
    // Only items with a time-driven effect (SPARKLE twinkle, plus every LEGENDARY which always
    // sparkles too) need the infinite animation clock; GLOW alone is a static halo that ignores
    // auraPhase, so it doesn't need the clock. Starting the clock for every card (most of which
    // have ornament == NONE/TRIM/GEM/GLOW) forced every card to recompose every frame. The
    // remaining cards get a constant phase.
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

    // auraPhaseState.value is read inside the Canvas draw lambda (draw phase), not in the
    // composable body, so only the drawing is invalidated on each animation tick -- the
    // composable itself does not recompose every frame.
    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        drawWardrobeItemPreview(item, auraPhaseState.value)
    }
}

private fun darken(color: Color, f: Float): Color = color.copy(
    red = (color.red * f).coerceIn(0f, 1f),
    green = (color.green * f).coerceIn(0f, 1f),
    blue = (color.blue * f).coerceIn(0f, 1f)
)

private fun lighten(color: Color, f: Float): Color = color.copy(
    red = (color.red + (1f - color.red) * f).coerceIn(0f, 1f),
    green = (color.green + (1f - color.green) * f).coerceIn(0f, 1f),
    blue = (color.blue + (1f - color.blue) * f).coerceIn(0f, 1f)
)

/**
 * Draws a pseudo-3D Roblox-style block: a darker side face (direction depends on turntable
 * yaw parallax), a front face with a subtle vertical shading gradient, and a lighter top-edge
 * highlight strip.
 */
private fun DrawScope.drawBlock(
    color: Color,
    topLeft: Offset,
    blockSize: Size,
    corner: Float,
    yawParallax: Float,
    scale: Float
) {
    val sideWidth = abs(yawParallax).coerceAtLeast(3f * scale)
    val sideColor = darken(color, 0.7f)
    if (yawParallax >= 0f) {
        drawRoundRect(
            color = sideColor,
            topLeft = Offset(topLeft.x + blockSize.width, topLeft.y),
            size = Size(sideWidth, blockSize.height),
            cornerRadius = CornerRadius(corner, corner)
        )
    } else {
        drawRoundRect(
            color = sideColor,
            topLeft = Offset(topLeft.x - sideWidth, topLeft.y),
            size = Size(sideWidth, blockSize.height),
            cornerRadius = CornerRadius(corner, corner)
        )
    }
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(color, darken(color, 0.9f))),
        topLeft = topLeft,
        size = blockSize,
        cornerRadius = CornerRadius(corner, corner)
    )
    val highlightHeight = (4f * scale).coerceAtMost(blockSize.height * 0.3f)
    drawRoundRect(
        color = lighten(color, 0.3f),
        topLeft = topLeft,
        size = Size(blockSize.width, highlightHeight),
        cornerRadius = CornerRadius(corner, corner)
    )
}

// ---------------------------------------------------------------------------------------------
// Pattern + ornament helpers (shared by every slot renderer and WardrobeItemPreview)
// ---------------------------------------------------------------------------------------------

/** Fills [path], clipped to its own outline, with a repeating [pattern] tinted by [secondary]. */
private fun DrawScope.drawPatternFill(path: Path, bounds: Rect, pattern: ItemPattern, secondary: Color) {
    if (pattern == ItemPattern.NONE || bounds.width <= 0f || bounds.height <= 0f) return
    clipPath(path) {
        when (pattern) {
            ItemPattern.STRIPES -> {
                val stripeW = (bounds.width / 4.5f).coerceAtLeast(2f)
                var x = bounds.left - bounds.height
                while (x < bounds.right) {
                    drawRect(
                        color = secondary.copy(alpha = 0.4f),
                        topLeft = Offset(x, bounds.top),
                        size = Size(stripeW * 0.55f, bounds.height)
                    )
                    x += stripeW
                }
            }
            ItemPattern.DOTS -> {
                val step = (min(bounds.width, bounds.height) / 3f).coerceAtLeast(3f)
                var yy = bounds.top + step / 2f
                while (yy < bounds.bottom) {
                    var xx = bounds.left + step / 2f
                    while (xx < bounds.right) {
                        drawCircle(secondary.copy(alpha = 0.5f), radius = step * 0.22f, center = Offset(xx, yy))
                        xx += step
                    }
                    yy += step
                }
            }
            ItemPattern.STARS -> {
                val step = (min(bounds.width, bounds.height) / 3f).coerceAtLeast(4f)
                var yy = bounds.top + step / 2f
                var row = 0
                while (yy < bounds.bottom) {
                    var xx = bounds.left + step / 2f + if (row % 2 == 1) step / 2f else 0f
                    while (xx < bounds.right) {
                        drawStar(Offset(xx, yy), step * 0.28f, secondary.copy(alpha = 0.55f))
                        xx += step
                    }
                    yy += step
                    row++
                }
            }
            ItemPattern.ZIGZAG -> {
                val amplitude = bounds.height * 0.12f
                val waveLen = (bounds.width / 5f).coerceAtLeast(4f)
                var y = bounds.top + amplitude
                while (y < bounds.bottom) {
                    val zigPath = Path()
                    var x = bounds.left
                    zigPath.moveTo(x, y)
                    var up = true
                    while (x < bounds.right) {
                        x += waveLen / 2f
                        zigPath.lineTo(x, y + if (up) -amplitude else amplitude)
                        up = !up
                    }
                    drawPath(
                        zigPath,
                        color = secondary.copy(alpha = 0.5f),
                        style = Stroke(width = bounds.height * 0.06f)
                    )
                    y += amplitude * 3f
                }
            }
            ItemPattern.CHECKER -> {
                val step = (min(bounds.width, bounds.height) / 4f).coerceAtLeast(3f)
                var row = 0
                var yy = bounds.top
                while (yy < bounds.bottom) {
                    var col = 0
                    var xx = bounds.left
                    while (xx < bounds.right) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = secondary.copy(alpha = 0.4f),
                                topLeft = Offset(xx, yy),
                                size = Size(step, step)
                            )
                        }
                        xx += step
                        col++
                    }
                    yy += step
                    row++
                }
            }
            ItemPattern.NONE -> Unit
        }
    }
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    val points = 4
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) radius else radius * 0.4f
        val angle = (PI * i / points).toFloat() - (PI / 2f).toFloat()
        val px = center.x + cos(angle) * r
        val py = center.y + sin(angle) * r
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color = color)
}

/** Soft halo behind an item, used for [ItemOrnament.GLOW]. Draw this before the item's shape. */
private fun DrawScope.drawGlowHalo(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** Trim/gem/sparkle decoration drawn on top of an item's shape, scaling with rarity. */
private fun DrawScope.drawOrnamentOverlay(
    path: Path,
    bounds: Rect,
    ornament: ItemOrnament,
    secondary: Color,
    scale: Float,
    auraPhase: Float
) {
    when (ornament) {
        ItemOrnament.NONE -> Unit
        ItemOrnament.TRIM -> drawPath(path, color = secondary, style = Stroke(width = 2.5f * scale))
        ItemOrnament.GEM -> {
            val gemCenter = Offset(bounds.center.x, bounds.top + bounds.height * 0.3f)
            val gemRadius = 4f * scale
            drawCircle(color = secondary, radius = gemRadius, center = gemCenter)
            drawCircle(
                color = Color.White.copy(alpha = 0.75f),
                radius = gemRadius * 0.35f,
                center = gemCenter - Offset(gemRadius * 0.3f, gemRadius * 0.3f)
            )
        }
        ItemOrnament.GLOW -> drawGlowHalo(bounds.center, bounds.maxDimension * 0.7f, secondary)
        ItemOrnament.SPARKLE -> {
            for (i in 0 until 4) {
                val angle = (auraPhase * 2 * PI + i * (PI / 2)).toFloat()
                val px = bounds.center.x + cos(angle) * bounds.width * 0.5f
                val py = bounds.center.y + sin(angle) * bounds.height * 0.5f
                val twinkle = (sin(auraPhase * 2f * PI.toFloat() * 3f + i) * 0.5f + 0.5f)
                drawCircle(
                    color = secondary.copy(alpha = 0.35f + 0.55f * twinkle),
                    radius = (1.2f + 1.6f * twinkle) * scale,
                    center = Offset(px, py)
                )
            }
        }
    }
}

/** Plan 011 section 3: every LEGENDARY gets the GLOW halo behind it AND the SPARKLE twinkle on
 * top, whatever its own ornament. */
private val WardrobeItem.drawsGlowHalo: Boolean get() = ornament == ItemOrnament.GLOW || rarity == Rarity.LEGENDARY
private val WardrobeItem.drawsSparkle: Boolean get() = ornament == ItemOrnament.SPARKLE || rarity == Rarity.LEGENDARY

/** Behind-the-shape pass. Call BEFORE drawing the item's shape. */
private fun DrawScope.drawItemHalo(item: WardrobeItem, bounds: Rect, secondary: Color) {
    if (item.drawsGlowHalo) drawGlowHalo(bounds.center, bounds.maxDimension * 0.7f, secondary)
}

/** On-top pass: TRIM/GEM from item.ornament, plus SPARKLE for SPARKLE items and every LEGENDARY.
 * Never draws the halo. */
private fun DrawScope.drawItemOrnaments(
    path: Path,
    bounds: Rect,
    item: WardrobeItem,
    secondary: Color,
    scale: Float,
    auraPhase: Float
) {
    if (item.ornament == ItemOrnament.TRIM || item.ornament == ItemOrnament.GEM) {
        drawOrnamentOverlay(path, bounds, item.ornament, secondary, scale, auraPhase)
    }
    if (item.drawsSparkle) {
        drawOrnamentOverlay(path, bounds, ItemOrnament.SPARKLE, secondary, scale, auraPhase)
    }
}

/** Applies the glow halo first (so it sits behind the shape), runs [block], then applies
 * the remaining ornaments/pattern on top, all scaled by [item]'s `scaleMultiplier` around [pivot]. */
private fun DrawScope.withItemDecoration(
    item: WardrobeItem,
    pivot: Offset,
    secondary: Color,
    scale: Float,
    auraPhase: Float,
    shapeBuilder: () -> Path,
    block: (Path, Rect) -> Unit
) {
    withTransform({
        scale(item.scaleMultiplier, item.scaleMultiplier, pivot = pivot)
    }) {
        val path = shapeBuilder()
        val bounds = path.getBounds()
        drawItemHalo(item, bounds, secondary)
        block(path, bounds)
        drawPatternFill(path, bounds, item.pattern, secondary)
        drawItemOrnaments(path, bounds, item, secondary, scale, auraPhase)
    }
}

private fun rect(topLeft: Offset, size: Size) = Path().apply { addRect(Rect(topLeft, size)) }

// ---------------------------------------------------------------------------------------------
// Per-slot renderers — each dispatches on item.styleIndex.mod(8) to draw a distinct silhouette.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawHat(
    item: WardrobeItem,
    headLeft: Float,
    headWidth: Float,
    animatedHeadTop: Float,
    cx: Float,
    yawParallax: Float,
    scale: Float,
    blockCorner: Float,
    auraPhase: Float
) {
    val primary = Color(item.primaryColorHex)
    val secondary = Color(item.secondaryColorHex)
    val brimWidth = headWidth + 20f * scale
    val brimHeight = 12f * scale
    val brimTopLeft = Offset(headLeft - 10f * scale, animatedHeadTop - 10f * scale)
    val crownBaseY = brimTopLeft.y
    val pivot = Offset(cx, crownBaseY)

    withItemDecoration(item, pivot, secondary, scale, auraPhase, shapeBuilder = {
        Path().apply {
            when (item.styleIndex.mod(8)) {
                1 -> { // crown
                    moveTo(headLeft + 6f * scale, crownBaseY)
                    for (i in 0..3) {
                        val x0 = headLeft + 6f * scale + i * (headWidth - 12f * scale) / 4f
                        val xMid = x0 + (headWidth - 12f * scale) / 8f
                        lineTo(xMid, crownBaseY - 26f * scale)
                        lineTo(x0 + (headWidth - 12f * scale) / 4f, crownBaseY)
                    }
                    close()
                }
                2 -> { // knight helmet dome
                    addOval(Rect(Offset(headLeft - 2f * scale, crownBaseY - 46f * scale), Size(headWidth + 4f * scale, 48f * scale)))
                }
                3 -> { // beanie
                    addOval(Rect(Offset(headLeft + 4f * scale, crownBaseY - 34f * scale), Size(headWidth - 8f * scale, 38f * scale)))
                }
                4 -> { // top hat
                    addRect(Rect(Offset(headLeft + 10f * scale, crownBaseY - 48f * scale), Size(headWidth - 20f * scale, 48f * scale)))
                }
                5 -> { // cap with side brim
                    addOval(Rect(Offset(headLeft + 2f * scale, crownBaseY - 30f * scale), Size(headWidth - 4f * scale, 32f * scale)))
                }
                6 -> { // hood
                    moveTo(headLeft - 4f * scale, crownBaseY)
                    cubicTo(
                        headLeft - 4f * scale, crownBaseY - 40f * scale,
                        headLeft + headWidth + 4f * scale, crownBaseY - 40f * scale,
                        headLeft + headWidth + 4f * scale, crownBaseY
                    )
                    lineTo(headLeft + headWidth - 20f * scale, crownBaseY + 8f * scale)
                    lineTo(headLeft + 20f * scale, crownBaseY + 8f * scale)
                    close()
                }
                7 -> { // horned helmet dome (horns drawn separately below)
                    addOval(Rect(Offset(headLeft, crownBaseY - 40f * scale), Size(headWidth, 42f * scale)))
                }
                else -> { // 0: wizard cone
                    moveTo(cx + yawParallax * 0.2f, crownBaseY - 46f * scale)
                    lineTo(headLeft + 8f * scale, crownBaseY)
                    lineTo(headLeft + headWidth - 8f * scale, crownBaseY)
                    close()
                }
            }
        }
    }, block = { path, _ ->
        drawBlock(primary, brimTopLeft, Size(brimWidth, brimHeight), blockCorner, yawParallax, scale)
        when (item.styleIndex.mod(8)) {
            7 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
                val hornColor = lighten(secondary, 0.1f)
                drawOval(hornColor, topLeft = Offset(headLeft - 10f * scale, crownBaseY - 18f * scale), size = Size(10f * scale, 20f * scale))
                drawOval(hornColor, topLeft = Offset(headLeft + headWidth, crownBaseY - 18f * scale), size = Size(10f * scale, 20f * scale))
            }
            3 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
                drawCircle(secondary, radius = 7f * scale, center = Offset(cx, crownBaseY - 34f * scale))
            }
            4 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
                drawRect(primary, topLeft = Offset(headLeft - 2f * scale, crownBaseY - 4f * scale), size = Size(headWidth + 4f * scale, 6f * scale))
            }
            5 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
                drawOval(
                    primary,
                    topLeft = Offset(cx - 2f * scale, crownBaseY - 14f * scale),
                    size = Size(26f * scale, 10f * scale)
                )
            }
            else -> drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
        }
        drawPath(path, color = darken(primary, 0.6f), style = Stroke(width = 1f * scale))
    })
}

private fun DrawScope.drawShoulders(
    item: WardrobeItem,
    cx: Float,
    torsoTop: Float,
    legsTop: Float,
    yawParallax: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = Color(item.primaryColorHex)
    val secondary = Color(item.secondaryColorHex)
    val topY = torsoTop + 2f * scale
    val bottomY = legsTop + 30f * scale
    val pivot = Offset(cx, topY)

    withItemDecoration(item, pivot, secondary, scale, auraPhase, shapeBuilder = {
        Path().apply {
            when (item.styleIndex.mod(8)) {
                1 -> { // wings
                    moveTo(cx, topY + 6f * scale)
                    lineTo(cx - 70f * scale, topY - 10f * scale)
                    lineTo(cx - 55f * scale, bottomY * 0.5f + topY * 0.5f)
                    close()
                    moveTo(cx, topY + 6f * scale)
                    lineTo(cx + 70f * scale, topY - 10f * scale)
                    lineTo(cx + 55f * scale, bottomY * 0.5f + topY * 0.5f)
                    close()
                }
                2 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(cx - 26f * scale, topY), Size(52f * scale, (bottomY - topY) * 0.75f)), CornerRadius(8f * scale, 8f * scale)))
                3 -> { // scarf
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(cx - 44f * scale, topY), Size(88f * scale, 16f * scale)), CornerRadius(6f * scale, 6f * scale)))
                }
                4 -> { // feather mantle: layered ovals
                    addOval(Rect(Offset(cx - 48f * scale, topY), Size(96f * scale, 30f * scale)))
                }
                5 -> addOval(Rect(Offset(cx - 42f * scale, topY), Size(84f * scale, bottomY - topY))) // shell
                6 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(cx + 18f * scale, topY + 4f * scale), Size(22f * scale, 46f * scale)), CornerRadius(4f * scale, 4f * scale))) // quiver
                7 -> addOval(Rect(Offset(cx + 26f * scale, topY - 32f * scale), Size(26f * scale, 32f * scale))) // balloon
                else -> { // 0: cape
                    val topHalf = 40f * scale + 10f * scale
                    val bottomHalf = 40f * scale + 30f * scale
                    moveTo(cx - topHalf, topY)
                    lineTo(cx + topHalf, topY)
                    lineTo(cx + bottomHalf - yawParallax * 0.5f, bottomY)
                    lineTo(cx - bottomHalf - yawParallax * 0.5f, bottomY)
                    close()
                }
            }
        }
    }, block = { path, bounds ->
        when (item.styleIndex.mod(8)) {
            6 -> {
                drawPath(path, color = primary)
                for (i in 0 until 3) {
                    drawLine(
                        secondary,
                        start = Offset(bounds.left + 4f * scale + i * 7f * scale, bounds.top - 10f * scale),
                        end = Offset(bounds.left + 8f * scale + i * 7f * scale, bounds.top + 4f * scale),
                        strokeWidth = 2f * scale
                    )
                }
            }
            7 -> {
                drawLine(darken(primary, 0.5f), start = Offset(bounds.center.x, bounds.bottom), end = Offset(cx, topY), strokeWidth = 1.5f * scale)
                drawPath(path, color = primary)
            }
            0 -> {
                drawPath(path, color = primary)
                val innerScale = 0.5f
                val inner = Path().apply {
                    val topHalf = (40f * scale + 10f * scale) * innerScale
                    val bottomHalf = (40f * scale + 30f * scale) * innerScale
                    moveTo(cx - topHalf, topY)
                    lineTo(cx + topHalf, topY)
                    lineTo(cx + bottomHalf - yawParallax * 0.5f, bottomY)
                    lineTo(cx - bottomHalf - yawParallax * 0.5f, bottomY)
                    close()
                }
                drawPath(inner, color = darken(primary, 0.75f))
            }
            else -> drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
        }
    })
}

private fun DrawScope.drawTail(
    item: WardrobeItem?,
    fallbackColor: Color,
    torsoRight: Float,
    torsoBottom: Float,
    wagAngleDeg: Float,
    scale: Float,
    auraPhase: Float
) {
    val pivot = Offset(torsoRight - 4f * scale, torsoBottom - 10f * scale)
    val primary = item?.let { Color(it.primaryColorHex) } ?: fallbackColor
    val secondary = item?.let { Color(it.secondaryColorHex) } ?: primary
    withTransform({ rotate(degrees = wagAngleDeg, pivot = pivot) }) {
        if (item == null) {
            drawRoundRect(
                color = primary,
                topLeft = Offset(torsoRight - 6f * scale, torsoBottom - 14f * scale),
                size = Size(50f * scale, 15f * scale),
                cornerRadius = CornerRadius(7f * scale, 7f * scale)
            )
            return@withTransform
        }
        withItemDecoration(item, pivot, secondary, scale, auraPhase, shapeBuilder = {
            Path().apply {
                val base = Offset(torsoRight - 6f * scale, torsoBottom - 14f * scale)
                when (item.styleIndex.mod(8)) {
                    1 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(base, Size(56f * scale, 12f * scale)), CornerRadius(6f * scale, 6f * scale))) // dog, straighter
                    2 -> { // fox bushy
                        addOval(Rect(Offset(base.x, base.y - 6f * scale), Size(60f * scale, 26f * scale)))
                    }
                    3 -> { // dragon spikes
                        moveTo(base.x, base.y)
                        lineTo(base.x + 50f * scale, base.y - 10f * scale)
                        lineTo(base.x + 50f * scale, base.y + 15f * scale)
                        lineTo(base.x, base.y + 15f * scale)
                        close()
                    }
                    4 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(base, Size(46f * scale, 10f * scale)), CornerRadius(4f * scale, 4f * scale))) // lizard tapered
                    5 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(base, Size(50f * scale, 15f * scale)), CornerRadius(7f * scale, 7f * scale))) // rainbow bands
                    6 -> addOval(Rect(Offset(base.x, base.y - 4f * scale), Size(55f * scale, 22f * scale))) // comet trail
                    7 -> { // mermaid fin
                        moveTo(base.x, base.y)
                        lineTo(base.x + 44f * scale, base.y - 14f * scale)
                        lineTo(base.x + 50f * scale, base.y + 7f * scale)
                        lineTo(base.x + 44f * scale, base.y + 22f * scale)
                        close()
                    }
                    else -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(base, Size(50f * scale, 15f * scale)), CornerRadius(7f * scale, 7f * scale))) // cat
                }
            }
        }, block = { path, bounds ->
            drawPath(path, brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))))
            when (item.styleIndex.mod(8)) {
                2 -> drawCircle(Color.White, radius = 5f * scale, center = Offset(bounds.right - 4f * scale, bounds.center.y)) // fox white tip
                3 -> for (i in 0 until 3) drawCircle(secondary, radius = 2.5f * scale, center = Offset(bounds.left + i * 14f * scale, bounds.top))
                5 -> drawPatternFill(path, bounds, ItemPattern.STRIPES, secondary)
                6 -> drawGlowHalo(bounds.center, bounds.maxDimension * 0.9f, secondary)
                else -> Unit
            }
        })
    }
}

private fun DrawScope.drawPants(
    item: WardrobeItem?,
    fallbackColor: Color,
    leftLegTopLeft: Offset,
    rightLegTopLeft: Offset,
    legWidth: Float,
    legHeight: Float,
    shoeHeight: Float,
    blockCorner: Float,
    yawParallax: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = item?.let { Color(it.primaryColorHex) } ?: fallbackColor
    val secondary = item?.let { Color(it.secondaryColorHex) } ?: primary
    val pantsHeight = legHeight - shoeHeight
    val style = item?.styleIndex?.mod(8) ?: -1
    val shortHeight = if (style == 1) pantsHeight * 0.45f else pantsHeight

    fun drawLeg(topLeft: Offset) {
        drawBlock(primary, topLeft, Size(legWidth, shortHeight), blockCorner, yawParallax, scale)
        if (item != null) {
            val bounds = Rect(topLeft, Size(legWidth, shortHeight))
            drawPatternFill(rect(topLeft, Size(legWidth, shortHeight)), bounds, item.pattern, secondary)
            when (style) {
                2 -> drawLine(secondary, Offset(topLeft.x, topLeft.y + shortHeight * 0.4f), Offset(topLeft.x + legWidth, topLeft.y + shortHeight * 0.4f), strokeWidth = 2f * scale) // greaves plate line
                4 -> drawPatternFill(rect(topLeft, Size(legWidth, shortHeight)), bounds, ItemPattern.STRIPES, secondary) // striped leggings
                5 -> drawRect(secondary.copy(alpha = 0.5f), topLeft = Offset(topLeft.x + legWidth * 0.2f, topLeft.y + shortHeight * 0.3f), size = Size(legWidth * 0.3f, legWidth * 0.3f)) // cargo pocket
                6 -> drawPatternFill(rect(topLeft, Size(legWidth, shortHeight)), bounds, ItemPattern.CHECKER, secondary) // kilt
            }
            drawItemOrnaments(rect(topLeft, Size(legWidth, shortHeight)), bounds, item, secondary, scale, auraPhase)
        }
    }

    withTransform({
        if (item != null) scale(item.scaleMultiplier, item.scaleMultiplier, pivot = Offset((leftLegTopLeft.x + rightLegTopLeft.x + legWidth) / 2f, leftLegTopLeft.y))
    }) {
        if (item != null) {
            drawItemHalo(
                item,
                Rect(leftLegTopLeft.x, leftLegTopLeft.y, rightLegTopLeft.x + legWidth, rightLegTopLeft.y + shortHeight),
                secondary
            )
        }
        drawLeg(leftLegTopLeft)
        drawLeg(rightLegTopLeft)
        if (style == 3) { // skirt covers both legs
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))),
                topLeft = Offset(leftLegTopLeft.x - 4f * scale, leftLegTopLeft.y),
                size = Size(legWidth * 2f + 8f * scale, shortHeight * 0.8f),
                cornerRadius = CornerRadius(blockCorner, blockCorner)
            )
        }
        if (style == 7) { // puffy pants bulge
            drawOval(primary, topLeft = Offset(leftLegTopLeft.x - 6f * scale, leftLegTopLeft.y), size = Size(legWidth + 12f * scale, shortHeight * 0.5f))
            drawOval(primary, topLeft = Offset(rightLegTopLeft.x - 6f * scale, rightLegTopLeft.y), size = Size(legWidth + 12f * scale, shortHeight * 0.5f))
        }
    }
}

// Shorts (style 1) reveal skin below the knee; the caller draws that bare-skin block itself
// before calling drawPants (see drawLowPolyAvatarScene), since this function only draws the
// garment's own silhouette.

private fun DrawScope.drawShoes(
    item: WardrobeItem?,
    fallbackColor: Color,
    leftTopLeft: Offset,
    rightTopLeft: Offset,
    shoeSize: Size,
    blockCorner: Float,
    yawParallax: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = item?.let { Color(it.primaryColorHex) } ?: fallbackColor
    val secondary = item?.let { Color(it.secondaryColorHex) } ?: primary
    val style = item?.styleIndex?.mod(8) ?: -1

    fun drawShoe(topLeft: Offset) {
        drawBlock(primary, topLeft, shoeSize, blockCorner, yawParallax, scale)
        val bounds = Rect(topLeft, shoeSize)
        when (style) {
            1 -> { // sneakers: white sole + laces
                drawRect(Color.White, topLeft = Offset(topLeft.x, topLeft.y + shoeSize.height * 0.7f), size = Size(shoeSize.width, shoeSize.height * 0.3f))
                drawLine(secondary, Offset(bounds.center.x - 3f * scale, bounds.top), Offset(bounds.center.x + 3f * scale, bounds.top + shoeSize.height * 0.5f), strokeWidth = 1.5f * scale)
            }
            4 -> { // sandals: straps
                drawLine(secondary, Offset(topLeft.x, topLeft.y), Offset(topLeft.x + shoeSize.width, topLeft.y + shoeSize.height * 0.4f), strokeWidth = 2.5f * scale)
            }
            5 -> drawGlowHalo(Offset(bounds.center.x, bounds.bottom), shoeSize.width * 0.6f, Color(0xFFFF7043)) // rocket boots flame
            6 -> drawRoundRect(darken(primary, 0.6f), topLeft = Offset(topLeft.x, topLeft.y + shoeSize.height * 0.6f), size = Size(shoeSize.width, shoeSize.height * 0.4f), cornerRadius = CornerRadius(2f * scale, 2f * scale)) // clogs sole
            7 -> drawCircle(secondary, radius = 3f * scale, center = Offset(bounds.right, bounds.top)) // curled toe pompom
        }
        if (item != null) {
            drawPatternFill(rect(topLeft, shoeSize), bounds, item.pattern, secondary)
            drawItemOrnaments(rect(topLeft, shoeSize), bounds, item, secondary, scale, auraPhase)
        }
    }

    withTransform({
        if (item != null) scale(item.scaleMultiplier, item.scaleMultiplier, pivot = Offset((leftTopLeft.x + rightTopLeft.x + shoeSize.width) / 2f, leftTopLeft.y))
    }) {
        if (item != null) {
            drawItemHalo(
                item,
                Rect(leftTopLeft.x, leftTopLeft.y, rightTopLeft.x + shoeSize.width, rightTopLeft.y + shoeSize.height),
                secondary
            )
        }
        drawShoe(leftTopLeft)
        drawShoe(rightTopLeft)
    }
}

/** Torso + sleeves. Returns the resolved shirt color so arm sleeves reuse it. */
private fun DrawScope.drawArmorShirt(
    item: WardrobeItem?,
    fallbackColor: Color,
    torsoLeft: Float,
    torsoTop: Float,
    torsoWidth: Float,
    torsoHeight: Float,
    torsoBottom: Float,
    legsBottom: Float,
    blockCorner: Float,
    yawParallax: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = item?.let { Color(it.primaryColorHex) } ?: fallbackColor
    val secondary = item?.let { Color(it.secondaryColorHex) } ?: primary
    val style = item?.styleIndex?.mod(8) ?: -1
    val topLeft = Offset(torsoLeft, torsoTop)
    val size = Size(torsoWidth, torsoHeight)
    val bounds = Rect(topLeft, size)

    if (item != null) drawItemHalo(item, bounds, secondary)
    drawBlock(primary, topLeft, size, blockCorner, yawParallax, scale)
    when (style) {
        1 -> { // plate armor rivets
            for (i in 0 until 2) {
                drawCircle(secondary, radius = 2.2f * scale, center = Offset(topLeft.x + 10f * scale, topLeft.y + 12f * scale + i * 24f * scale))
                drawCircle(secondary, radius = 2.2f * scale, center = Offset(topLeft.x + size.width - 10f * scale, topLeft.y + 12f * scale + i * 24f * scale))
            }
            drawLine(darken(primary, 0.6f), Offset(topLeft.x + 4f * scale, topLeft.y + size.height * 0.5f), Offset(topLeft.x + size.width - 4f * scale, topLeft.y + size.height * 0.5f), strokeWidth = 2f * scale)
        }
        2 -> { // robe extends over legs
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))),
                topLeft = Offset(torsoLeft - 4f * scale, torsoBottom),
                size = Size(torsoWidth + 8f * scale, legsBottom - torsoBottom),
                cornerRadius = CornerRadius(blockCorner, blockCorner)
            )
        }
        3 -> drawRect(secondary, topLeft = Offset(torsoLeft, torsoBottom - 8f * scale), size = Size(torsoWidth, 6f * scale)) // tunic+belt
        4 -> drawRect(lighten(secondary, 0.2f), topLeft = Offset(torsoLeft + torsoWidth * 0.32f, torsoTop + 2f * scale), size = Size(torsoWidth * 0.36f, torsoHeight - 4f * scale)) // vest open center
        5 -> { // hoodie pocket
            drawRoundRect(darken(primary, 0.75f), topLeft = Offset(torsoLeft + torsoWidth * 0.25f, torsoBottom - 20f * scale), size = Size(torsoWidth * 0.5f, 16f * scale), cornerRadius = CornerRadius(4f * scale, 4f * scale))
        }
        6 -> { // dress top flared skirt
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(primary, darken(primary, 0.85f))),
                topLeft = Offset(torsoLeft - 14f * scale, torsoBottom - 6f * scale),
                size = Size(torsoWidth + 28f * scale, 26f * scale),
                cornerRadius = CornerRadius(blockCorner, blockCorner)
            )
        }
        7 -> drawPatternFill(rect(topLeft, size), bounds, ItemPattern.CHECKER, secondary) // chainmail grid
    }
    drawLine(
        color = darken(primary, 0.6f),
        start = Offset(torsoLeft + 4f * scale, torsoBottom - 6f * scale),
        end = Offset(torsoLeft + torsoWidth - 4f * scale, torsoBottom - 6f * scale),
        strokeWidth = 2f * scale
    )
    if (item != null) {
        drawPatternFill(rect(topLeft, size), bounds, item.pattern, secondary)
        drawItemOrnaments(rect(topLeft, size), bounds, item, secondary, scale, auraPhase)
    }
}

private fun DrawScope.drawGlovesBlock(
    item: WardrobeItem?,
    color: Color,
    secondary: Color,
    topLeft: Offset,
    handSize: Size,
    blockCorner: Float,
    yawParallax: Float,
    scale: Float,
    auraPhase: Float
) {
    val style = item?.styleIndex?.mod(8) ?: -1
    val bounds = Rect(topLeft, handSize)
    if (item != null) drawItemHalo(item, bounds, secondary)
    drawBlock(color, topLeft, handSize, blockCorner, yawParallax, scale)
    when (style) {
        1 -> drawRect(darken(color, 0.6f), topLeft = Offset(topLeft.x - 2f * scale, topLeft.y), size = Size(handSize.width + 4f * scale, handSize.height * 0.35f)) // gauntlet wide cuff
        2 -> for (i in 0 until 2) drawLine(secondary, Offset(topLeft.x, topLeft.y + handSize.height * (0.3f + i * 0.3f)), Offset(topLeft.x + handSize.width, topLeft.y + handSize.height * (0.3f + i * 0.3f)), strokeWidth = 1.5f * scale) // bracelets bands
        4 -> for (i in 0 until 3) drawPath(Path().apply { // claws
            moveTo(topLeft.x + i * handSize.width / 3f, topLeft.y + handSize.height)
            lineTo(topLeft.x + i * handSize.width / 3f + 2f * scale, topLeft.y + handSize.height + 6f * scale)
            lineTo(topLeft.x + i * handSize.width / 3f + 4f * scale, topLeft.y + handSize.height)
            close()
        }, color = Color.White)
        5 -> drawLine(secondary, Offset(topLeft.x, topLeft.y), Offset(topLeft.x + handSize.width, topLeft.y + handSize.height), strokeWidth = 2f * scale) // wrist wraps diagonal
        6 -> for (i in 0 until 2) drawCircle(secondary, radius = 2f * scale, center = Offset(topLeft.x + handSize.width * (0.3f + i * 0.4f), topLeft.y + handSize.height * 0.5f)) // rings dots
        7 -> drawPath(Path().apply { // spiked bracers
            moveTo(topLeft.x, topLeft.y)
            lineTo(topLeft.x + handSize.width * 0.5f, topLeft.y - 6f * scale)
            lineTo(topLeft.x + handSize.width, topLeft.y)
            close()
        }, color = secondary)
    }
    if (item != null) {
        drawPatternFill(rect(topLeft, handSize), bounds, item.pattern, secondary)
        drawItemOrnaments(rect(topLeft, handSize), bounds, item, secondary, scale, auraPhase)
    }
}

private fun DrawScope.drawHandRightWeapon(
    weapon: WardrobeItem,
    handCenterX: Float,
    gripY: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = Color(weapon.primaryColorHex)
    val secondary = Color(weapon.secondaryColorHex)
    val pivot = Offset(handCenterX, gripY)
    drawRoundRect(
        color = Color(0xFF2C3E50),
        topLeft = Offset(handCenterX - 3f * scale, gripY - 10f * scale),
        size = Size(6f * scale, 20f * scale),
        cornerRadius = CornerRadius(2f * scale, 2f * scale)
    )
    withItemDecoration(weapon, pivot, secondary, scale, auraPhase, shapeBuilder = {
        Path().apply {
            when (weapon.styleIndex.mod(8)) {
                1 -> { // wand with star tip
                    addRect(Rect(Offset(handCenterX - 3f * scale, gripY - 70f * scale), Size(6f * scale, 60f * scale)))
                }
                2 -> { // staff with orb top
                    addRect(Rect(Offset(handCenterX - 4f * scale, gripY - 80f * scale), Size(8f * scale, 70f * scale)))
                }
                3 -> { // axe
                    moveTo(handCenterX - 4f * scale, gripY - 60f * scale)
                    lineTo(handCenterX + 20f * scale, gripY - 70f * scale)
                    lineTo(handCenterX + 20f * scale, gripY - 40f * scale)
                    lineTo(handCenterX - 4f * scale, gripY - 50f * scale)
                    close()
                }
                4 -> { // hammer
                    addRect(Rect(Offset(handCenterX - 14f * scale, gripY - 74f * scale), Size(28f * scale, 20f * scale)))
                }
                5 -> addRect(Rect(Offset(handCenterX - 3f * scale, gripY - 90f * scale), Size(6f * scale, 80f * scale))) // spear
                6 -> { // bow
                    moveTo(handCenterX, gripY - 80f * scale)
                    quadraticTo(handCenterX + 18f * scale, gripY - 40f * scale, handCenterX, gripY)
                    quadraticTo(handCenterX - 6f * scale, gripY - 40f * scale, handCenterX, gripY - 80f * scale)
                    close()
                }
                7 -> addOval(Rect(Offset(handCenterX - 10f * scale, gripY - 84f * scale), Size(20f * scale, 20f * scale))) // magic orb (no shaft)
                else -> { // 0: sword
                    addRect(Rect(Offset(handCenterX - 14f * scale, gripY - 14f * scale), Size(28f * scale, 7f * scale)))
                    addRect(Rect(Offset(handCenterX - 5f * scale, gripY - 84f * scale), Size(10f * scale, 70f * scale)))
                }
            }
        }
    }, block = { path, bounds ->
        when (weapon.styleIndex.mod(8)) {
            1 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(lighten(primary, 0.3f), primary)))
                drawStar(Offset(bounds.center.x, bounds.top), 6f * scale, secondary)
            }
            2 -> {
                drawPath(path, brush = Brush.verticalGradient(listOf(lighten(primary, 0.3f), primary)))
                drawCircle(secondary, radius = 8f * scale, center = Offset(bounds.center.x, bounds.top))
            }
            6 -> drawPath(path, color = primary, style = Stroke(width = 3f * scale))
            7 -> drawCircle(brush = Brush.radialGradient(listOf(lighten(primary, 0.4f), primary)), radius = 10f * scale, center = bounds.center)
            else -> drawPath(path, brush = Brush.verticalGradient(listOf(lighten(primary, 0.3f), primary)))
        }
    })
}

private fun DrawScope.drawHandLeftItem(
    item: WardrobeItem,
    rightArmLeft: Float,
    torsoTop: Float,
    sleeveHeight: Float,
    scale: Float,
    auraPhase: Float
) {
    val primary = Color(item.primaryColorHex)
    val secondary = Color(item.secondaryColorHex)
    val shieldTopLeft = Offset(rightArmLeft - 2f * scale, torsoTop + sleeveHeight - 2f * scale)
    val pivot = Offset(shieldTopLeft.x + 20f * scale, shieldTopLeft.y + 24f * scale)

    withItemDecoration(item, pivot, secondary, scale, auraPhase, shapeBuilder = {
        Path().apply {
            when (item.styleIndex.mod(8)) {
                1 -> { // kite shield
                    moveTo(shieldTopLeft.x + 20f * scale, shieldTopLeft.y)
                    lineTo(shieldTopLeft.x + 40f * scale, shieldTopLeft.y + 14f * scale)
                    lineTo(shieldTopLeft.x + 20f * scale, shieldTopLeft.y + 48f * scale)
                    lineTo(shieldTopLeft.x, shieldTopLeft.y + 14f * scale)
                    close()
                }
                2 -> addRect(Rect(shieldTopLeft, Size(36f * scale, 44f * scale))) // book
                3 -> addOval(Rect(shieldTopLeft, Size(36f * scale, 44f * scale))) // lantern
                4 -> addOval(Rect(shieldTopLeft, Size(40f * scale, 40f * scale))) // compass
                5 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(shieldTopLeft, Size(30f * scale, 46f * scale)), CornerRadius(10f * scale, 10f * scale))) // hourglass
                6 -> addRect(Rect(Offset(shieldTopLeft.x + 14f * scale, shieldTopLeft.y), Size(8f * scale, 44f * scale))) // torch stick
                7 -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(shieldTopLeft, Size(26f * scale, 36f * scale)), CornerRadius(8f * scale, 8f * scale))) // potion
                else -> addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(shieldTopLeft, Size(40f * scale, 48f * scale)), CornerRadius(6f * scale, 6f * scale))) // round-ish shield
            }
        }
    }, block = { path, bounds ->
        drawPath(path, color = primary)
        when (item.styleIndex.mod(8)) {
            0, 1 -> {
                drawPath(path, color = primary, style = Stroke(width = 3f * scale))
                drawCircle(secondary, radius = 5f * scale, center = bounds.center)
            }
            6 -> drawGlowHalo(Offset(bounds.center.x, bounds.top), 14f * scale, Color(0xFFFF7043))
            else -> drawPath(path, color = darken(primary, 0.7f), style = Stroke(width = 2f * scale))
        }
    })
}

// ---------------------------------------------------------------------------------------------
// Scene assembly
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawLowPolyAvatarScene(
    appearance: AvatarAppearance,
    equippedItems: Map<EquipmentSlot, WardrobeItem>,
    pose: ProceduralIdlePose,
    yawAngleDeg: Float,
    hasLegendary: Boolean
) {
    val cx = size.width / 2f
    val cy = size.height * 0.56f
    val scale = (minOf(size.width, size.height) / 340f).coerceAtLeast(0.45f)
    val yawParallax = sin(yawAngleDeg * (PI / 180.0)).toFloat() * 14f * scale
    val blockCorner = 3f * scale
    val auraPhase = pose.legendaryAuraPhase

    // Roblox-style block proportions (1 unit ~= 40px*scale wide), anchored by leg/foot stance.
    val legsBottom = cy + 96f * scale
    val legsTop = legsBottom - 80f * scale
    val torsoBottom = legsTop
    val torsoTop = torsoBottom - 80f * scale
    val torsoLeft = cx - 40f * scale
    val torsoRight = cx + 40f * scale
    val headBottom = torsoTop - 1.5f * scale
    val headTop = headBottom - 52f * scale
    val headLeft = cx - 28f * scale + yawParallax * 0.35f

    // 1. Pedestal shadow & 3D stage ring (anchored under the feet)
    drawOval(
        color = Color(0x33000000),
        topLeft = Offset(cx - 88f * scale, legsBottom + 2f * scale),
        size = Size(176f * scale, 36f * scale)
    )
    drawOval(
        brush = Brush.linearGradient(
            colors = if (hasLegendary) {
                listOf(Color(0xFFF39C12), Color(0xFFE67E22))
            } else {
                listOf(Color(0xFF4A90E2), Color(0xFF357ABD))
            }
        ),
        topLeft = Offset(cx - 82f * scale, legsBottom - 2f * scale),
        size = Size(164f * scale, 28f * scale),
        style = Stroke(width = 4f * scale)
    )

    // 2. Legendary Aura Particles (if any Legendary item equipped)
    if (hasLegendary) {
        for (i in 0 until 8) {
            val angle = (pose.legendaryAuraPhase * 2 * PI + i * (PI / 4)).toFloat()
            val px = cx + cos(angle) * 95f * scale
            val py = cy - 15f * scale + sin(angle * 2f) * 65f * scale
            drawCircle(
                color = Color(0xFFFFD54F),
                radius = (5f + (i % 3) * 2f) * scale,
                center = Offset(px, py)
            )
        }
    }

    val skinColor = Color(appearance.primaryTintHex)
    val darkerSkin = darken(skinColor, 0.78f)
    val shirtItem = equippedItems[EquipmentSlot.ARMOR_SHIRT]
    val pantsItem = equippedItems[EquipmentSlot.PANTS]
    val shoesItem = equippedItems[EquipmentSlot.SHOES]
    val glovesItem = equippedItems[EquipmentSlot.ARMS_GLOVES]
    val shirtColor = shirtItem?.let { Color(it.primaryColorHex) } ?: Color(0xFF4A90E2)
    val pantsColor = pantsItem?.let { Color(it.primaryColorHex) } ?: Color(0xFF34495E)
    val shoesColor = shoesItem?.let { Color(it.primaryColorHex) } ?: Color(0xFF2C3E50)
    val handColor = glovesItem?.let { Color(it.primaryColorHex) } ?: skinColor
    val handSecondary = glovesItem?.let { Color(it.secondaryColorHex) } ?: skinColor

    // 3. Slot: SHOULDERS_BACK
    equippedItems[EquipmentSlot.SHOULDERS_BACK]?.let { back ->
        drawShoulders(back, cx, torsoTop, legsTop, yawParallax, scale, auraPhase)
    }

    // 4. Slot: TAIL (Wagging rigid-body bone, drawn behind legs/torso from lower back)
    val tailItem = equippedItems[EquipmentSlot.TAIL]
    val showTail = !appearance.avatarType.isHumanoid || tailItem != null
    if (showTail) {
        drawTail(tailItem, darkerSkin, torsoRight, torsoBottom, pose.tailWagAngleDeg, scale, auraPhase)
    }

    // 5. Slot: PANTS/SHOES & Legs (flush against each other and the torso)
    val legWidth = 40f * scale
    val legHeight = 80f * scale
    val shoeHeight = 18f * scale
    val shoeOutset = 3f * scale
    val leftLegTopLeft = Offset(cx - legWidth, legsTop)
    val rightLegTopLeft = Offset(cx, legsTop)
    val pantsStyle = pantsItem?.styleIndex?.mod(8)
    if (pantsStyle == 1) {
        // shorts: draw bare skin leg below the short pants
        drawBlock(skinColor, Offset(leftLegTopLeft.x, legsTop + (legHeight - shoeHeight) * 0.45f), Size(legWidth, (legHeight - shoeHeight) * 0.55f), blockCorner, yawParallax, scale)
        drawBlock(skinColor, Offset(rightLegTopLeft.x, legsTop + (legHeight - shoeHeight) * 0.45f), Size(legWidth, (legHeight - shoeHeight) * 0.55f), blockCorner, yawParallax, scale)
    }
    drawPants(pantsItem, pantsColor, leftLegTopLeft, rightLegTopLeft, legWidth, legHeight, shoeHeight, blockCorner, yawParallax, scale, auraPhase)
    drawShoes(
        shoesItem,
        shoesColor,
        Offset(leftLegTopLeft.x - shoeOutset, legsBottom - shoeHeight),
        Offset(rightLegTopLeft.x - shoeOutset, legsBottom - shoeHeight),
        Size(legWidth + shoeOutset * 2f, shoeHeight),
        blockCorner,
        yawParallax,
        scale,
        auraPhase
    )
    // Seam between the legs
    drawLine(
        color = darken(pantsColor, 0.6f),
        start = Offset(cx, legsTop + 2f * scale),
        end = Offset(cx, legsBottom - shoeHeight - 2f * scale),
        strokeWidth = 1.5f * scale
    )

    // 6. Torso & Slot: ARMOR_SHIRT (with breathing scale, pivot at torso bottom)
    val torsoWidth = 80f * scale
    val torsoHeight = 80f * scale
    withTransform({
        scale(scaleX = 1.0f, scaleY = pose.torsoBreathScaleY, pivot = Offset(cx, torsoBottom))
    }) {
        drawArmorShirt(
            shirtItem, shirtColor, torsoLeft, torsoTop, torsoWidth, torsoHeight, torsoBottom, legsBottom,
            blockCorner, yawParallax, scale, auraPhase
        )
    }

    // 7. Left & Right Arms + Slot: ARMS_GLOVES + Slots: HAND_LEFT & HAND_RIGHT
    val armWidth = 38f * scale
    val armHeight = 80f * scale
    val sleeveHeight = armHeight * 0.6f
    val handHeight = armHeight - sleeveHeight

    // Left Arm (viewer's left) - holds HAND_RIGHT equipment (character's main hand)
    val leftArmLeft = torsoLeft - armWidth
    withTransform({
        rotate(degrees = pose.leftArmAngleDeg, pivot = Offset(leftArmLeft + armWidth / 2f, torsoTop))
    }) {
        drawBlock(shirtColor, Offset(leftArmLeft, torsoTop), Size(armWidth, sleeveHeight), blockCorner, yawParallax, scale)
        drawGlovesBlock(
            glovesItem, handColor, handSecondary,
            Offset(leftArmLeft, torsoTop + sleeveHeight), Size(armWidth, handHeight),
            blockCorner, yawParallax, scale, auraPhase
        )
        // Slot: HAND_RIGHT (Sword / Wand gripped in the character's main hand)
        equippedItems[EquipmentSlot.HAND_RIGHT]?.let { weapon ->
            val handCenterX = leftArmLeft + armWidth / 2f
            val gripY = torsoTop + sleeveHeight + handHeight * 0.5f
            drawHandRightWeapon(weapon, handCenterX, gripY, scale, auraPhase)
        }
    }

    // Right Arm (viewer's right) - holds HAND_LEFT equipment
    val rightArmLeft = torsoRight
    withTransform({
        rotate(degrees = pose.rightArmAngleDeg, pivot = Offset(rightArmLeft + armWidth / 2f, torsoTop))
    }) {
        drawBlock(shirtColor, Offset(rightArmLeft, torsoTop), Size(armWidth, sleeveHeight), blockCorner, yawParallax, scale)
        drawGlovesBlock(
            glovesItem, handColor, handSecondary,
            Offset(rightArmLeft, torsoTop + sleeveHeight), Size(armWidth, handHeight),
            blockCorner, yawParallax, scale, auraPhase
        )
        // Slot: HAND_LEFT (Shield / off-hand item)
        equippedItems[EquipmentSlot.HAND_LEFT]?.let { item ->
            drawHandLeftItem(item, rightArmLeft, torsoTop, sleeveHeight, scale, auraPhase)
        }
    }

    // 8. Head (with rigid-body vertical bob & Roblox blocky charm, narrower than the torso)
    val headBobOffset = pose.headBobOffsetY * scale * 0.5f
    val headWidth = 56f * scale
    val headHeight = 52f * scale
    val animatedHeadTop = headTop + headBobOffset
    val animatedHeadBottom = headBottom + headBobOffset

    // Cat / Dog ears if animal avatar (attached to head top, follow the head bob)
    if (appearance.avatarType == AvatarType.CAT) {
        val leftEar = Path().apply {
            moveTo(headLeft + 2f * scale, animatedHeadTop + 10f * scale)
            lineTo(headLeft + 12f * scale, animatedHeadTop - 16f * scale)
            lineTo(headLeft + 22f * scale, animatedHeadTop + 10f * scale)
            close()
        }
        val rightEar = Path().apply {
            moveTo(headLeft + headWidth - 22f * scale, animatedHeadTop + 10f * scale)
            lineTo(headLeft + headWidth - 12f * scale, animatedHeadTop - 16f * scale)
            lineTo(headLeft + headWidth - 2f * scale, animatedHeadTop + 10f * scale)
            close()
        }
        drawPath(leftEar, color = darkerSkin)
        drawPath(rightEar, color = darkerSkin)
    } else if (appearance.avatarType == AvatarType.DOG) {
        drawRoundRect(
            color = darkerSkin,
            topLeft = Offset(headLeft - 4f * scale, animatedHeadTop + 8f * scale),
            size = Size(10f * scale, 28f * scale),
            cornerRadius = CornerRadius(4f * scale, 4f * scale)
        )
        drawRoundRect(
            color = darkerSkin,
            topLeft = Offset(headLeft + headWidth - 6f * scale, animatedHeadTop + 8f * scale),
            size = Size(10f * scale, 28f * scale),
            cornerRadius = CornerRadius(4f * scale, 4f * scale)
        )
    }

    // Main Roblox-style head block
    drawBlock(
        skinColor,
        Offset(headLeft, animatedHeadTop),
        Size(headWidth, headHeight),
        12f * scale,
        yawParallax,
        scale
    )

    // Expressive eyes & friendly smile (shifted for turntable parallax)
    val faceShiftX = yawParallax * 0.35f
    val eyeW = if (appearance.eyeExpressionIndex % 2 == 1) 8f * scale else 6f * scale
    val eyeH = if (appearance.eyeExpressionIndex % 2 == 1) 12f * scale else 9f * scale
    val eyeY = animatedHeadTop + headHeight * 0.4f
    val leftEyeCenter = Offset(cx - 14f * scale + faceShiftX, eyeY)
    val rightEyeCenter = Offset(cx + 14f * scale + faceShiftX, eyeY)
    drawOval(
        color = Color(0xFF1E272E),
        topLeft = Offset(leftEyeCenter.x - eyeW / 2f, leftEyeCenter.y - eyeH / 2f),
        size = Size(eyeW, eyeH)
    )
    drawOval(
        color = Color(0xFF1E272E),
        topLeft = Offset(rightEyeCenter.x - eyeW / 2f, rightEyeCenter.y - eyeH / 2f),
        size = Size(eyeW, eyeH)
    )
    drawCircle(Color.White, radius = 1.6f * scale, center = leftEyeCenter - Offset(0.8f * scale, eyeH * 0.22f))
    drawCircle(Color.White, radius = 1.6f * scale, center = rightEyeCenter - Offset(0.8f * scale, eyeH * 0.22f))

    // Smile as a stroked arc
    val smileWide = appearance.smileExpressionIndex % 2 != 0
    val smileSize = Size(26f * scale, 18f * scale)
    val smileTopLeft = Offset(cx - smileSize.width / 2f + faceShiftX, animatedHeadTop + headHeight * 0.58f)
    val smileSweep = if (smileWide) 160f else 120f
    val smileStart = (180f - smileSweep) / 2f
    drawArc(
        color = Color(0xFF1E272E),
        startAngle = smileStart,
        sweepAngle = smileSweep,
        useCenter = false,
        topLeft = smileTopLeft,
        size = smileSize,
        style = Stroke(width = 3f * scale, cap = StrokeCap.Round)
    )

    // 9. Slot: HAT (Anchored to the head top, follows the head bob)
    equippedItems[EquipmentSlot.HAT]?.let { hat ->
        drawHat(hat, headLeft, headWidth, animatedHeadTop, cx, yawParallax, scale, blockCorner, auraPhase)
    }
}

// ---------------------------------------------------------------------------------------------
// WardrobeItemPreview rendering — draws a single item centered & fitted, used by item cards.
// ---------------------------------------------------------------------------------------------

/**
 * Extra per-slot shrink applied on top of the base preview [scale] so that the widest/tallest
 * silhouette for that slot (wings/cape/balloon for shoulders, top-hat/dome for hats, spear for
 * hand_right) still fits inside the ~110-unit preview box with a small margin, even at the
 * LEGENDARY `scaleMultiplier` (1.15x). Sized from the worst-case extents measured in the
 * per-style draw functions below (e.g. shoulders wings reach `cx +/- 70*scale`, cape bottom
 * reaches `bottomY + 30*scale` past `legsTop`, hand_right spear reaches `gripY - ~63*scale`).
 */
private fun previewFitScale(slot: EquipmentSlot): Float = when (slot) {
    EquipmentSlot.SHOULDERS_BACK -> 0.55f
    EquipmentSlot.HAT -> 0.7f
    EquipmentSlot.HAND_RIGHT -> 0.7f
    else -> 1.0f
}

private fun DrawScope.drawWardrobeItemPreview(item: WardrobeItem, auraPhase: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseScale = (minOf(size.width, size.height) / 110f).coerceAtLeast(0.3f)
    val scale = baseScale * previewFitScale(item.slot)
    val blockCorner = 3f * scale
    val yawParallax = 4f * scale

    when (item.slot) {
        EquipmentSlot.HAT -> {
            val headWidth = 40f * scale
            val headLeft = cx - headWidth / 2f
            val headTop = cy + 6f * scale
            drawBlock(Color(0xFFE8C9A0), Offset(headLeft, headTop), Size(headWidth, headWidth * 0.8f), 10f * scale, yawParallax, scale)
            drawHat(item, headLeft, headWidth, headTop, cx, yawParallax, scale, blockCorner, auraPhase)
        }
        EquipmentSlot.SHOULDERS_BACK -> {
            drawShoulders(item, cx, cy - 30f * scale, cy + 32f * scale, yawParallax, scale, auraPhase)
        }
        EquipmentSlot.ARMS_GLOVES -> {
            val handSize = Size(30f * scale, 34f * scale)
            val topLeft = Offset(cx - handSize.width / 2f, cy - handSize.height / 2f)
            drawGlovesBlock(item, Color(item.primaryColorHex), Color(item.secondaryColorHex), topLeft, handSize, blockCorner, yawParallax, scale, auraPhase)
        }
        EquipmentSlot.ARMOR_SHIRT -> {
            drawArmorShirt(
                item, Color(item.primaryColorHex),
                cx - 30f * scale, cy - 30f * scale, 60f * scale, 60f * scale,
                cy + 30f * scale, cy + 46f * scale,
                blockCorner, yawParallax, scale, auraPhase
            )
        }
        EquipmentSlot.PANTS -> {
            val legWidth = 20f * scale
            val legHeight = 46f * scale
            val shoeHeight = 0f
            drawPants(
                item, Color(item.primaryColorHex),
                Offset(cx - legWidth, cy - legHeight / 2f), Offset(cx, cy - legHeight / 2f),
                legWidth, legHeight, shoeHeight, blockCorner, yawParallax, scale, auraPhase
            )
        }
        EquipmentSlot.SHOES -> {
            val shoeSize = Size(24f * scale, 14f * scale)
            drawShoes(
                item, Color(item.primaryColorHex),
                Offset(cx - shoeSize.width - 2f * scale, cy), Offset(cx + 2f * scale, cy),
                shoeSize, blockCorner, yawParallax, scale, auraPhase
            )
        }
        EquipmentSlot.HAND_RIGHT -> {
            drawHandRightWeapon(item, cx, cy + 40f * scale, scale, auraPhase)
        }
        EquipmentSlot.HAND_LEFT -> {
            drawHandLeftItem(item, cx - 18f * scale, cy - 24f * scale, 0f, scale, auraPhase)
        }
        EquipmentSlot.TAIL -> {
            drawTail(item, Color(item.primaryColorHex), cx - 6f * scale, cy, 10f, scale, auraPhase)
        }
    }
}
