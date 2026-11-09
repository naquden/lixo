package se.lixo.app.ui.components.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.CONTENT_EYE_ARGB
import se.lixo.app.domain.content.ContentAvatar
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ContentLayer
import se.lixo.app.domain.content.Shading
import se.lixo.app.domain.content.ZBucket
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.Rarity

/**
 * `DrawScope` layer interpreter for the content-pack-driven avatar/item renderer (020). Pairs
 * with the pure geometry in `LayerGeometry.kt`: this file only draws, never computes placement.
 */

internal val ContentItem.drawsGlowHalo: Boolean get() = ornament == ItemOrnament.GLOW || rarity == Rarity.LEGENDARY
internal val ContentItem.drawsSparkle: Boolean get() = ornament == ItemOrnament.SPARKLE || rarity == Rarity.LEGENDARY

private fun ContentItem.secondaryColor(): Color = palette.secondary.let(::parseContentHex)?.let(::Color) ?: Color.Gray

/** The stage ring + shadow, copied from the old `drawLowPolyAvatarScene` (`Avatar3DController.kt`). */
fun DrawScope.drawStage(frame: RigFrame, hasLegendary: Boolean) {
    val cx = frame.feet.x
    val legsBottom = frame.feet.y
    val scale = frame.pxPerAu
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
}

/** The legendary aura particles, copied from the old `drawLowPolyAvatarScene`. */
fun DrawScope.drawLegendaryAura(frame: RigFrame, auraPhase: Float) {
    val cx = frame.feet.x
    val scale = frame.pxPerAu
    val cy = frame.feet.y - 96f * scale
    for (i in 0 until 8) {
        val angle = (auraPhase * 2 * PI + i * (PI / 4)).toFloat()
        val px = cx + cos(angle) * 95f * scale
        val py = cy - 15f * scale + sin(angle * 2f) * 65f * scale
        drawCircle(
            color = Color(0xFFFFD54F),
            radius = (5f + (i % 3) * 2f) * scale,
            center = Offset(px, py)
        )
    }
}

private fun DrawScope.drawPlacedLayer(
    content: ActiveContent,
    p: PlacedLayer,
    bones: Map<Bone, BoneTransform>,
    alpha: Float
) {
    val layer = p.layer
    val path = content.parsedPath(layer)
    val b = content.layerBounds(layer)
    val fill = p.fillColor

    when (layer.shading) {
        Shading.FLAT -> withTransform({ transform(layerMatrix(p, bones)) }) {
            drawPath(path, color = fill, alpha = alpha)
        }
        Shading.GRADIENT -> withTransform({ transform(layerMatrix(p, bones)) }) {
            drawPath(
                path,
                brush = Brush.verticalGradient(listOf(fill, darken(fill, 0.85f)), startY = b.top, endY = b.bottom),
                alpha = alpha
            )
        }
        Shading.BLOCK -> {
            val t = bones[p.bone] ?: error("no BoneTransform for ${p.bone}")
            val depth = layer.depth ?: 0f
            val dir = if (t.yawPx >= 0f) 1f else -1f
            withTransform({ transform(layerMatrix(p, bones, sideOffsetAu = dir * depth)) }) {
                drawPath(path, color = darken(fill, 0.7f), alpha = alpha)
            }
            withTransform({ transform(layerMatrix(p, bones)) }) {
                drawPath(
                    path,
                    brush = Brush.verticalGradient(listOf(fill, darken(fill, 0.9f)), startY = b.top, endY = b.bottom),
                    alpha = alpha
                )
                clipPath(path) {
                    drawRect(
                        color = lighten(fill, 0.3f),
                        topLeft = b.topLeft,
                        size = Size(b.width, min(4f, b.height * 0.3f)),
                        alpha = alpha
                    )
                }
            }
        }
    }

    val strokeDef = layer.stroke
    val strokeColor = p.strokeColor
    if (strokeDef != null && strokeColor != null) {
        withTransform({ transform(layerMatrix(p, bones)) }) {
            drawPath(path, color = strokeColor, style = Stroke(width = strokeDef.width), alpha = alpha)
        }
    }
}

/** The face: eyes + smile, designed once in a 56×56 au box and stretched to the avatar's effective
 * HEAD box by [faceMatrix], per approach F step 4. */
private fun DrawScope.drawFace(avatar: ContentAvatar, eyeIndex: Int, smileIndex: Int, bones: Map<Bone, BoneTransform>) {
    withTransform({ transform(faceMatrix(bones)) }) {
        val color = Color(CONTENT_EYE_ARGB)
        val eyeStyles = avatar.face.eyeStyles.coerceAtLeast(1)
        val smileStyles = avatar.face.smileStyles.coerceAtLeast(1)

        val eyeWide = eyeIndex.mod(eyeStyles) % 2 == 1
        val eyeW = if (eyeWide) 8f else 6f
        val eyeH = if (eyeWide) 12f else 9f
        val eyeY = 0.4f * 56f
        val leftCenter = Offset(28f - 14f, eyeY)
        val rightCenter = Offset(28f + 14f, eyeY)
        for (center in listOf(leftCenter, rightCenter)) {
            drawOval(
                color = color,
                topLeft = Offset(center.x - eyeW / 2f, center.y - eyeH / 2f),
                size = Size(eyeW, eyeH)
            )
            drawCircle(Color.White, radius = 1.6f, center = center - Offset(0.8f, eyeH * 0.22f))
        }

        val smileWide = smileIndex.mod(smileStyles) % 2 == 1
        val sweep = if (smileWide) 160f else 120f
        val start = (180f - sweep) / 2f
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(28f - 13f, 0.58f * 56f),
            size = Size(26f, 18f),
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
    }
}

