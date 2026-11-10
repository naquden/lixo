package se.lixo.app.content

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.accepts
import se.lixo.app.ui.components.avatar.REST_POSE
import se.lixo.app.ui.components.avatar.RigFrame
import se.lixo.app.ui.components.avatar.boneTransforms
import se.lixo.app.ui.components.avatar.buildAvatarDrawList
import se.lixo.app.ui.components.avatar.placedBoundsPx

/**
 * Tests for 032's shared hand item pool: [EquipmentSlot.accepts] and the renderer's side-swap
 * mirroring in `buildAvatarDrawList`. Never calls `Path()`, same as [LayerTransformTest].
 */
class SharedHandItemsTest {

    private val content = SampleContentPack.activeContent()
    private val cat = content.avatarsById["avatar_cat"]!!

    @Test
    fun acceptsSharedHandPool() {
        assertTrue(EquipmentSlot.HAND_LEFT.accepts(EquipmentSlot.HAND_RIGHT))
        assertTrue(EquipmentSlot.HAND_RIGHT.accepts(EquipmentSlot.HAND_LEFT))
        assertTrue(EquipmentSlot.HAND_LEFT.accepts(EquipmentSlot.HAND_LEFT))
        assertTrue(EquipmentSlot.HAT.accepts(EquipmentSlot.HAT))

        assertFalse(EquipmentSlot.HAT.accepts(EquipmentSlot.HAND_RIGHT))
        assertFalse(EquipmentSlot.HAND_LEFT.accepts(EquipmentSlot.HAT))
        assertFalse(EquipmentSlot.SHOES.accepts(EquipmentSlot.HAT))
    }

    @Test
    fun wandInLeftHand_drawsMirroredOnHandL() {
        val wand = "item_hand_right_test_wand"
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAND_LEFT to wand))
        )
        val wandLayers = drawList.layers.filter { it.item?.id == wand }

        assertTrue(wandLayers.isNotEmpty())
        assertTrue(wandLayers.all { it.bone == Bone.HAND_L && it.mirrored })
        assertFalse(wandLayers.any { it.bone == Bone.HAND_R })
    }

    @Test
    fun shieldInRightHand_drawsMirroredOnHandR() {
        val shield = "item_hand_left_test_shield"
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAND_RIGHT to shield))
        )
        val shieldLayers = drawList.layers.filter { it.item?.id == shield }

        assertTrue(shieldLayers.isNotEmpty())
        assertTrue(shieldLayers.all { it.bone == Bone.HAND_R && it.mirrored })
        assertFalse(shieldLayers.any { it.bone == Bone.HAND_L })
    }

    @Test
    fun nativeEquip_unchanged() {
        val wand = "item_hand_right_test_wand"
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAND_RIGHT to wand))
        )
        val wandLayers = drawList.layers.filter { it.item?.id == wand }

        assertTrue(wandLayers.isNotEmpty())
        assertTrue(wandLayers.all { it.bone == Bone.HAND_R && !it.mirrored })
    }

    @Test
    fun swappedWand_isMirrorImageAboutBodyCentre() {
        val wand = "item_hand_right_test_wand"
        val bones = boneTransforms(cat.copy(bones = emptyMap()), REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val nativeDrawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAND_RIGHT to wand))
        )
        val swappedDrawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAND_LEFT to wand))
        )

        val native = nativeDrawList.layers.single { it.item?.id == wand }
        val swapped = swappedDrawList.layers.single { it.item?.id == wand }

        val n = placedBoundsPx(content, native, bones)
        val s = placedBoundsPx(content, swapped, bones)

        assertEquals(-n.right, s.left, 1e-3f)
        assertEquals(-n.left, s.right, 1e-3f)
        assertEquals(n.top, s.top, 1e-3f)
        assertEquals(n.bottom, s.bottom, 1e-3f)
    }

    @Test
    fun drawListItems_unchangedOrder() {
        val wand = "item_hand_right_test_wand"
        val shield = "item_hand_left_test_shield"
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance(
                "avatar_cat",
                equipped = mapOf(EquipmentSlot.HAND_LEFT to wand, EquipmentSlot.HAND_RIGHT to shield)
            )
        )

        assertEquals(listOf(shield, wand), drawList.items.map { it.id })
    }
}
