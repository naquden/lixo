package se.lixo.app.content

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertTrue
import se.lixo.app.BundledTestContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentPaths
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.ui.components.avatar.AvatarDrawList
import se.lixo.app.ui.components.avatar.BoneTransform
import se.lixo.app.ui.components.avatar.REST_POSE
import se.lixo.app.ui.components.avatar.RigFrame
import se.lixo.app.ui.components.avatar.boneTransforms
import se.lixo.app.ui.components.avatar.buildAvatarDrawList
import se.lixo.app.ui.components.avatar.itemUnionPx
import se.lixo.app.ui.components.avatar.placedBoundsPx

private const val CAPE = "item_shoulders_back_sv_cape_1"
private const val QUIVER = "item_shoulders_back_pilkoger_isbla"
private const val BALLOON = "item_shoulders_back_ballong_kungabla"

private val BODY_BONES = setOf(Bone.TORSO, Bone.ARM_L, Bone.ARM_R, Bone.HAND_L, Bone.HAND_R)

/**
 * 033's geometry checks for the real bundled SHOULDERS_BACK items, against the real bundled pack
 * (so this lives in androidHostTest, not commonTest -- see `BundledTestContent`). Never calls
 * `ActiveContent.parsedPath`/`Path()`.
 *
 * The quiver only peeks at yaw -90, not +90: it sits over the right shoulder (torso-local x
 * 58..80), and at +90 the back-gear slide moves left, which pushes it further inside the body
 * instead of out past the arm. The cape spans almost the whole torso width, so it peeks on
 * whichever side the slide goes.
 */
class AvatarTurnGeometryTest {

    private val content = BundledTestContent.content

    private fun bodyUnion(drawList: AvatarDrawList, bones: Map<Bone, BoneTransform>): Rect {
        var union: Rect? = null
        for (p in drawList.layers) {
            if (p.item != null || p.bone !in BODY_BONES) continue
            val r = placedBoundsPx(content, p, bones)
            union = if (union == null) r else ContentPaths.union(union, r)
        }
        return union ?: error("no body layers found")
    }

    @Test
    fun capePeeksOnBothSides() {
        for (avatar in content.avatarsOrdered) {
            val appearance = AvatarAppearance(avatar.id, equipped = mapOf(EquipmentSlot.SHOULDERS_BACK to CAPE))
            val drawList = buildAvatarDrawList(content, appearance)

            val bones90 = boneTransforms(avatar, REST_POSE, 90f, RigFrame(Offset.Zero, 1f))
            val body90 = bodyUnion(drawList, bones90)
            val cape90 = itemUnionPx(content, drawList.layers, CAPE, bones90)
            assertTrue(cape90 != null, "${avatar.id}: cape has no layers at yaw 90")
            assertTrue(
                cape90!!.left < body90.left - 4f,
                "${avatar.id}: cape $cape90 does not peek left of body $body90 at yaw 90"
            )

            val bonesM90 = boneTransforms(avatar, REST_POSE, -90f, RigFrame(Offset.Zero, 1f))
            val bodyM90 = bodyUnion(drawList, bonesM90)
            val capeM90 = itemUnionPx(content, drawList.layers, CAPE, bonesM90)
            assertTrue(capeM90 != null, "${avatar.id}: cape has no layers at yaw -90")
            assertTrue(
                capeM90!!.right > bodyM90.right + 4f,
                "${avatar.id}: cape $capeM90 does not peek right of body $bodyM90 at yaw -90"
            )
        }
    }

    @Test
    fun quiverPeeksWhenTurnedLeft() {
        for (avatar in content.avatarsOrdered) {
            val appearance = AvatarAppearance(avatar.id, equipped = mapOf(EquipmentSlot.SHOULDERS_BACK to QUIVER))
            val drawList = buildAvatarDrawList(content, appearance)

            val bonesM90 = boneTransforms(avatar, REST_POSE, -90f, RigFrame(Offset.Zero, 1f))
            val bodyM90 = bodyUnion(drawList, bonesM90)
            val quiverM90 = itemUnionPx(content, drawList.layers, QUIVER, bonesM90)
            assertTrue(quiverM90 != null, "${avatar.id}: quiver has no layers at yaw -90")
            assertTrue(
                quiverM90!!.right > bodyM90.right + 4f,
                "${avatar.id}: quiver $quiverM90 does not peek right of body $bodyM90 at yaw -90"
            )

            val bones0 = boneTransforms(avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
            val body0 = bodyUnion(drawList, bones0)
            val quiver0 = itemUnionPx(content, drawList.layers, QUIVER, bones0)
            assertTrue(quiver0 != null, "${avatar.id}: quiver has no layers at yaw 0")
            assertTrue(
                quiver0!!.left >= body0.left - 0.01f && quiver0.right <= body0.right + 0.01f,
                "${avatar.id}: quiver $quiver0 is not inside body $body0 at yaw 0"
            )
        }
    }

    @Test
    fun balloonPeeksWhenTurnedLeft() {
        for (avatar in content.avatarsOrdered) {
            val appearance = AvatarAppearance(avatar.id, equipped = mapOf(EquipmentSlot.SHOULDERS_BACK to BALLOON))
            val drawList = buildAvatarDrawList(content, appearance)

            val bonesM90 = boneTransforms(avatar, REST_POSE, -90f, RigFrame(Offset.Zero, 1f))
            val bodyM90 = bodyUnion(drawList, bonesM90)
            val balloonM90 = itemUnionPx(content, drawList.layers, BALLOON, bonesM90)
            assertTrue(balloonM90 != null, "${avatar.id}: balloon has no layers at yaw -90")
            assertTrue(
                balloonM90!!.right > bodyM90.right + 4f,
                "${avatar.id}: balloon $balloonM90 does not peek right of body $bodyM90 at yaw -90"
            )
        }
    }
}