/** The layers with `patternClip == true`, or every one of [item]'s layers if none have it. */
private fun decorationTargets(item: ContentItem): Set<ContentLayer> {
    val clipped = item.layers.filter { it.patternClip }
    return (if (clipped.isNotEmpty()) clipped else item.layers).toSet()
}

/**
 * Draws [layers] in their already-sorted order, interleaving each item's halo/pattern/ornament
 * decoration with the global z-order (approach H), and inserting [face] right before the first
 * layer at or past [se.lixo.app.domain.content.ZBucket.FACE] (or at the end, if none reach it).
 */
fun DrawScope.drawLayerList(
    content: ActiveContent,
    layers: List<PlacedLayer>,
    bones: Map<Bone, BoneTransform>,
    auraPhase: Float,
    alpha: Float = 1f,
    face: (DrawScope.() -> Unit)? = null
) {
    val firstIndexByItem = HashMap<String, Int>()
    val lastIndexByItem = HashMap<String, Int>()
    layers.forEachIndexed { i, p ->
        val id = p.item?.id ?: return@forEachIndexed
        firstIndexByItem.putIfAbsent(id, i)
        lastIndexByItem[id] = i
    }
    val unionCache = HashMap<String, Rect?>()
    fun unionFor(itemId: String): Rect? = unionCache.getOrPut(itemId) { itemUnionPx(content, layers, itemId, bones) }

    var faceDrawn = false

    layers.forEachIndexed { i, p ->
        if (!faceDrawn && face != null && p.layer.z.ordinal >= ZBucket.FACE.ordinal) {
            face()
            faceDrawn = true
        }

        val item = p.item
        if (item != null && firstIndexByItem[item.id] == i && item.drawsGlowHalo) {
            unionFor(item.id)?.let { u -> drawGlowHalo(u.center, u.maxDimension * 0.7f, item.secondaryColor()) }
        }

        drawPlacedLayer(content, p, bones, alpha)

        if (item != null) {
            if (p.layer in decorationTargets(item)) {
                withTransform({ transform(layerMatrix(p, bones)) }) {
                    val path = content.parsedPath(p.layer)
                    val b = content.layerBounds(p.layer)
                    drawPatternFill(path, b, item.pattern, item.secondaryColor())
                    if (item.ornament == ItemOrnament.TRIM) {
                        drawPath(path, color = item.secondaryColor(), style = Stroke(width = 2.5f))
                    }
                }
            }
            if (lastIndexByItem[item.id] == i) {
                val pxPerAu = bones[p.bone]?.pxPerAu ?: 1f
                unionFor(item.id)?.let { u ->
                    if (item.ornament == ItemOrnament.GEM) drawGem(u, item.secondaryColor(), pxPerAu)
                    if (item.drawsSparkle) drawSparkle(u, item.secondaryColor(), pxPerAu, auraPhase)
                }
            }
        }
    }

    if (!faceDrawn) face?.invoke(this)
}

/**
 * Draws [appearance] on [content]'s avatar: the stage, the legendary aura (if any equipped item
 * is LEGENDARY), then its sorted layers with the face inserted at the FACE bucket.
 */
fun DrawScope.drawAvatar(
    content: ActiveContent,
    appearance: AvatarAppearance,
    pose: ProceduralIdlePose,
    yaw: Float,
    auraPhase: Float,
    drawList: AvatarDrawList = buildAvatarDrawList(content, appearance)
) {
    val frame = avatarFrame(size, drawList.avatar)
    val bones = boneTransforms(drawList.avatar, pose, yaw, frame)
    drawStage(frame, drawList.hasLegendary)
    if (drawList.hasLegendary) drawLegendaryAura(frame, auraPhase)
    drawLayerList(
        content = content,
        layers = drawList.layers,
        bones = bones,
        auraPhase = auraPhase,
        alpha = 1f,
        face = { drawFace(drawList.avatar, appearance.eyeIndex, appearance.smileIndex, bones) }
    )
}

/**
 * Draws a single item's layers at rest, fitted to the canvas with a 10% margin (no stage, no
 * aura, no face). Draws the default avatar's matching ghost-body layers first, at alpha 0.25,
 * when `item.preview?.ghostBody == true`.
 */
fun DrawScope.drawItemPreview(
    content: ActiveContent,
    item: ContentItem,
    auraPhase: Float,
    drawList: ItemDrawList = buildItemDrawList(content, item)
) {
    val yawDeg = previewYawDeg(item)
    val frame = previewFrame(content, drawList, yawDeg, size) ?: return
    val bones = boneTransforms(drawList.avatar, REST_POSE, yawDeg, frame)

    if (drawList.ghostLayers.isNotEmpty()) {
        drawLayerList(content, drawList.ghostLayers, bones, auraPhase, alpha = 0.25f)
    }
    drawLayerList(content, drawList.layers, bones, auraPhase, alpha = 1f)
}
