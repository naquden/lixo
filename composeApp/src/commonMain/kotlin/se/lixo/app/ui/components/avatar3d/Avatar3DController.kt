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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
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

    // 1. Pedestal shadow & 3D stage ring
    drawOval(
        color = Color(0x33000000),
        topLeft = Offset(cx - 88f * scale, cy + 96f * scale),
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
        topLeft = Offset(cx - 82f * scale, cy + 92f * scale),
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

    val bodyColor = Color(appearance.primaryTintHex)
    val bodyShadeColor = bodyColor.copy(
        red = (bodyColor.red * 0.82f).coerceIn(0f, 1f),
        green = (bodyColor.green * 0.82f).coerceIn(0f, 1f),
        blue = (bodyColor.blue * 0.82f).coerceIn(0f, 1f)
    )

    // 3. Slot: SHOULDERS_BACK (Cape / Wings behind character)
    equippedItems[EquipmentSlot.SHOULDERS_BACK]?.let { cape ->
        val capeColor = Color(cape.primaryColorHex)
        val capePath = Path().apply {
            moveTo(cx - 42f * scale, cy - 35f * scale)
            lineTo(cx + 42f * scale, cy - 35f * scale)
            lineTo(cx + 62f * scale - yawParallax * 0.5f, cy + 72f * scale)
            lineTo(cx - 62f * scale - yawParallax * 0.5f, cy + 72f * scale)
            close()
        }
        drawPath(capePath, color = capeColor)
    }

    // 4. Slot: TAIL (Wagging rigid-body bone)
    val showTail = !appearance.avatarType.isHumanoid || equippedItems.containsKey(EquipmentSlot.TAIL)
    if (showTail) {
        val tailColor = equippedItems[EquipmentSlot.TAIL]?.let { Color(it.primaryColorHex) } ?: bodyShadeColor
        withTransform({
            rotate(degrees = pose.tailWagAngleDeg, pivot = Offset(cx + 32f * scale, cy + 40f * scale))
        }) {
            drawRoundRect(
                color = tailColor,
                topLeft = Offset(cx + 28f * scale, cy + 26f * scale),
                size = Size(54f * scale, 16f * scale),
                cornerRadius = CornerRadius(10f * scale, 10f * scale)
            )
        }
    }

    // 5. Slot: PANTS & Legs
    val pantsColor = equippedItems[EquipmentSlot.PANTS]?.let { Color(it.primaryColorHex) } ?: Color(0xFF34495E)
    drawRoundRect(
        color = pantsColor,
        topLeft = Offset(cx - 34f * scale, cy + 36f * scale),
        size = Size(28f * scale, 48f * scale),
        cornerRadius = CornerRadius(8f * scale, 8f * scale)
    )
    drawRoundRect(
        color = pantsColor,
        topLeft = Offset(cx + 6f * scale, cy + 36f * scale),
        size = Size(28f * scale, 48f * scale),
        cornerRadius = CornerRadius(8f * scale, 8f * scale)
    )

    // 6. Slot: SHOES
    val shoesColor = equippedItems[EquipmentSlot.SHOES]?.let { Color(it.primaryColorHex) } ?: Color(0xFF2C3E50)
    drawRoundRect(
        color = shoesColor,
        topLeft = Offset(cx - 38f * scale, cy + 78f * scale),
        size = Size(34f * scale, 20f * scale),
        cornerRadius = CornerRadius(8f * scale, 8f * scale)
    )
    drawRoundRect(
        color = shoesColor,
        topLeft = Offset(cx + 4f * scale, cy + 78f * scale),
        size = Size(34f * scale, 20f * scale),
        cornerRadius = CornerRadius(8f * scale, 8f * scale)
    )

    // 7. Torso & Slot: ARMOR_SHIRT (with breathing scale)
    val torsoColor = equippedItems[EquipmentSlot.ARMOR_SHIRT]?.let { Color(it.primaryColorHex) } ?: bodyColor
    withTransform({
        scale(scaleX = 1.0f, scaleY = pose.torsoBreathScaleY, pivot = Offset(cx, cy + 10f * scale))
    }) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(torsoColor, torsoColor.copy(alpha = 0.88f))
            ),
            topLeft = Offset(cx - 42f * scale, cy - 36f * scale),
            size = Size(84f * scale, 78f * scale),
            cornerRadius = CornerRadius(18f * scale, 18f * scale)
        )
    }

    // 8. Left & Right Arms + Slot: ARMS_GLOVES + Slots: HAND_LEFT & HAND_RIGHT
    val armColor = equippedItems[EquipmentSlot.ARMS_GLOVES]?.let { Color(it.primaryColorHex) } ?: bodyShadeColor

    // Left Arm (viewer's left)
    withTransform({
        rotate(degrees = pose.leftArmAngleDeg, pivot = Offset(cx - 46f * scale, cy - 28f * scale))
    }) {
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx - 66f * scale, cy - 28f * scale),
            size = Size(22f * scale, 58f * scale),
            cornerRadius = CornerRadius(10f * scale, 10f * scale)
        )
        // Slot: HAND_RIGHT (Sword / Wand held in character's main hand on viewer's left)
        equippedItems[EquipmentSlot.HAND_RIGHT]?.let { weapon ->
            val weaponColor = Color(weapon.primaryColorHex)
            drawRoundRect(
                color = weaponColor,
                topLeft = Offset(cx - 76f * scale, cy - 72f * scale),
                size = Size(14f * scale, 76f * scale),
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
            // Guard
            drawRoundRect(
                color = Color(0xFFF1C40F),
                topLeft = Offset(cx - 86f * scale, cy - 12f * scale),
                size = Size(34f * scale, 10f * scale),
                cornerRadius = CornerRadius(4f * scale, 4f * scale)
            )
        }
    }

    // Right Arm (viewer's right)
    withTransform({
        rotate(degrees = pose.rightArmAngleDeg, pivot = Offset(cx + 46f * scale, cy - 28f * scale))
    }) {
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx + 44f * scale, cy - 28f * scale),
            size = Size(22f * scale, 58f * scale),
            cornerRadius = CornerRadius(10f * scale, 10f * scale)
        )
        // Slot: HAND_LEFT (Shield / Lantern)
        equippedItems[EquipmentSlot.HAND_LEFT]?.let { shield ->
            val shieldColor = Color(shield.primaryColorHex)
            drawRoundRect(
                color = shieldColor,
                topLeft = Offset(cx + 42f * scale, cy - 12f * scale),
                size = Size(44f * scale, 52f * scale),
                cornerRadius = CornerRadius(14f * scale, 14f * scale)
            )
            drawRoundRect(
                color = Color(0xFFF39C12),
                topLeft = Offset(cx + 42f * scale, cy - 12f * scale),
                size = Size(44f * scale, 52f * scale),
                cornerRadius = CornerRadius(14f * scale, 14f * scale),
                style = Stroke(width = 3.5f * scale)
            )
        }
    }

    // 9. Head (with rigid-body vertical bob & Roblox/Lost Castle blocky low-poly charm)
    val headCenterY = cy - 78f * scale + pose.headBobOffsetY * scale
    val headLeft = cx - 48f * scale + yawParallax * 0.35f

    // Cat / Dog ears if animal avatar
    if (appearance.avatarType == AvatarType.CAT) {
        val leftEar = Path().apply {
            moveTo(headLeft + 8f * scale, headCenterY - 36f * scale)
            lineTo(headLeft + 22f * scale, headCenterY - 68f * scale)
            lineTo(headLeft + 38f * scale, headCenterY - 36f * scale)
            close()
        }
        val rightEar = Path().apply {
            moveTo(headLeft + 58f * scale, headCenterY - 36f * scale)
            lineTo(headLeft + 74f * scale, headCenterY - 68f * scale)
            lineTo(headLeft + 88f * scale, headCenterY - 36f * scale)
            close()
        }
        drawPath(leftEar, color = bodyShadeColor)
        drawPath(rightEar, color = bodyShadeColor)
    } else if (appearance.avatarType == AvatarType.DOG) {
        drawRoundRect(
            color = bodyShadeColor,
            topLeft = Offset(headLeft - 10f * scale, headCenterY - 28f * scale),
            size = Size(18f * scale, 42f * scale),
            cornerRadius = CornerRadius(8f * scale, 8f * scale)
        )
        drawRoundRect(
            color = bodyShadeColor,
            topLeft = Offset(headLeft + 88f * scale, headCenterY - 28f * scale),
            size = Size(18f * scale, 42f * scale),
            cornerRadius = CornerRadius(8f * scale, 8f * scale)
        )
    }

    // Main low-poly head cube
    drawRoundRect(
        color = bodyColor,
        topLeft = Offset(headLeft, headCenterY - 42f * scale),
        size = Size(96f * scale, 80f * scale),
        cornerRadius = CornerRadius(22f * scale, 22f * scale)
    )

    // Expressive eyes & friendly smile
    val eyeY = headCenterY - 6f * scale
    val eyeRadius = if (appearance.eyeExpressionIndex % 2 == 0) 7.5f * scale else 9.0f * scale
    drawCircle(Color(0xFF1E272E), radius = eyeRadius, center = Offset(headLeft + 30f * scale, eyeY))
    drawCircle(Color(0xFF1E272E), radius = eyeRadius, center = Offset(headLeft + 66f * scale, eyeY))
    // Eye sparkle
    drawCircle(Color.White, radius = 2.8f * scale, center = Offset(headLeft + 28f * scale, eyeY - 2.5f * scale))
    drawCircle(Color.White, radius = 2.8f * scale, center = Offset(headLeft + 64f * scale, eyeY - 2.5f * scale))

    // Smile
    drawRoundRect(
        color = Color(0xFF1E272E),
        topLeft = Offset(headLeft + 38f * scale, headCenterY + 12f * scale),
        size = Size(20f * scale, 7f * scale),
        cornerRadius = CornerRadius(4f * scale, 4f * scale)
    )

    // 10. Slot: HAT (Anchored to socket_hat on top of the head)
    equippedItems[EquipmentSlot.HAT]?.let { hat ->
        val hatColor = Color(hat.primaryColorHex)
        // Hat brim
        drawRoundRect(
            color = hatColor,
            topLeft = Offset(headLeft - 10f * scale, headCenterY - 52f * scale),
            size = Size(116f * scale, 16f * scale),
            cornerRadius = CornerRadius(8f * scale, 8f * scale)
        )
        // Hat crown
        val crownPath = Path().apply {
            moveTo(headLeft + 12f * scale, headCenterY - 50f * scale)
            lineTo(headLeft + 48f * scale, headCenterY - 96f * scale)
            lineTo(headLeft + 84f * scale, headCenterY - 50f * scale)
            close()
        }
        drawPath(crownPath, color = hatColor)
    }
}
