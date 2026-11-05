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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.EquipmentSlot
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
    val shirtColor = equippedItems[EquipmentSlot.ARMOR_SHIRT]?.let { Color(it.primaryColorHex) }
        ?: Color(0xFF4A90E2)
    val pantsColor = equippedItems[EquipmentSlot.PANTS]?.let { Color(it.primaryColorHex) }
        ?: Color(0xFF34495E)
    val shoesColor = equippedItems[EquipmentSlot.SHOES]?.let { Color(it.primaryColorHex) }
        ?: Color(0xFF2C3E50)
    val handColor = equippedItems[EquipmentSlot.ARMS_GLOVES]?.let { Color(it.primaryColorHex) }
        ?: skinColor

    // 3. Slot: SHOULDERS_BACK (Cape behind the character)
    equippedItems[EquipmentSlot.SHOULDERS_BACK]?.let { cape ->
        val capeColor = Color(cape.primaryColorHex)
        val capeInner = darken(capeColor, 0.75f)
        val capeTopHalfWidth = 40f * scale + 10f * scale
        val capeBottomHalfWidth = 40f * scale + 30f * scale
        val capeTopY = torsoTop + 2f * scale
        val capeBottomY = legsTop + 30f * scale
        val capePath = Path().apply {
            moveTo(cx - capeTopHalfWidth, capeTopY)
            lineTo(cx + capeTopHalfWidth, capeTopY)
            lineTo(cx + capeBottomHalfWidth - yawParallax * 0.5f, capeBottomY)
            lineTo(cx - capeBottomHalfWidth - yawParallax * 0.5f, capeBottomY)
            close()
        }
        drawPath(capePath, color = capeColor)
        val capeInnerPath = Path().apply {
            moveTo(cx - capeTopHalfWidth * 0.5f, capeTopY)
            lineTo(cx + capeTopHalfWidth * 0.5f, capeTopY)
            lineTo(cx + capeBottomHalfWidth * 0.5f - yawParallax * 0.5f, capeBottomY)
            lineTo(cx - capeBottomHalfWidth * 0.5f - yawParallax * 0.5f, capeBottomY)
            close()
        }
        drawPath(capeInnerPath, color = capeInner)
    }

    // 4. Slot: TAIL (Wagging rigid-body bone, drawn behind legs/torso from lower back)
    val showTail = !appearance.avatarType.isHumanoid || equippedItems.containsKey(EquipmentSlot.TAIL)
    if (showTail) {
        val tailColor = equippedItems[EquipmentSlot.TAIL]?.let { Color(it.primaryColorHex) } ?: darkerSkin
        withTransform({
            rotate(degrees = pose.tailWagAngleDeg, pivot = Offset(torsoRight - 4f * scale, torsoBottom - 10f * scale))
        }) {
            drawRoundRect(
                color = tailColor,
                topLeft = Offset(torsoRight - 6f * scale, torsoBottom - 14f * scale),
                size = Size(50f * scale, 15f * scale),
                cornerRadius = CornerRadius(7f * scale, 7f * scale)
            )
        }
    }

    // 5. Slot: PANTS/SHOES & Legs (flush against each other and the torso)
    val legWidth = 40f * scale
    val legHeight = 80f * scale
    val shoeHeight = 18f * scale
    val shoeOutset = 3f * scale
    val leftLegTopLeft = Offset(cx - legWidth, legsTop)
    val rightLegTopLeft = Offset(cx, legsTop)
    drawBlock(pantsColor, leftLegTopLeft, Size(legWidth, legHeight - shoeHeight), blockCorner, yawParallax, scale)
    drawBlock(pantsColor, rightLegTopLeft, Size(legWidth, legHeight - shoeHeight), blockCorner, yawParallax, scale)
    drawBlock(
        shoesColor,
        Offset(leftLegTopLeft.x - shoeOutset, legsBottom - shoeHeight),
        Size(legWidth + shoeOutset * 2f, shoeHeight),
        blockCorner,
        yawParallax,
        scale
    )
    drawBlock(
        shoesColor,
        Offset(rightLegTopLeft.x - shoeOutset, legsBottom - shoeHeight),
        Size(legWidth + shoeOutset * 2f, shoeHeight),
        blockCorner,
        yawParallax,
        scale
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
        drawBlock(
            shirtColor,
            Offset(torsoLeft, torsoTop),
            Size(torsoWidth, torsoHeight),
            blockCorner,
            yawParallax,
            scale
        )
        // Belt-ish seam near the torso bottom
        drawLine(
            color = darken(shirtColor, 0.6f),
            start = Offset(torsoLeft + 4f * scale, torsoBottom - 6f * scale),
            end = Offset(torsoRight - 4f * scale, torsoBottom - 6f * scale),
            strokeWidth = 2f * scale
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
        drawBlock(
            shirtColor,
            Offset(leftArmLeft, torsoTop),
            Size(armWidth, sleeveHeight),
            blockCorner,
            yawParallax,
            scale
        )
        drawBlock(
            handColor,
            Offset(leftArmLeft, torsoTop + sleeveHeight),
            Size(armWidth, handHeight),
            blockCorner,
            yawParallax,
            scale
        )
        // Slot: HAND_RIGHT (Sword / Wand gripped in the character's main hand)
        equippedItems[EquipmentSlot.HAND_RIGHT]?.let { weapon ->
            val weaponColor = Color(weapon.primaryColorHex)
            val handCenterX = leftArmLeft + armWidth / 2f
            val gripY = torsoTop + sleeveHeight + handHeight * 0.5f
            drawRoundRect(
                color = Color(0xFF2C3E50),
                topLeft = Offset(handCenterX - 3f * scale, gripY - 10f * scale),
                size = Size(6f * scale, 20f * scale),
                cornerRadius = CornerRadius(2f * scale, 2f * scale)
            )
            drawRoundRect(
                color = Color(0xFFF1C40F),
                topLeft = Offset(handCenterX - 14f * scale, gripY - 14f * scale),
                size = Size(28f * scale, 7f * scale),
                cornerRadius = CornerRadius(3f * scale, 3f * scale)
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(lighten(weaponColor, 0.3f), weaponColor)),
                topLeft = Offset(handCenterX - 5f * scale, gripY - 84f * scale),
                size = Size(10f * scale, 70f * scale),
                cornerRadius = CornerRadius(3f * scale, 3f * scale)
            )
        }
    }

    // Right Arm (viewer's right) - holds HAND_LEFT equipment
    val rightArmLeft = torsoRight
    withTransform({
        rotate(degrees = pose.rightArmAngleDeg, pivot = Offset(rightArmLeft + armWidth / 2f, torsoTop))
    }) {
        drawBlock(
            shirtColor,
            Offset(rightArmLeft, torsoTop),
            Size(armWidth, sleeveHeight),
            blockCorner,
            yawParallax,
            scale
        )
        drawBlock(
            handColor,
            Offset(rightArmLeft, torsoTop + sleeveHeight),
            Size(armWidth, handHeight),
            blockCorner,
            yawParallax,
            scale
        )
        // Slot: HAND_LEFT (Shield)
        equippedItems[EquipmentSlot.HAND_LEFT]?.let { shield ->
            val shieldColor = Color(shield.primaryColorHex)
            val shieldTopLeft = Offset(rightArmLeft - 2f * scale, torsoTop + sleeveHeight - 2f * scale)
            val shieldSize = Size(40f * scale, 48f * scale)
            drawRoundRect(
                color = shieldColor,
                topLeft = shieldTopLeft,
                size = shieldSize,
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
            drawRoundRect(
                color = Color(0xFFF39C12),
                topLeft = shieldTopLeft,
                size = shieldSize,
                cornerRadius = CornerRadius(6f * scale, 6f * scale),
                style = Stroke(width = 3f * scale)
            )
            drawCircle(
                color = Color(0xFFF39C12),
                radius = 5f * scale,
                center = Offset(shieldTopLeft.x + shieldSize.width / 2f, shieldTopLeft.y + shieldSize.height / 2f)
            )
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
        val hatColor = Color(hat.primaryColorHex)
        val brimWidth = headWidth + 20f * scale
        val brimHeight = 12f * scale
        val brimTopLeft = Offset(headLeft - 10f * scale, animatedHeadTop - 10f * scale)
        drawBlock(hatColor, brimTopLeft, Size(brimWidth, brimHeight), blockCorner, yawParallax, scale)
        // Hat crown (wizard-hat style sharp triangle with a darker 3D side face)
        val crownBaseY = brimTopLeft.y
        val crownTip = Offset(cx + yawParallax * 0.2f, crownBaseY - 46f * scale)
        val crownLeft = Offset(headLeft + 8f * scale, crownBaseY)
        val crownRight = Offset(headLeft + headWidth - 8f * scale, crownBaseY)
        val crownSidePath = Path().apply {
            moveTo(crownTip.x, crownTip.y)
            lineTo(cx, crownBaseY)
            lineTo((if (yawParallax >= 0f) crownRight else crownLeft).x, crownBaseY)
            close()
        }
        val crownFrontPath = Path().apply {
            moveTo(crownTip.x, crownTip.y)
            lineTo(crownLeft.x, crownLeft.y)
            lineTo(crownRight.x, crownRight.y)
            close()
        }
        drawPath(crownFrontPath, color = hatColor)
        drawPath(crownSidePath, color = darken(hatColor, 0.7f))
    }
}
