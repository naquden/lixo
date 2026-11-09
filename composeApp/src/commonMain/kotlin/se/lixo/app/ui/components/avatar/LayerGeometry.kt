package se.lixo.app.ui.components.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.BACK_BUCKET_PARALLAX
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentAvatar
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ContentLayer
import se.lixo.app.domain.content.ContentPaths
import se.lixo.app.domain.content.CONTENT_EYE_ARGB
import se.lixo.app.domain.content.CONTENT_OUTLINE_ARGB
import se.lixo.app.domain.content.DEFAULT_PARALLAX
import se.lixo.app.domain.content.Mirror
import se.lixo.app.domain.content.PaletteRole
import se.lixo.app.domain.content.STANDARD_BONE_BOX
import se.lixo.app.domain.content.ZBucket
import se.lixo.app.domain.content.mirrorOf
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity

/**
 * Pure (no `DrawScope`, no `Path`) geometry for the generic layer renderer (020): bone placement,
 * pose, the per-layer placement [Matrix] and layer collection/sorting. Everything here is plain
 * Kotlin so it can be exercised from `commonTest` on the host JVM, where
 * `androidx.compose.ui.graphics.Path` is backed by `AndroidPath` and throws against the
 * android.jar stubs. Never call [ActiveContent.parsedPath] from here.
 */

/** A rest pose: no head bob, no breath, no limb/tail angles — used to frame item previews. */
val REST_POSE = ProceduralIdlePose(
    headBobOffsetY = 0f,
    torsoBreathScaleY = 1f,
    leftArmAngleDeg = 0f,
    rightArmAngleDeg = 0f,
    tailWagAngleDeg = 0f,
    legendaryAuraPhase = 0f
)

/** Where the avatar's feet are on screen, and how many px map to one avatar unit (au). */
data class RigFrame(val feet: Offset, val pxPerAu: Float)

/** Fits the whole avatar into [canvasSize] the same way the old `drawLowPolyAvatarScene` did. */
fun avatarFrame(canvasSize: Size): RigFrame {
    val s = (min(canvasSize.width, canvasSize.height) / 340f).coerceAtLeast(0.45f)
    val feet = Offset(canvasSize.width / 2f, canvasSize.height * 0.56f + 96f * s)
    return RigFrame(feet, s)
}

/** A bone's origin and rotation/scale pivot, in au, relative to the feet (y down). */
data class BoneLayout(val origin: Offset, val pivot: Offset)

/** [bone]'s effective (possibly avatar-overridden) size, in au. Null [avatar] means the standard box. */
fun effectiveBoneSize(avatar: ContentAvatar?, bone: Bone): Size {
    val standard = STANDARD_BONE_BOX[bone] ?: Size.Zero
    val override = avatar?.bones?.get(bone)?.size
    return if (override != null && override.size == 2) Size(override[0], override[1]) else standard
}

private fun boneOffset(avatar: ContentAvatar?, bone: Bone): Offset {
    val offset = avatar?.bones?.get(bone)?.offset
    return if (offset != null && offset.size == 2) Offset(offset[0], offset[1]) else Offset.Zero
}

/** The y of the torso's bottom edge (`legsTop`), in au relative to the feet. Shared by [boneLayoutAu]
 * and the breath pivot computed in [boneTransforms]: a null [avatar] means the standard box. */
private fun legsTopAu(avatar: ContentAvatar?): Float {
    val legL = effectiveBoneSize(avatar, Bone.LEG_L)
    val legR = effectiveBoneSize(avatar, Bone.LEG_R)
    return -max(legL.height, legR.height)
}

/**
 * Every bone's [BoneLayout], in au relative to the feet (y down), per `doc/plans/020-layer-renderer.md`
 * approach B. A null [avatar] means the standard box and no offsets; [STANDARD_BONE_ORIGIN] is this
 * function's origins for that case.
 */
