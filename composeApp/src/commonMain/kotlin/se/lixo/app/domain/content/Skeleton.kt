package se.lixo.app.domain.content

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.Serializable

/**
 * The content pack schema version this app build understands (`doc/content/content-pack.md`).
 * [se.lixo.app.domain.content.ContentPackValidator] rejects any pack whose `schemaVersion`
 * differs.
 */
const val SUPPORTED_SCHEMA_VERSION = 1

/**
 * The fixed skeleton every avatar shares. Content packs attach layers to a [Bone]; the renderer
 * (020) places each bone's box per-avatar, optionally resized/offset by `ContentAvatar.bones`.
 */
@Serializable
enum class Bone {
    ROOT,
    TORSO,
    HEAD,
    ARM_L,
    ARM_R,
    HAND_L,
    HAND_R,
    LEG_L,
    LEG_R,
    TAIL
}

/**
 * The opposite bone for a mirrored (`Mirror.X`) layer, or null if this bone has no mirror
 * counterpart (e.g. [Bone.ROOT], [Bone.TORSO], [Bone.HEAD], [Bone.TAIL]).
 */
fun Bone.mirrorOf(): Bone? = when (this) {
    Bone.ARM_L -> Bone.ARM_R
    Bone.ARM_R -> Bone.ARM_L
    Bone.HAND_L -> Bone.HAND_R
    Bone.HAND_R -> Bone.HAND_L
    Bone.LEG_L -> Bone.LEG_R
    Bone.LEG_R -> Bone.LEG_L
    Bone.ROOT, Bone.TORSO, Bone.HEAD, Bone.TAIL -> null
}

/**
 * Draw-order buckets for layers. The enum's ordinal IS the draw order (lowest first, drawn
 * behind everything with a higher ordinal): BACK, TAIL, LEGS, LEGWEAR, FOOTWEAR, TORSO,
 * TORSO_OVER, ARM_BACK, ARM, HAND, HEAD, FACE, HEAD_OVER, HAT, FRONT.
 */
@Serializable
enum class ZBucket {
    BACK,
    TAIL,
    LEGS,
    LEGWEAR,
    FOOTWEAR,
    TORSO,
    TORSO_OVER,
    ARM_BACK,
    ARM,
    HAND,
    HEAD,
    FACE,
    HEAD_OVER,
    HAT,
    FRONT
}

/** The standard (unscaled) size of each bone's box, in avatar units (au). */
val STANDARD_BONE_BOX: Map<Bone, Size> = mapOf(
    Bone.ROOT to Size(160f, 240f),
    Bone.TORSO to Size(80f, 80f),
    Bone.HEAD to Size(56f, 56f),
    Bone.ARM_L to Size(38f, 80f),
    Bone.ARM_R to Size(38f, 80f),
    Bone.HAND_L to Size(38f, 38f),
    Bone.HAND_R to Size(38f, 38f),
    Bone.LEG_L to Size(40f, 80f),
    Bone.LEG_R to Size(40f, 80f),
    Bone.TAIL to Size(40f, 60f)
)

/** The default parallax factor per bone, used by the renderer unless a layer overrides it. */
val DEFAULT_PARALLAX: Map<Bone, Float> = mapOf(
    Bone.ROOT to 0.0f,
    Bone.TORSO to 0.5f,
    Bone.HEAD to 1.0f,
    Bone.ARM_L to 0.5f,
    Bone.ARM_R to 0.5f,
    Bone.HAND_L to 0.5f,
    Bone.HAND_R to 0.5f,
    Bone.LEG_L to 0.3f,
    Bone.LEG_R to 0.3f,
    Bone.TAIL to -0.4f
)

/**
 * How far, in standard-torso au, a [ZBucket.BACK] layer without its own `parallax` slides against
 * its bone at ±90° yaw, opposite to the body's turn (see [se.lixo.app.ui.components.avatar.boneTransforms]).
 * It is scaled by the bone's `itemScale.x`, so a wide avatar (e.g. the knight) peeks as much as a
 * narrow one. 48 gives quivers about 10–14au and capes about 34–52au of peek past the arms at
 * ±90° across all six bundled avatars; keep it within 40..60.
 */
const val BACK_PEEK_AU = 48f

/** How far, as a multiple of a bone box's own size, a layer's path may extend on each side. */
const val BONE_BOX_OVERHANG = 1.5f

/**
 * [size]'s bone box expanded by [BONE_BOX_OVERHANG] on every side, so hats and capes can stick
 * out of their bone's standard box. A layer's parsed path bounds must lie [isInside] this rect.
 */
fun expandedBoneBox(size: Size): Rect {
    val w = size.width
    val h = size.height
    return Rect(
        left = -BONE_BOX_OVERHANG * w,
        top = -BONE_BOX_OVERHANG * h,
        right = (1f + BONE_BOX_OVERHANG) * w,
        bottom = (1f + BONE_BOX_OVERHANG) * h
    )
}

/**
 * Whether this rect lies entirely inside [outer], inclusive on all four edges. Unlike
 * `Rect.contains(Offset)`, which is exclusive on the right/bottom edges, this treats a rect
 * exactly touching [outer]'s edge as inside.
 */
fun Rect.isInside(outer: Rect): Boolean =
    left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom

/** The face "eye" colour used by content layers with `PaletteRole.EYE`. */
const val CONTENT_EYE_ARGB = 0xFF1E272EL

/** The outline colour used by content layers with `PaletteRole.OUTLINE`. */
const val CONTENT_OUTLINE_ARGB = 0xFF2D3436L
