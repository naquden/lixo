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
import kotlin.math.sqrt
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.BACK_BUCKET_PARALLAX
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentAvatar
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ContentLayer
import se.lixo.app.domain.content.ContentPalette
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
import se.lixo.app.domain.model.isHand

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

/** The tallest hat (top hat at legendary scale 1.15, 58 au × 1.15) reaches this far above the head top. */
private const val HAT_REACH_AU = 67f

/** How many au of headTop+hat reach fit above the feet before [avatarFrame] shrinks the avatar down. */
private const val AVATAR_REACH_BUDGET_AU = 290f

/**
 * Fits the whole avatar into [canvasSize] the same way the old `drawLowPolyAvatarScene` did, but
 * shrinks avatars whose head (plus the tallest hat's reach, scaled to their own HEAD bone) would not
 * fit above the feet in a square canvas — e.g. the witch with a tall hat. A null [avatar] (or one
 * with standard bones) keeps the old, unshrunk scale.
 */
fun avatarFrame(canvasSize: Size, avatar: ContentAvatar? = null): RigFrame {
    val base = (min(canvasSize.width, canvasSize.height) / 340f).coerceAtLeast(0.45f)
    val headTopAu = -boneLayoutAu(avatar).getValue(Bone.HEAD).origin.y
    val reach = headTopAu + HAT_REACH_AU * effectiveBoneSize(avatar, Bone.HEAD).height / 56f
    val s = base * min(1f, AVATAR_REACH_BUDGET_AU / reach)
    val feet = Offset(canvasSize.width / 2f, canvasSize.height * 0.56f + 108f * base)
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

private val HELD_ITEM_SLOTS = setOf(EquipmentSlot.HAND_LEFT, EquipmentSlot.HAND_RIGHT)
private val HELD_ITEM_BONES = setOf(Bone.HAND_L, Bone.HAND_R, Bone.ARM_L, Bone.ARM_R)

/** HAND_LEFT/HAND_RIGHT-slot item layers on a hand or arm bone: scaled uniformly, not per axis. */
private fun isHeldItemLayer(p: PlacedLayer): Boolean =
    p.item != null && p.item.slot in HELD_ITEM_SLOTS && p.bone in HELD_ITEM_BONES

/** The hand centre a held-item layer on [bone] is scaled around: (standard-box point, effective-box point), bone-local au. */
private fun heldItemAnchors(bone: Bone, t: BoneTransform, bones: Map<Bone, BoneTransform>): Pair<Offset, Offset> {
    val std = STANDARD_BONE_BOX[bone] ?: Size.Zero
    val eff = t.effectiveSize
    val hand = when (bone) { Bone.ARM_R -> Bone.HAND_R; Bone.ARM_L -> Bone.HAND_L; else -> null }
        ?: return Offset(std.width / 2f, std.height / 2f) to Offset(eff.width / 2f, eff.height / 2f)
    val stdHand = STANDARD_BONE_BOX[hand] ?: Size.Zero
    val effHand = bones[hand]?.effectiveSize ?: stdHand
    return Offset(std.width / 2f, std.height - stdHand.height / 2f) to
        Offset(eff.width / 2f, eff.height - effHand.height / 2f)
}

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
    val yawPx: Float,
    val itemScale: Offset
)