fun boneLayoutAu(avatar: ContentAvatar?): Map<Bone, BoneLayout> {
    fun eff(b: Bone) = effectiveBoneSize(avatar, b)
    fun offset(b: Bone) = boneOffset(avatar, b)
    fun withOffset(origin: Offset, pivot: Offset, bone: Bone): BoneLayout {
        val off = offset(bone)
        return BoneLayout(origin + off, pivot + off)
    }

    val legL = eff(Bone.LEG_L)
    val legR = eff(Bone.LEG_R)
    val legsTop = legsTopAu(avatar)

    val legRRaw = Offset(-legR.width, -legR.height)
    val legLRaw = Offset(0f, -legL.height)

    val torsoSize = eff(Bone.TORSO)
    val torsoOriginRaw = Offset(-torsoSize.width / 2f, legsTop - torsoSize.height)
    val torsoTop = torsoOriginRaw.y
    val torsoLeft = -torsoSize.width / 2f
    val torsoRight = torsoSize.width / 2f

    val headSize = eff(Bone.HEAD)
    val headOriginRaw = Offset(-headSize.width / 2f, torsoTop - 1.5f - headSize.height)

    val armRSize = eff(Bone.ARM_R)
    val armROriginRaw = Offset(torsoLeft - armRSize.width, torsoTop)
    val armRPivotRaw = Offset(torsoLeft - armRSize.width / 2f, torsoTop)

    val armLSize = eff(Bone.ARM_L)
    val armLOriginRaw = Offset(torsoRight, torsoTop)
    val armLPivotRaw = Offset(torsoRight + armLSize.width / 2f, torsoTop)

    val tailOriginRaw = Offset(torsoRight - 6f, legsTop - 14f)
    val tailPivotRaw = Offset(torsoRight - 4f, legsTop - 10f)

    val rootSize = eff(Bone.ROOT)
    val rootOriginRaw = Offset(-rootSize.width / 2f, -rootSize.height)

    val legRLayout = withOffset(legRRaw, legRRaw, Bone.LEG_R)
    val legLLayout = withOffset(legLRaw, legLRaw, Bone.LEG_L)
    val torso = withOffset(torsoOriginRaw, torsoOriginRaw, Bone.TORSO)
    val head = withOffset(headOriginRaw, headOriginRaw, Bone.HEAD)
    val armR = withOffset(armROriginRaw, armRPivotRaw, Bone.ARM_R)
    val armL = withOffset(armLOriginRaw, armLPivotRaw, Bone.ARM_L)
    val tail = withOffset(tailOriginRaw, tailPivotRaw, Bone.TAIL)
    val root = withOffset(rootOriginRaw, rootOriginRaw, Bone.ROOT)

    val handRSize = eff(Bone.HAND_R)
    val handROriginFormula = Offset(
        armR.origin.x + (armRSize.width - handRSize.width) / 2f,
        armR.origin.y + armRSize.height - handRSize.height
    )
    val handROffset = offset(Bone.HAND_R)
    val handR = BoneLayout(handROriginFormula + handROffset, armR.pivot + handROffset)

    val handLSize = eff(Bone.HAND_L)
    val handLOriginFormula = Offset(
        armL.origin.x + (armLSize.width - handLSize.width) / 2f,
        armL.origin.y + armLSize.height - handLSize.height
    )
    val handLOffset = offset(Bone.HAND_L)
    val handL = BoneLayout(handLOriginFormula + handLOffset, armL.pivot + handLOffset)

    return mapOf(
        Bone.LEG_R to legRLayout,
        Bone.LEG_L to legLLayout,
        Bone.TORSO to torso,
        Bone.HEAD to head,
        Bone.ARM_R to armR,
        Bone.ARM_L to armL,
        Bone.HAND_R to handR,
        Bone.HAND_L to handL,
        Bone.TAIL to tail,
        Bone.ROOT to root
    )
}

/** [boneLayoutAu] for the standard (unoverridden) avatar, i.e. `boneLayoutAu(null).mapValues { it.value.origin }`. */
val STANDARD_BONE_ORIGIN: Map<Bone, Offset> = boneLayoutAu(null).mapValues { it.value.origin }

