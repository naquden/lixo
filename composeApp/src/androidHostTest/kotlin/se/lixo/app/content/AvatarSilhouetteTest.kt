package se.lixo.app.content

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import se.lixo.app.BundledTestContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentPaths
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.ui.components.avatar.BoneTransform
import se.lixo.app.ui.components.avatar.REST_POSE
import se.lixo.app.ui.components.avatar.RigFrame
import se.lixo.app.ui.components.avatar.boneTransforms
import se.lixo.app.ui.components.avatar.buildAvatarDrawList
import se.lixo.app.ui.components.avatar.itemUnionPx

/**
 * 026's silhouette checks, against the real bundled pack (so this lives in androidHostTest, not
 * commonTest -- see `BundledTestContent`). Never calls `ActiveContent.parsedPath`/`Path()`:
 * `layerBounds`/`placedBoundsPx`/`itemUnionPx` are path-free.
 */
class AvatarSilhouetteTest {

    private val content = BundledTestContent.content

    private val SILHOUETTE_BONES = listOf(Bone.TORSO, Bone.HEAD, Bone.ARM_L, Bone.ARM_R, Bone.LEG_L, Bone.LEG_R)

    private fun silhouetteUnion(bones: Map<Bone, BoneTransform>): Rect {
        var union: Rect? = null
        for (bone in SILHOUETTE_BONES) {
            val t = bones[bone] ?: continue
            val r = Rect(t.origin, t.effectiveSize)
            union = if (union == null) r else ContentPaths.union(union, r)
        }
        return union!!
    }

    @Test
    fun everyAvatarHasDistinctSilhouette() {
        val avatars = content.avatarsOrdered
        val silhouettes = avatars.associateWith { avatar ->
            silhouetteUnion(boneTransforms(avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f)))
        }

        for (i in avatars.indices) {
            for (j in i + 1 until avatars.size) {
                val a = silhouettes.getValue(avatars[i])
                val b = silhouettes.getValue(avatars[j])
                val widthDiffers = abs(a.width - b.width) > 2f
                val heightDiffers = abs(a.height - b.height) > 2f
                assertTrue(
                    widthDiffers || heightDiffers,
                    "${avatars[i].id} and ${avatars[j].id} have near-identical silhouettes: $a vs $b"
                )
            }
        }
    }

    private val STYLE_0_EQUIPPED: Map<EquipmentSlot, String> = mapOf(
        EquipmentSlot.HAT to "item_hat_trollhatt_nattsvart",
        EquipmentSlot.SHOULDERS_BACK to "item_shoulders_back_cape_kungabla",
        EquipmentSlot.ARMS_GLOVES to "item_arms_gloves_handskar_skogsgron",
        EquipmentSlot.ARMOR_SHIRT to "item_armor_shirt_troja_vintervit",
        EquipmentSlot.PANTS to "item_pants_byxor_barnstensgul",
        EquipmentSlot.SHOES to "item_shoes_stovlar_solgyllene",
        EquipmentSlot.HAND_RIGHT to "item_hand_right_svard_vintervit",
        EquipmentSlot.HAND_LEFT to "item_hand_left_rundskold_isbla",
        EquipmentSlot.TAIL to "item_tail_kattsvans_vintervit"
    )

    @Test
    fun styleZeroOutfitFitsEveryBody() {
        for (avatar in content.avatarsOrdered) {
            val appearance = AvatarAppearance(avatar.id, equipped = STYLE_0_EQUIPPED)
            val drawList = buildAvatarDrawList(content, appearance)
            val bones = boneTransforms(avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

            val torso = bones.getValue(Bone.TORSO)
            val torsoRect = Rect(torso.origin, torso.effectiveSize)
            val shirtUnion = itemUnionPx(content, drawList.layers, "item_armor_shirt_troja_vintervit", bones)
            assertTrue(shirtUnion != null, "${avatar.id}: shirt has no layers")
            assertTrue(
                shirtUnion!!.left <= torsoRect.left + 0.01f &&
                    shirtUnion.top <= torsoRect.top + 0.01f &&
                    shirtUnion.right >= torsoRect.right - 0.01f &&
                    shirtUnion.bottom >= torsoRect.bottom - 0.01f,
                "${avatar.id}: shirt $shirtUnion does not cover torso $torsoRect"
            )

            val head = bones.getValue(Bone.HEAD)
            val headTop = head.origin.y
            val hatUnion = itemUnionPx(content, drawList.layers, "item_hat_trollhatt_nattsvart", bones)
            assertTrue(hatUnion != null, "${avatar.id}: hat has no layers")
            assertTrue(
                hatUnion!!.bottom in (headTop - 0.01f)..(headTop + 0.2f * head.effectiveSize.height + 0.01f),
                "${avatar.id}: hat $hatUnion floats or clips relative to head top $headTop"
            )

            val legL = bones.getValue(Bone.LEG_L)
            val legR = bones.getValue(Bone.LEG_R)
            val pantsUnion = itemUnionPx(content, drawList.layers, "item_pants_byxor_barnstensgul", bones)
            assertTrue(pantsUnion != null, "${avatar.id}: pants have no layers")
            for (leg in listOf(legL, legR)) {
                val legTop70 = leg.origin.y + 0.7f * leg.effectiveSize.height
                assertTrue(
                    pantsUnion!!.top <= leg.origin.y + 0.01f && pantsUnion.bottom >= legTop70 - 0.01f,
                    "${avatar.id}: pants $pantsUnion do not span leg top to 0.7h ($legTop70)"
                )
            }
        }
    }
}