/** [boneTransforms] fitting [avatar] into [canvasSize] the same way [avatarFrame] does. */
fun boneTransforms(
    avatar: ContentAvatar,
    pose: ProceduralIdlePose,
    yaw: Float,
    canvasSize: Size
): Map<Bone, BoneTransform> = boneTransforms(avatar, pose, yaw, avatarFrame(canvasSize, avatar))

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
        val eff = effectiveBoneSize(avatar, bone)
        val std = STANDARD_BONE_BOX[bone] ?: Size.Zero
        val itemScale = if (std.width != 0f && std.height != 0f) {
            Offset(eff.width / std.width, eff.height / std.height)
        } else {
            Offset(1f, 1f)
        }
        BoneTransform(
            origin = toPx(frame, originAu),
            pxPerAu = frame.pxPerAu,
            effectiveSize = eff,
            rotationDeg = rotations[bone] ?: 0f,
            pivot = toPx(frame, pivotAu),
            breathScaleY = if (bone == Bone.TORSO) breathScale else 1f,
            breathPivot = breathPivotPx,
            parallaxPx = yawPx * (DEFAULT_PARALLAX[bone] ?: 0f),
            yawPx = yawPx,
            itemScale = itemScale
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
    val strokeColor: Color? = null,
    /** Part layers only: the bone box this layer was authored in, when it is drawn on a
     * different-size bone (`ItemPreview`'s ghost body). [layerMatrix] stretches the layer from
     * this box to the bone's effective box and mirrors it within this box's width. Null means it
     * was authored in the drawn bone's own effective box. */
    val authoredBoneSize: Size? = null
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
 * [sideOffsetAu] is used for a BLOCK-shaded layer's darker side face. Item layers are stretched per
 * axis by [BoneTransform.itemScale], except held items (HAND_LEFT/HAND_RIGHT slot layers on
 * HAND_L/HAND_R/ARM_L/ARM_R). Those are scaled uniformly by the geometric mean of itemScale around
 * the hand centre (hand-box centre; for arm bones, the centre of the hand inside the arm box), so
 * round shields stay round on slim avatars. [PlacedLayer.authoredBoneSize] layers (`ItemPreview`'s
 * ghost body) are stretched from that authored box to the bone's effective box instead.
 */
fun layerMatrix(p: PlacedLayer, bones: Map<Bone, BoneTransform>, sideOffsetAu: Float = 0f): Matrix {
    val t = bones[p.bone] ?: error("no BoneTransform for ${p.bone}")
    val parallax = layerParallaxPx(p, t)
    val m = boneMatrix(p.bone, bones, parallax, p.layer.breath)

    if (p.item != null) {
        if (isHeldItemLayer(p)) {
            val (stdAnchor, effAnchor) = heldItemAnchors(p.bone, t, bones)
            val k = sqrt(t.itemScale.x * t.itemScale.y)
            m.translate(effAnchor.x, effAnchor.y)
            m.scale(k, k)
            m.translate(-stdAnchor.x, -stdAnchor.y)
        } else {
            m.scale(t.itemScale.x, t.itemScale.y)
        }
        val std = STANDARD_BONE_BOX[p.bone] ?: Size.Zero
        if (p.item.scale != 1f) {
            val frac = ITEM_SCALE_PIVOT[p.bone] ?: Offset.Zero
            val pivot = Offset(frac.x * std.width, frac.y * std.height)
            val k = p.item.scale
            m.translate(pivot.x, pivot.y)
            m.scale(k, k)
            m.translate(-pivot.x, -pivot.y)
        }
    } else if (p.authoredBoneSize != null) {
        val a = p.authoredBoneSize
        val eff = t.effectiveSize
        m.scale(if (a.width != 0f) eff.width / a.width else 1f, if (a.height != 0f) eff.height / a.height else 1f)
    }

    if (sideOffsetAu != 0f) {
        m.translate(sideOffsetAu, 0f)
    }

    if (p.mirrored) {
        val w = when {
            p.item != null -> STANDARD_BONE_BOX[p.bone]?.width ?: 0f
            p.authoredBoneSize != null -> p.authoredBoneSize.width
            else -> t.effectiveSize.width
        }
        m.translate(w, 0f)
        m.scale(-1f, 1f)
    }
    return m
}

/** The HEAD bone matrix, as for an unmirrored item layer with item scale 1, further stretched from
 * the 56×56 au face design box to the effective HEAD box: used to draw the face. */
fun faceMatrix(bones: Map<Bone, BoneTransform>): Matrix {
    val t = bones[Bone.HEAD] ?: error("no BoneTransform for HEAD")
    val m = boneMatrix(Bone.HEAD, bones, t.parallaxPx, breath = false)
    m.scale(t.itemScale.x, t.itemScale.y)
    return m
}

/** [c] lerped towards white ([shade] > 0) or black ([shade] < 0). */
fun applyShade(c: Color, shade: Float): Color = when {
    shade > 0f -> lerp(c, Color.White, shade)
    shade < 0f -> lerp(c, Color.Black, -shade)
    else -> c
}

/** Resolves one [PaletteRole] to a concrete [Color], per `doc/plans/020-layer-renderer.md` approach E.
 * PRIMARY/SECONDARY/ACCENT prefer the [item]'s own palette, falling back to [avatarPalette] (the
 * avatar's own palette, used by its part layers) and then to gray. */
fun resolveRoleColor(
    role: PaletteRole,
    item: ContentItem?,
    skin: Color,
    tint: Color,
    avatarPalette: ContentPalette? = null
): Color = when (role) {
    PaletteRole.PRIMARY -> (item?.palette ?: avatarPalette)?.primary?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.SECONDARY -> (item?.palette ?: avatarPalette)?.secondary?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.ACCENT -> (item?.palette ?: avatarPalette)?.accent?.let(::parseContentHex)?.let(::Color) ?: Color.Gray
    PaletteRole.SKIN -> skin
    PaletteRole.TINT -> tint
    PaletteRole.EYE -> Color(CONTENT_EYE_ARGB)
    PaletteRole.OUTLINE -> Color(CONTENT_OUTLINE_ARGB)
}

/** Stable sort by `(z.ordinal, order)`. */
fun sortLayers(layers: List<PlacedLayer>): List<PlacedLayer> =
    layers.sortedWith(compareBy({ it.layer.z.ordinal }, { it.layer.order }))

/** [sideSwap] remaps every placed layer to its mirror bone with `mirrored` inverted: a hand item
 * equipped in the opposite hand's slot is drawn on the mirror bone, mirrored (doc/plans/032). */
private fun expandMirror(
    layer: ContentLayer,
    item: ContentItem?,
    partName: String?,
    fill: Color,
    stroke: Color?,
    sideSwap: Boolean = false
): List<PlacedLayer> {
    val base = PlacedLayer(layer, layer.bone, mirrored = false, item = item, partName = partName, fillColor = fill, strokeColor = stroke)
    val mirrorBone = if (layer.mirror == Mirror.X) layer.bone.mirrorOf() else null
    val placed = if (mirrorBone != null) listOf(base, base.copy(bone = mirrorBone, mirrored = true)) else listOf(base)
    return if (sideSwap) {
        placed.map { it.copy(bone = it.bone.mirrorOf() ?: it.bone, mirrored = !it.mirrored) }
    } else {
        placed
    }
}

/** The avatar + its equipped items + the sorted layers to draw, plus whether any item is LEGENDARY. */
data class AvatarDrawList(
    val avatar: ContentAvatar,
    val items: List<ContentItem>,
    val layers: List<PlacedLayer>,
    val hasLegendary: Boolean
)

/** Collects and sorts [appearance]'s layers, per `doc/plans/020-layer-renderer.md` approach E.
 * A hand item equipped in the hand opposite the one it was authored for (shared hand pool,
 * doc/plans/032) is drawn mirrored onto that side's bone via [expandMirror]'s `sideSwap`. */
fun buildAvatarDrawList(content: ActiveContent, appearance: AvatarAppearance): AvatarDrawList {
    val avatar = content.avatarsById[appearance.avatarId] ?: content.defaultAvatar
    val equippedItems: List<Pair<EquipmentSlot, ContentItem>> =
        EquipmentSlot.entries.mapNotNull { slot -> appearance.equipped[slot]?.let { content.itemsById[it] }?.let { slot to it } }
    val items = equippedItems.map { it.second }
    val hidden = items.flatMap { it.hidesParts }.toSet()

    val skin = parseContentHex(avatar.skinHex)?.let(::Color) ?: Color.Gray
    val tint = (appearance.tintHex ?: parseContentHex(avatar.defaultTintHex))?.let(::Color) ?: Color.Gray

    fun fillOf(layer: ContentLayer, item: ContentItem?): Color =
        applyShade(resolveRoleColor(layer.fill.role, item, skin, tint, avatar.palette), layer.fill.shade)
    fun strokeOf(layer: ContentLayer, item: ContentItem?): Color? =
        layer.stroke?.let { resolveRoleColor(it.role, item, skin, tint, avatar.palette) }

    val partLayers = avatar.parts.filter { it.part !in hidden }.flatMap { part ->
        part.layers.flatMap { layer -> expandMirror(layer, null, part.part, fillOf(layer, null), strokeOf(layer, null)) }
    }
    val itemLayers = equippedItems.flatMap { (slot, item) ->
        val swap = slot.isHand && item.slot.isHand && slot != item.slot
        item.layers.flatMap { layer -> expandMirror(layer, item, null, fillOf(layer, item), strokeOf(layer, item), sideSwap = swap) }
    }

    val layers = sortLayers(partLayers + itemLayers)
    val hasLegendary = items.any { it.rarity == Rarity.LEGENDARY }
    return AvatarDrawList(avatar, items, layers, hasLegendary)
}

/** Base body parts every avatar has; mirrors `avatar_body.BODY_PART_NAMES`
 * (`tools/content_pipeline/pack/templates/avatars/body.py`). `ItemPreview`'s ghost body uses only
 * these, never signature parts (ears, muzzle, stripes, tail, hair...). */
internal val BASE_BODY_PARTS: Set<String> = setOf("torso", "arms", "hands", "legs", "pants", "shoes", "head")

/** A single item's layers (for [ItemPreview][se.lixo.app.ui.components.avatar.drawItemPreview]),
 * drawn against [avatar] — the default avatar with its bone overrides cleared (standard bones;
 * skin, tint and palette kept) — plus [ghostLayers]: the default avatar's base-body parts on the
 * item's bones, rescaled to the standard boxes, underneath it when `item.preview?.ghostBody == true`. */
data class ItemDrawList(
    val item: ContentItem,
    val avatar: ContentAvatar,
    val layers: List<PlacedLayer>,
    val ghostLayers: List<PlacedLayer>
)

/** Builds [item]'s draw list against [content]'s default avatar's skin/tint/palette, per approach I.
 * [ItemDrawList.avatar] has its bone overrides cleared so items render on standard bones, at their
 * authored proportions, whatever avatar a player wears. The ghost body (when
 * `item.preview?.ghostBody == true`) uses only [BASE_BODY_PARTS], rescaled from the default
 * avatar's effective bone sizes to the standard boxes via [PlacedLayer.authoredBoneSize]. */
fun buildItemDrawList(content: ActiveContent, item: ContentItem): ItemDrawList {
    val avatar = content.defaultAvatar
    val skin = parseContentHex(avatar.skinHex)?.let(::Color) ?: Color.Gray
    val tint = parseContentHex(avatar.defaultTintHex)?.let(::Color) ?: Color.Gray

    val itemLayers = sortLayers(
        item.layers.flatMap { layer ->
            val fill = applyShade(resolveRoleColor(layer.fill.role, item, skin, tint, avatar.palette), layer.fill.shade)
            val stroke = layer.stroke?.let { resolveRoleColor(it.role, item, skin, tint, avatar.palette) }
            expandMirror(layer, item, null, fill, stroke)
        }
    )

    val ghostLayers = if (item.preview?.ghostBody == true) {
        val placedBones = itemLayers.map { it.bone }.toSet()
        val hiddenParts = item.hidesParts.toSet()
        sortLayers(
            avatar.parts.filter { it.part in BASE_BODY_PARTS && it.part !in hiddenParts }.flatMap { part ->
                part.layers.flatMap { layer ->
                    val fill = applyShade(resolveRoleColor(layer.fill.role, null, skin, tint, avatar.palette), layer.fill.shade)
                    val stroke = layer.stroke?.let { resolveRoleColor(it.role, null, skin, tint, avatar.palette) }
                    expandMirror(layer, null, part.part, fill, stroke)
                }
            }.filter { it.bone in placedBones }
                .map { it.copy(authoredBoneSize = effectiveBoneSize(avatar, it.bone)) }
        )
    } else {
        emptyList()
    }

    return ItemDrawList(item, avatar.copy(bones = emptyMap()), itemLayers, ghostLayers)
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