/** Fraction, of the STANDARD bone box, of the pivot an item's own `scale` is applied around. */
val ITEM_SCALE_PIVOT: Map<Bone, Offset> = mapOf(
    Bone.HEAD to Offset(0.5f, 0f),
    Bone.TORSO to Offset(0.5f, 0f),
    Bone.ARM_L to Offset(0.5f, 0f),
    Bone.ARM_R to Offset(0.5f, 0f),
    Bone.HAND_L to Offset(0.5f, 0.5f),
    Bone.HAND_R to Offset(0.5f, 0.5f),
    Bone.LEG_L to Offset(0.5f, 1f),
    Bone.LEG_R to Offset(0.5f, 1f),
    Bone.ROOT to Offset(0.5f, 1f),
    Bone.TAIL to Offset(0f, 0f)
)

/**
 * A bone's fully-posed placement for one frame, in px. [origin], [pivot] and [breathPivot] are in
 * px; [breathPivot] is the torso bottom centre and is the same value for every bone.
 */
data class BoneTransform(
    val origin: Offset,
    val pxPerAu: Float,
    val effectiveSize: Size,
    val rotationDeg: Float,
    val pivot: Offset,
    val breathScaleY: Float,
    val breathPivot: Offset,
    val parallaxPx: Float,
    val yawPx: Float
)

/** [boneTransforms] fitting [avatar] into [canvasSize] the same way [avatarFrame] does. */
fun boneTransforms(
    avatar: ContentAvatar,
    pose: ProceduralIdlePose,
    yaw: Float,
    canvasSize: Size
): Map<Bone, BoneTransform> = boneTransforms(avatar, pose, yaw, avatarFrame(canvasSize))

/** [yaw] is in DEGREES, matching the turntable drag gesture state. */
fun boneTransforms(
    avatar: ContentAvatar,
    pose: ProceduralIdlePose,
    yaw: Float,
    frame: RigFrame
): Map<Bone, BoneTransform> {
    val layout = boneLayoutAu(avatar)
    val legsTop = legsTopAu(avatar)
    val yawPx = sin(yaw * PI.toFloat() / 180f) * 14f * frame.pxPerAu
    val headBobAu = pose.headBobOffsetY * 0.5f * avatar.pose.headBob
    val breathScale = 1f + (pose.torsoBreathScaleY - 1f) * avatar.pose.breath
    val breathPivotPx = toPx(frame, Offset(0f, legsTop))

    val rotations = mapOf(
        Bone.ARM_R to pose.leftArmAngleDeg * avatar.pose.armSwing,
        Bone.HAND_R to pose.leftArmAngleDeg * avatar.pose.armSwing,
        Bone.ARM_L to pose.rightArmAngleDeg * avatar.pose.armSwing,
        Bone.HAND_L to pose.rightArmAngleDeg * avatar.pose.armSwing,
        Bone.TAIL to pose.tailWagAngleDeg * avatar.pose.tailWag
    )

    return Bone.entries.associateWith { bone ->
        val bl = layout[bone] ?: BoneLayout(Offset.Zero, Offset.Zero)
        val originAu = if (bone == Bone.HEAD) bl.origin + Offset(0f, headBobAu) else bl.origin
        val pivotAu = if (bone == Bone.HEAD) bl.pivot + Offset(0f, headBobAu) else bl.pivot
        BoneTransform(
            origin = toPx(frame, originAu),
            pxPerAu = frame.pxPerAu,
            effectiveSize = effectiveBoneSize(avatar, bone),
            rotationDeg = rotations[bone] ?: 0f,
            pivot = toPx(frame, pivotAu),
            breathScaleY = if (bone == Bone.TORSO) breathScale else 1f,
            breathPivot = breathPivotPx,
            parallaxPx = yawPx * (DEFAULT_PARALLAX[bone] ?: 0f),
            yawPx = yawPx
        )
    }
}

private fun toPx(frame: RigFrame, au: Offset): Offset =
    frame.feet + Offset(au.x * frame.pxPerAu, au.y * frame.pxPerAu)

/** One layer placed on a bone, with its draw colours already resolved. */
data class PlacedLayer(
    val layer: ContentLayer,
    val bone: Bone,
    val mirrored: Boolean,
    val item: ContentItem?,
    val partName: String?,
    val fillColor: Color = Color.Gray,
    val strokeColor: Color? = null
)

