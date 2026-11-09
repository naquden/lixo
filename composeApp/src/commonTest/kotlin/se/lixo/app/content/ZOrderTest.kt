package se.lixo.app.content

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentLayer
import se.lixo.app.domain.content.LayerFill
import se.lixo.app.domain.content.PaletteRole
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ZBucket
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.ui.components.avatar.PlacedLayer
import se.lixo.app.ui.components.avatar.avatarContentDescription
import se.lixo.app.ui.components.avatar.buildAvatarDrawList
import se.lixo.app.ui.components.avatar.sortLayers

/** `sortLayers`/`buildAvatarDrawList` z-order, `hidesParts` and unknown-id-skipping tests (020). */
class ZOrderTest {

    private val content = SampleContentPack.activeContent()

    private val allFixtureItems = mapOf(
        EquipmentSlot.HAT to "item_hat_test_cone",
        EquipmentSlot.SHOULDERS_BACK to "item_shoulders_test_cape",
        EquipmentSlot.ARMS_GLOVES to "item_gloves_test_mitt",
        EquipmentSlot.ARMOR_SHIRT to "item_shirt_test_tee",
        EquipmentSlot.PANTS to "item_pants_test_shorts",
        EquipmentSlot.SHOES to "item_shoes_test_boot",
        EquipmentSlot.HAND_RIGHT to "item_hand_right_test_wand",
        EquipmentSlot.HAND_LEFT to "item_hand_left_test_shield",
        EquipmentSlot.TAIL to "item_tail_test_bow"
    )

    private fun syntheticHatLayer(order: Int): ContentLayer = ContentLayer(
        bone = Bone.HEAD,
        z = ZBucket.HAT,
        order = order,
        d = "M 0 0 L 1 0 L 1 1 Z",
        fill = LayerFill(role = PaletteRole.PRIMARY)
    )

    @Test
    fun shuffledLayersSortByBucketThenOrder() {
        val drawList = buildAvatarDrawList(content, AvatarAppearance("avatar_cat", equipped = allFixtureItems))
        val synthetic = listOf(2, 0, 1).map { order ->
            PlacedLayer(syntheticHatLayer(order), Bone.HEAD, mirrored = false, item = null, partName = "synthetic_$order")
        }
        val shuffled = (drawList.layers + synthetic).shuffled(Random(42))
        val sorted = sortLayers(shuffled)

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1].layer
            val cur = sorted[i].layer
            val prevKey = prev.z.ordinal to prev.order
            val curKey = cur.z.ordinal to cur.order
            assertTrue(
                prevKey.first < curKey.first || (prevKey.first == curKey.first && prevKey.second <= curKey.second),
                "out of order at $i: $prevKey then $curKey"
            )
        }

        val hatSyntheticOrders = sorted
            .filter { it.partName?.startsWith("synthetic_") == true }
            .map { it.layer.order }
        assertEquals(listOf(0, 1, 2), hatSyntheticOrders)
    }

    @Test
    fun hidesPartsRemovesEars() {
        val withHat = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAT to "item_hat_test_cone"))
        )
        assertFalse(withHat.layers.any { it.partName == "ears" })

        val withoutHat = buildAvatarDrawList(content, AvatarAppearance("avatar_cat"))
        assertTrue(withoutHat.layers.any { it.partName == "ears" })
    }

    @Test
    fun unknownEquippedIdIsSkipped() {
        val withUnknown = buildAvatarDrawList(
            content,
            AvatarAppearance(
                "avatar_cat",
                equipped = mapOf(
                    EquipmentSlot.TAIL to "item_does_not_exist",
                    EquipmentSlot.HAT to "item_hat_test_cone"
                )
            )
        )
        val hatOnly = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.HAT to "item_hat_test_cone"))
        )

        assertEquals(hatOnly.layers, withUnknown.layers)
        assertEquals(listOf("item_hat_test_cone"), withUnknown.items.map { it.id })
        assertEquals(
            "Katten Lixo: Testhatt",
            avatarContentDescription(
                content,
                AvatarAppearance(
                    "avatar_cat",
                    equipped = mapOf(
                        EquipmentSlot.TAIL to "item_does_not_exist",
                        EquipmentSlot.HAT to "item_hat_test_cone"
                    )
                )
            )
        )
    }

    @Test
    fun mirrorExpansionAddsOppositeLegCopy() {
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.PANTS to "item_pants_test_shorts"))
        )
        val legWearLayers = drawList.layers.filter { it.layer.z == ZBucket.LEGWEAR }
        assertTrue(legWearLayers.any { it.bone == Bone.LEG_L && !it.mirrored })
        assertTrue(legWearLayers.any { it.bone == Bone.LEG_R && it.mirrored })
    }
}
