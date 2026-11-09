package se.lixo.app.content

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarPart
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.BoneOverride
import se.lixo.app.domain.content.ItemPreviewHints
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.content.ZBucket
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.ui.components.avatar.REST_POSE
import se.lixo.app.ui.components.avatar.RigFrame
import se.lixo.app.ui.components.avatar.PlacedLayer
import se.lixo.app.ui.components.avatar.STANDARD_BONE_ORIGIN
import se.lixo.app.ui.components.avatar.avatarFrame
import se.lixo.app.ui.components.avatar.boneTransforms
import se.lixo.app.ui.components.avatar.buildAvatarDrawList
import se.lixo.app.ui.components.avatar.buildItemDrawList
import se.lixo.app.ui.components.avatar.faceMatrix
import se.lixo.app.ui.components.avatar.layerMatrix
import se.lixo.app.ui.components.avatar.placedBoundsPx
import se.lixo.app.ui.components.avatar.previewFrame
import se.lixo.app.ui.components.avatar.previewYawDeg

/**
 * Pure-geometry tests for 020's layer renderer (`LayerGeometry.kt`): bone placement, matrix
 * order, mirroring and preview framing. Never calls `ActiveContent.parsedPath`/`Path()`: the
 * host JVM's `Path()` is backed by `AndroidPath`, which throws here.
 */
class LayerTransformTest {

    private val content = SampleContentPack.activeContent()
    private val cat = content.avatarsById["avatar_cat"]!!

    /** A copy of [content] whose cat renames its "body" part to "torso" (a [se.lixo.app.ui.components.avatar.BASE_BODY_PARTS]
     * part) and adds a "stripes" signature part, plus a test tee shirt with `ghostBody = true`. */
    private fun chibiContent(): ActiveContent {
        val bodyLayer = cat.parts.single { it.part == "body" }.layers.single()
        val chibiCat = cat.copy(
            bones = mapOf(
                Bone.TORSO to BoneOverride(size = listOf(70f, 62f)),
                Bone.ARM_L to BoneOverride(size = listOf(32f, 62f)),
                Bone.ARM_R to BoneOverride(size = listOf(32f, 62f))
            ),
            parts = listOf(
                AvatarPart("torso", listOf(bodyLayer.copy(d = "M 0 0 L 70 0 L 70 62 L 0 62 Z"))),
                AvatarPart("stripes", listOf(bodyLayer.copy(z = ZBucket.TORSO_OVER, d = "M 10 10 L 20 10 L 20 20 L 10 20 Z")))
            ) + cat.parts.filter { it.part != "body" }
        )
        val ghostTee = content.itemsById["item_shirt_test_tee"]!!.copy(preview = ItemPreviewHints(ghostBody = true))
        return ActiveContent.from(
            content.pack.copy(
                avatars = content.pack.avatars.map { if (it.id == cat.id) chibiCat else it },
                items = content.pack.items.map { if (it.id == ghostTee.id) ghostTee else it }
            )
        )
    }