/** This layer's horizontal parallax, in px, per `doc/plans/020-layer-renderer.md` approach D step 1. */
fun layerParallaxPx(p: PlacedLayer, t: BoneTransform): Float {
    val layer = p.layer
    return when {
        layer.parallax != null -> layer.parallax * t.yawPx
        layer.z == ZBucket.BACK -> BACK_BUCKET_PARALLAX * t.yawPx
        else -> t.parallaxPx
    }
}

/** The outer→inner steps 1-4 shared by [layerMatrix] and [faceMatrix]: parallax, rotation, breath,
 * then the bone's own origin/scale. [parallaxPx] and [breath] let each caller pick its own source. */
private fun boneMatrix(
    bone: Bone,
    bones: Map<Bone, BoneTransform>,
    parallaxPx: Float,
    breath: Boolean
): Matrix {
    val t = bones[bone] ?: error("no BoneTransform for $bone")
    val m = Matrix()
    m.translate(parallaxPx, 0f)
    if (t.rotationDeg != 0f) {
        m.translate(t.pivot.x, t.pivot.y)
        m.rotateZ(t.rotationDeg)
        m.translate(-t.pivot.x, -t.pivot.y)
    }
    if (bone == Bone.TORSO || breath) {
        val torso = bones[Bone.TORSO] ?: error("no BoneTransform for TORSO")
        m.translate(torso.breathPivot.x, torso.breathPivot.y)
        m.scale(1f, torso.breathScaleY)
        m.translate(-torso.breathPivot.x, -torso.breathPivot.y)
    }
    m.translate(t.origin.x, t.origin.y)
    m.scale(t.pxPerAu, t.pxPerAu)
    return m
}

/**
 * The full placement [Matrix] for layer [p], per `doc/plans/020-layer-renderer.md` approach D.
 * [sideOffsetAu] is used for a BLOCK-shaded layer's darker side face.
 */
fun layerMatrix(p: PlacedLayer, bones: Map<Bone, BoneTransform>, sideOffsetAu: Float = 0f): Matrix {
    val t = bones[p.bone] ?: error("no BoneTransform for ${p.bone}")
    val parallax = layerParallaxPx(p, t)
    val m = boneMatrix(p.bone, bones, parallax, p.layer.breath)

    if (p.item != null) {
        val eff = t.effectiveSize
        val std = STANDARD_BONE_BOX[p.bone] ?: Size.Zero
        if (std.width != 0f && std.height != 0f) {
            m.scale(eff.width / std.width, eff.height / std.height)
        }
        if (p.item.scale != 1f) {
            val frac = ITEM_SCALE_PIVOT[p.bone] ?: Offset.Zero
            val pivot = Offset(frac.x * std.width, frac.y * std.height)
            val k = p.item.scale
            m.translate(pivot.x, pivot.y)
            m.scale(k, k)
            m.translate(-pivot.x, -pivot.y)
        }
    }

    if (sideOffsetAu != 0f) {
        m.translate(sideOffsetAu, 0f)
    }

    if (p.mirrored) {
        val w = if (p.item != null) (STANDARD_BONE_BOX[p.bone]?.width ?: 0f) else t.effectiveSize.width
        m.translate(w, 0f)
        m.scale(-1f, 1f)
    }
    return m
}

/** The HEAD bone matrix, as for an unmirrored item layer with item scale 1: used to draw the face. */
fun faceMatrix(bones: Map<Bone, BoneTransform>): Matrix {
    val t = bones[Bone.HEAD] ?: error("no BoneTransform for HEAD")
    return boneMatrix(Bone.HEAD, bones, t.parallaxPx, breath = false)
}

/** [c] lerped towards white ([shade] > 0) or black ([shade] < 0). */
fun applyShade(c: Color, shade: Float): Color = when {
    shade > 0f -> lerp(c, Color.White, shade)
    shade < 0f -> lerp(c, Color.Black, -shade)
    else -> c
}

/** Resolves one [PaletteRole] to a concrete [Color], per `doc/plans/020-layer-renderer.md` approach E. */
fun resolveRoleColor(role: PaletteRole, item: ContentItem?, skin: Color, tint: Color): Color = when (role) {
    PaletteRole.PRIMARY -> item?.palette?.primary?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.SECONDARY -> item?.palette?.secondary?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.ACCENT -> item?.palette?.accent?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.SKIN -> skin
    PaletteRole.TINT -> tint
    PaletteRole.EYE -> Color(CONTENT_EYE_ARGB)
    PaletteRole.OUTLINE -> Color(CONTENT_OUTLINE_ARGB)
}

/** Stable sort by `(z.ordinal, order)`. */
fun sortLayers(layers: List<PlacedLayer>): List<PlacedLayer> =
    layers.sortedWith(compareBy({ it.layer.z.ordinal }, { it.layer.order }))

private fun expandMirror(
    layer: ContentLayer,
    item: ContentItem?,
    partName: String?,
    fill: Color,
    stroke: Color?
): List<PlacedLayer> {
    val base = PlacedLayer(layer, layer.bone, mirrored = false, item = item, partName = partName, fillColor = fill, strokeColor = stroke)
    val mirrorBone = if (layer.mirror == Mirror.X) layer.bone.mirrorOf() else null
    return if (mirrorBone != null) listOf(base, base.copy(bone = mirrorBone, mirrored = true)) else listOf(base)
}

/** The avatar + its equipped items + the sorted layers to draw, plus whether any item is LEGENDARY. */
data class AvatarDrawList(
    val avatar: ContentAvatar,
    val items: List<ContentItem>,
    val layers: List<PlacedLayer>,
    val hasLegendary: Boolean
)

/** Collects and sorts [appearance]'s layers, per `doc/plans/020-layer-renderer.md` approach E. */
fun buildAvatarDrawList(content: ActiveContent, appearance: AvatarAppearance): AvatarDrawList {
    val avatar = content.avatarsById[appearance.avatarId] ?: content.defaultAvatar
    val items = EquipmentSlot.entries.mapNotNull { slot -> appearance.equipped[slot]?.let { content.itemsById[it] } }
    val hidden = items.flatMap { it.hidesParts }.toSet()

    val skin = parseContentHex(avatar.skinHex)?.let(::Color) ?: Color.Gray
    val tint = (appearance.tintHex ?: parseContentHex(avatar.defaultTintHex))?.let(::Color) ?: Color.Gray

    fun fillOf(layer: ContentLayer, item: ContentItem?): Color =
        applyShade(resolveRoleColor(layer.fill.role, item, skin, tint), layer.fill.shade)
    fun strokeOf(layer: ContentLayer, item: ContentItem?): Color? =
        layer.stroke?.let { resolveRoleColor(it.role, item, skin, tint) }

    val partLayers = avatar.parts.filter { it.part !in hidden }.flatMap { part ->
        part.layers.flatMap { layer -> expandMirror(layer, null, part.part, fillOf(layer, null), strokeOf(layer, null)) }
    }
    val itemLayers = items.flatMap { item ->
        item.layers.flatMap { layer -> expandMirror(layer, item, null, fillOf(layer, item), strokeOf(layer, item)) }
    }

    val layers = sortLayers(partLayers + itemLayers)
    val hasLegendary = items.any { it.rarity == Rarity.LEGENDARY }
    return AvatarDrawList(avatar, items, layers, hasLegendary)
}

/** A single item's layers (for [ItemPreview][se.lixo.app.ui.components.avatar.drawItemPreview]),
 * plus the default avatar's ghost-body layers underneath it when `item.preview?.ghostBody == true`. */
data class ItemDrawList(
    val item: ContentItem,
    val avatar: ContentAvatar,
    val layers: List<PlacedLayer>,
    val ghostLayers: List<PlacedLayer>
)