    @Test
    fun itemLayersScaleWithOversizedBone() {
        val hatItem = content.itemsById["item_hat_test_cone"]!!
        val hatLayer = hatItem.layers.single()

        val standardBones = boneTransforms(cat, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val standardPlaced = PlacedLayer(hatLayer, Bone.HEAD, mirrored = false, item = hatItem, partName = null)
        val standardMatrix = layerMatrix(standardPlaced, standardBones)
        val standardDist = hypot(
            (standardMatrix.map(Offset(10f, 0f)) - standardMatrix.map(Offset.Zero)).x,
            (standardMatrix.map(Offset(10f, 0f)) - standardMatrix.map(Offset.Zero)).y
        )
        assertEquals(10f, standardDist, 1e-3f)

        val oversizedCat = cat.copy(bones = mapOf(Bone.HEAD to BoneOverride(size = listOf(112f, 112f))))
        val oversizedBones = boneTransforms(oversizedCat, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val oversizedPlaced = PlacedLayer(hatLayer, Bone.HEAD, mirrored = false, item = hatItem, partName = null)
        val oversizedMatrix = layerMatrix(oversizedPlaced, oversizedBones)
        val oversizedDiff = oversizedMatrix.map(Offset(10f, 0f)) - oversizedMatrix.map(Offset.Zero)
        val oversizedDist = hypot(oversizedDiff.x, oversizedDiff.y)
        assertEquals(20f, oversizedDist, 1e-3f)
    }

    @Test
    fun heldItemScalesUniformlyOnNarrowHand() {
        val slim = cat.copy(bones = mapOf(Bone.HAND_R to BoneOverride(size = listOf(28f, 37.05f))))
        val bones = boneTransforms(slim, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val wand = content.itemsById["item_hand_right_test_wand"]!!
        val layer = wand.layers.single().copy(d = "M 0 0 L 38 0 L 38 38 L 0 38 Z")
        val placed = PlacedLayer(layer, Bone.HAND_R, mirrored = false, item = wand, partName = null)

        val b = placedBoundsPx(content, placed, bones)
        val expected = 38f * sqrt((28f / 38f) * (37.05f / 38f))
        assertEquals(b.height, b.width, 0.01f)
        assertEquals(expected, b.width, 0.01f)
        val expectedCenter = bones[Bone.HAND_R]!!.origin + Offset(14f, 37.05f / 2f)
        assertEquals(expectedCenter.x, b.center.x, 0.01f)
        assertEquals(expectedCenter.y, b.center.y, 0.01f)
    }

    @Test
    fun heldItemOnArmScalesUniformlyAroundHand() {
        val slim = cat.copy(
            bones = mapOf(
                Bone.ARM_R to BoneOverride(size = listOf(28f, 78f)),
                Bone.HAND_R to BoneOverride(size = listOf(28f, 37.05f))
            )
        )
        val bones = boneTransforms(slim, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val wand = content.itemsById["item_hand_right_test_wand"]!!
        val layer = wand.layers.single().copy(bone = Bone.ARM_R, d = "M 9 51 L 29 51 L 29 71 L 9 71 Z")
        val placed = PlacedLayer(layer, Bone.ARM_R, mirrored = false, item = wand, partName = null)

        val b = placedBoundsPx(content, placed, bones)
        val expected = 20f * sqrt((28f / 38f) * (78f / 80f))
        assertEquals(b.height, b.width, 0.01f)
        assertEquals(expected, b.width, 0.01f)
        val expectedCenter = bones[Bone.ARM_R]!!.origin + Offset(14f, 78f - 37.05f / 2f)
        assertEquals(expectedCenter.x, b.center.x, 0.01f)
        assertEquals(expectedCenter.y, b.center.y, 0.01f)
    }

    @Test
    fun glovesStillStretchPerAxis() {
        val slim = cat.copy(bones = mapOf(Bone.HAND_R to BoneOverride(size = listOf(28f, 37.05f))))
        val bones = boneTransforms(slim, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val mitt = content.itemsById["item_gloves_test_mitt"]!!
        val layer = mitt.layers.single().copy(d = "M 0 0 L 38 0 L 38 38 L 0 38 Z")
        val placed = PlacedLayer(layer, Bone.HAND_R, mirrored = true, item = mitt, partName = null)

        val b = placedBoundsPx(content, placed, bones)
        assertEquals(28f, b.width, 0.01f)
        assertEquals(37.05f, b.height, 0.01f)
        val origin = bones[Bone.HAND_R]!!.origin
        assertEquals(origin.x, b.topLeft.x, 0.01f)
        assertEquals(origin.y, b.topLeft.y, 0.01f)
    }

    @Test
    fun mirroredLayerLandsOnOppositeBoneFlipped() {
        val drawList = buildAvatarDrawList(
            content,
            AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.ARMS_GLOVES to "item_gloves_test_mitt"))
        )
        val bones = boneTransforms(drawList.avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val unmirrored = drawList.layers.single { it.bone == Bone.HAND_L && !it.mirrored }
        val mirrored = drawList.layers.single { it.bone == Bone.HAND_R && it.mirrored }

        val mirroredPoint = layerMatrix(mirrored, bones).map(Offset(0f, 5f))
        val unmirroredPoint = layerMatrix(mirrored.copy(mirrored = false), bones).map(Offset(38f, 5f))

        assertEquals(unmirroredPoint.x, mirroredPoint.x, 1e-3f)
        assertEquals(unmirroredPoint.y, mirroredPoint.y, 1e-3f)
        assertTrue(unmirrored.bone == Bone.HAND_L)
    }

    @Test
    fun headParallaxAtYaw90Equals14TimesScale() {
        val bones = boneTransforms(cat, REST_POSE, 90f, Size(680f, 680f))
        assertEquals(28f, bones[Bone.HEAD]!!.parallaxPx, 1e-3f)

        val hatItem = content.itemsById["item_hat_test_cone"]!!
        val hatLayer = hatItem.layers.single()
        val placed = PlacedLayer(hatLayer, Bone.HEAD, mirrored = false, item = hatItem, partName = null)

        val bones0 = boneTransforms(cat, REST_POSE, 0f, Size(680f, 680f))
        val x0 = layerMatrix(placed, bones0).map(Offset.Zero).x
        val x90 = layerMatrix(placed, bones).map(Offset.Zero).x
        assertEquals(28f, x90 - x0, 1e-3f)
    }

    @Test
    fun armRotationPivotsOnShoulder() {
        val pose = REST_POSE.copy(leftArmAngleDeg = 30f)
        val armLayer = cat.parts.single { it.part == "arms" }.layers.single()
        val placed = PlacedLayer(armLayer, Bone.ARM_R, mirrored = true, item = null, partName = "arms")

        val restBones = boneTransforms(cat, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val posedBones = boneTransforms(cat, pose, 0f, RigFrame(Offset.Zero, 1f))

        val restMatrix = layerMatrix(placed, restBones)
        val posedMatrix = layerMatrix(placed, posedBones)

        val pinned = restMatrix.map(Offset(19f, 0f))
        val pinnedPosed = posedMatrix.map(Offset(19f, 0f))
        assertEquals(pinned.x, pinnedPosed.x, 1e-3f)
        assertEquals(pinned.y, pinnedPosed.y, 1e-3f)

        val armTransform = posedBones[Bone.ARM_R]!!
        val mappedPivot = posedMatrix.map(Offset(19f, 0f)) // pivot is at local (19,0) pre-rotation
        val mappedPoint = posedMatrix.map(Offset(19f, 80f))
        val dist = hypot(mappedPoint.x - mappedPivot.x, mappedPoint.y - mappedPivot.y)
        assertEquals(80f, dist, 1e-2f)

        assertEquals(30f, posedBones[Bone.HAND_R]!!.rotationDeg, 1e-3f)
        assertEquals(30f, armTransform.rotationDeg, 1e-3f)
        assertEquals(armTransform.pivot.x, posedBones[Bone.HAND_R]!!.pivot.x, 1e-3f)
        assertEquals(armTransform.pivot.y, posedBones[Bone.HAND_R]!!.pivot.y, 1e-3f)
    }

    @Test
    fun itemPreviewUsesStandardBones() {
        val c = chibiContent()
        val drawList = buildItemDrawList(c, c.itemsById["item_shirt_test_tee"]!!)

        assertTrue(drawList.avatar.bones.isEmpty())
        assertEquals(c.defaultAvatar.skinHex, drawList.avatar.skinHex)

        val bones = boneTransforms(drawList.avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val b = placedBoundsPx(c, drawList.layers.single(), bones)

        assertEquals(70f, b.width, 0.01f)
        assertEquals(70f, b.height, 0.01f)
        val expectedTopLeft = bones[Bone.TORSO]!!.origin + Offset(5f, 5f)
        assertEquals(expectedTopLeft.x, b.topLeft.x, 0.01f)
        assertEquals(expectedTopLeft.y, b.topLeft.y, 0.01f)
    }

    @Test
    fun ghostBodyIsBasePartsAtStandardSize() {
        val c = chibiContent()
        val drawList = buildItemDrawList(c, c.itemsById["item_shirt_test_tee"]!!)

        assertTrue(drawList.ghostLayers.isNotEmpty())
        val baseParts = setOf("torso", "arms", "hands", "legs", "pants", "shoes", "head")
        for (ghost in drawList.ghostLayers) {
            assertTrue(ghost.partName in baseParts)
        }
        assertFalse(drawList.ghostLayers.any { it.partName == "stripes" })

        val bones = boneTransforms(drawList.avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val torso = drawList.ghostLayers.single { it.partName == "torso" }
        val b = placedBoundsPx(c, torso, bones)

        assertEquals(80f, b.width, 0.01f)
        assertEquals(80f, b.height, 0.01f)
        val origin = bones[Bone.TORSO]!!.origin
        assertEquals(origin.x, b.topLeft.x, 0.01f)
        assertEquals(origin.y, b.topLeft.y, 0.01f)
    }

    @Test
    fun ghostPartMirrorsInAuthoredWidth() {
        val bones = boneTransforms(cat.copy(bones = emptyMap()), REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val armLayer = cat.parts.single { it.part == "arms" }.layers.single().copy(d = "M 0 0 L 16 0 L 16 62 L 0 62 Z")
        val placed = PlacedLayer(
            armLayer,
            Bone.ARM_R,
            mirrored = true,
            item = null,
            partName = "arms",
            authoredBoneSize = Size(32f, 62f)
        )
        val b = placedBoundsPx(content, placed, bones)

        val o = bones[Bone.ARM_R]!!.origin
        assertEquals(o.x + 19f, b.left, 0.01f)
        assertEquals(o.x + 38f, b.right, 0.01f)
        assertEquals(80f, b.height, 0.01f)
    }

    @Test
    fun previewFramingFitsBoundsWithMargin() {
        val cape = content.itemsById["item_shoulders_test_cape"]!!
        val drawList = buildItemDrawList(content, cape)
        val yawDeg = previewYawDeg(cape)
        val frame = previewFrame(content, drawList, yawDeg, Size(200f, 100f))!!
        val bones = boneTransforms(drawList.avatar, REST_POSE, yawDeg, frame)

        var union: Rect? = null
        for (p in drawList.layers) {
            val b = placedBoundsPx(content, p, bones)
            union = if (union == null) b else se.lixo.app.domain.content.ContentPaths.union(union, b)
        }
        val u = union!!

        val bounds = Rect(20f, 10f, 180f, 90f)
        assertTrue(u.left >= bounds.left - 0.01f)
        assertTrue(u.top >= bounds.top - 0.01f)
        assertTrue(u.right <= bounds.right + 0.01f)
        assertTrue(u.bottom <= bounds.bottom + 0.01f)

        val widthMatches = abs(u.width - 160f) < 0.01f
        val heightMatches = abs(u.height - 80f) < 0.01f
        assertTrue(widthMatches || heightMatches)

        assertEquals(100f, u.center.x, 0.01f)
        assertEquals(50f, u.center.y, 0.01f)
    }

    @Test
    fun itemLayerStretchesToAvatarTorso_partLayerDoesNot() {
        val torsoCat = cat.copy(bones = mapOf(Bone.TORSO to BoneOverride(size = listOf(100f, 88f))))
        val bones = boneTransforms(torsoCat, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))

        val itemScale = bones[Bone.TORSO]!!.itemScale
        assertEquals(1.25f, itemScale.x, 1e-3f)
        assertEquals(1.1f, itemScale.y, 1e-3f)

        val shirt = content.itemsById["item_shirt_test_tee"]!!
        val shirtLayer = shirt.layers.single()
        val itemPlaced = PlacedLayer(shirtLayer, Bone.TORSO, mirrored = false, item = shirt, partName = null)
        val itemMatrix = layerMatrix(itemPlaced, bones)
        val itemOrigin = itemMatrix.map(Offset.Zero)
        val itemDx = itemMatrix.map(Offset(10f, 0f)) - itemOrigin
        val itemDy = itemMatrix.map(Offset(0f, 10f)) - itemOrigin
        assertEquals(12.5f, itemDx.x, 1e-3f)
        assertEquals(11f, itemDy.y, 1e-3f)

        val partPlaced = PlacedLayer(shirtLayer, Bone.TORSO, mirrored = false, item = null, partName = "torso")
        val partMatrix = layerMatrix(partPlaced, bones)
        val partOrigin = partMatrix.map(Offset.Zero)
        val partDx = partMatrix.map(Offset(10f, 0f)) - partOrigin
        val partDy = partMatrix.map(Offset(0f, 10f)) - partOrigin
        assertEquals(10f, partDx.x, 1e-3f)
        assertEquals(10f, partDy.y, 1e-3f)
    }

    @Test
    fun faceScalesToEffectiveHead() {
        val headCat = cat.copy(bones = mapOf(Bone.HEAD to BoneOverride(size = listOf(66f, 60f))))
        val bones = boneTransforms(headCat, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))
        val head = bones[Bone.HEAD]!!

        val m = faceMatrix(bones)
        val origin = m.map(Offset.Zero)
        assertEquals(head.origin.x, origin.x, 1e-3f)
        assertEquals(head.origin.y, origin.y, 1e-3f)

        val corner = m.map(Offset(56f, 56f))
        assertEquals(head.origin.x + 66f, corner.x, 1e-3f)
        assertEquals(head.origin.y + 60f, corner.y, 1e-3f)
    }

    @Test
    fun avatarFrameKeepsStandardScaleAndShrinksTallAvatar() {
        assertEquals(1f, avatarFrame(Size(340f, 340f), cat).pxPerAu, 1e-3f)

        val tallCat = cat.copy(
            bones = mapOf(
                Bone.HEAD to BoneOverride(size = listOf(50f, 64f)),
                Bone.TORSO to BoneOverride(size = listOf(64f, 80f)),
                Bone.LEG_L to BoneOverride(size = listOf(30f, 84f)),
                Bone.LEG_R to BoneOverride(size = listOf(30f, 84f))
            )
        )
        assertTrue(avatarFrame(Size(340f, 340f), tallCat).pxPerAu < 1f)
    }

    @Test
    fun standardBoneOriginsMatchLegacyLayout() {
        assertEquals(Offset(-40f, -160f), STANDARD_BONE_ORIGIN[Bone.TORSO])
        assertEquals(Offset(-28f, -217.5f), STANDARD_BONE_ORIGIN[Bone.HEAD])
        assertEquals(Offset(-78f, -160f), STANDARD_BONE_ORIGIN[Bone.ARM_R])
        assertEquals(Offset(40f, -160f), STANDARD_BONE_ORIGIN[Bone.ARM_L])
        assertEquals(Offset(34f, -94f), STANDARD_BONE_ORIGIN[Bone.TAIL])
    }
}