/** Builds [item]'s draw list against [content]'s default avatar's skin/tint, per approach I. */
fun buildItemDrawList(content: ActiveContent, item: ContentItem): ItemDrawList {
    val avatar = content.defaultAvatar
    val skin = parseContentHex(avatar.skinHex)?.let(::Color) ?: Color.Gray
    val tint = parseContentHex(avatar.defaultTintHex)?.let(::Color) ?: Color.Gray

    val itemLayers = sortLayers(
        item.layers.flatMap { layer ->
            val fill = applyShade(resolveRoleColor(layer.fill.role, item, skin, tint), layer.fill.shade)
            val stroke = layer.stroke?.let { resolveRoleColor(it.role, item, skin, tint) }
            expandMirror(layer, item, null, fill, stroke)
        }
    )

    val ghostLayers = if (item.preview?.ghostBody == true) {
        val placedBones = itemLayers.map { it.bone }.toSet()
        val hiddenParts = item.hidesParts.toSet()
        sortLayers(
            avatar.parts.filter { it.part !in hiddenParts }.flatMap { part ->
                part.layers.flatMap { layer ->
                    val fill = applyShade(resolveRoleColor(layer.fill.role, null, skin, tint), layer.fill.shade)
                    val stroke = layer.stroke?.let { resolveRoleColor(it.role, null, skin, tint) }
                    expandMirror(layer, null, part.part, fill, stroke)
                }
            }.filter { it.bone in placedBones }
        )
    } else {
        emptyList()
    }

    return ItemDrawList(item, avatar, itemLayers, ghostLayers)
}

/** [p]'s placed bounding box, in px, under [bones]. */
fun placedBoundsPx(content: ActiveContent, p: PlacedLayer, bones: Map<Bone, BoneTransform>): Rect =
    layerMatrix(p, bones).map(content.layerBounds(p.layer))

/** The union of every placed layer belonging to item [itemId], in px, or null if it has none in [layers]. */
fun itemUnionPx(content: ActiveContent, layers: List<PlacedLayer>, itemId: String, bones: Map<Bone, BoneTransform>): Rect? {
    var union: Rect? = null
    for (p in layers) {
        if (p.item?.id != itemId) continue
        val b = placedBoundsPx(content, p, bones)
        union = if (union == null) b else ContentPaths.union(union, b)
    }
    return union
}

/** [item]'s preview yaw, converted from `ItemPreviewHints.yaw` (radians) to degrees. */
fun previewYawDeg(item: ContentItem): Float = (item.preview?.yaw ?: 0.35f) * 180f / PI.toFloat()

/**
 * A [RigFrame] that fits [drawList]'s item layers into [canvasSize] with a [margin] fraction of
 * blank space on every side, per approach I. Null if [drawList] has no layers to frame.
 */
fun previewFrame(
    content: ActiveContent,
    drawList: ItemDrawList,
    yawDeg: Float,
    canvasSize: Size,
    margin: Float = 0.1f
): RigFrame? {
    if (drawList.layers.isEmpty()) return null
    val unitBones = boneTransforms(drawList.avatar, REST_POSE, yawDeg, RigFrame(Offset.Zero, 1f))

    var union: Rect? = null
    for (p in drawList.layers) {
        val b = placedBoundsPx(content, p, unitBones)
        union = if (union == null) b else ContentPaths.union(union, b)
    }
    val u = union ?: return null

    val usable = (1f - 2f * margin).coerceAtLeast(0f)
    val pxPerAu = min(
        (canvasSize.width * usable) / u.width.coerceAtLeast(0.001f),
        (canvasSize.height * usable) / u.height.coerceAtLeast(0.001f)
    )
    val canvasCentre = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val feet = canvasCentre - Offset(u.center.x * pxPerAu, u.center.y * pxPerAu)
    return RigFrame(feet, pxPerAu)
}

/**
 * [content.string][ActiveContent.string] of [appearance]'s avatar name, plus every equipped
 * item's name (in [EquipmentSlot] order) when anything is equipped, e.g. "Katten Lixo: Testhatt".
 */
fun avatarContentDescription(content: ActiveContent, appearance: AvatarAppearance): String {
    val avatar = content.avatarsById[appearance.avatarId] ?: content.defaultAvatar
    val name = content.string(avatar.nameKey)
    val items = EquipmentSlot.entries.mapNotNull { slot -> appearance.equipped[slot]?.let { content.itemsById[it] } }
    return if (items.isEmpty()) name else "$name: " + items.joinToString(", ") { content.string(it.nameKey) }
}
