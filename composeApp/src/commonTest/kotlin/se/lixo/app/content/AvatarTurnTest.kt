package se.lixo.app.content

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.BoneOverride
import se.lixo.app.domain.content.BACK_PEEK_AU
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.ui.components.avatar.PlacedLayer
import se.lixo.app.ui.components.avatar.REST_POSE
import se.lixo.app.ui.components.avatar.RigFrame
import se.lixo.app.ui.components.avatar.boneTransforms
import se.lixo.app.ui.components.avatar.clampYaw
import se.lixo.app.ui.components.avatar.layerParallaxPx
import se.lixo.app.ui.components.avatar.turnScaleX

/**
 * Pure-math tests for 033 (rotate avatar up to ±90° so SHOULDERS_BACK gear peeks out):
 * [turnScaleX], [clampYaw] and the [se.lixo.app.ui.components.avatar.BoneTransform.backPeekPx]
 * math in `boneTransforms`/`layerParallaxPx`. Never calls `ActiveContent.parsedPath`/`Path()`.
 */
class AvatarTurnTest {

    private val content = SampleContentPack.activeContent()
    private val std = content.avatarsById["avatar_cat"]!!.copy(bones = emptyMap())
    private val frame = RigFrame(Offset.Zero, 2f)

    @Test
    fun yawZero_noPeek_parallaxUnchanged() {
        val bones = boneTransforms(std, REST_POSE, 0f, frame)
        for (bone in Bone.entries) {
            val t = bones.getValue(bone)
            assertEquals(0f, t.backPeekPx, 1e-6f)
            assertEquals(0f, t.parallaxPx, 1e-6f)
        }

        val cape = content.itemsById["item_shoulders_test_cape"]!!
        val capeLayer = cape.layers.single()
        val placed = PlacedLayer(capeLayer, Bone.TORSO, mirrored = false, item = cape, partName = null)
        assertEquals(0f, layerParallaxPx(placed, bones.getValue(Bone.TORSO)), 1e-6f)
    }

    @Test
    fun yaw90_peekIsFullAndOpposite() {
        val bones90 = boneTransforms(std, REST_POSE, 90f, frame)
        val torso90 = bones90.getValue(Bone.TORSO)
        assertEquals(-BACK_PEEK_AU * 2f, torso90.backPeekPx, 1e-3f)

        val bonesM90 = boneTransforms(std, REST_POSE, -90f, frame)
        val torsoM90 = bonesM90.getValue(Bone.TORSO)
        assertEquals(BACK_PEEK_AU * 2f, torsoM90.backPeekPx, 1e-3f)

        assertEquals(14f * 2f, bones90.getValue(Bone.HEAD).parallaxPx, 1e-3f)

        val cape = content.itemsById["item_shoulders_test_cape"]!!
        val capeLayer = cape.layers.single()
        val placed = PlacedLayer(capeLayer, Bone.TORSO, mirrored = false, item = cape, partName = null)
        val expected = 0.5f * 14f * 2f - BACK_PEEK_AU * 2f
        assertEquals(expected, layerParallaxPx(placed, torso90), 1e-3f)
    }

    @Test
    fun peekScalesWithTorsoWidth() {
        val wideCat = std.copy(bones = mapOf(Bone.TORSO to BoneOverride(size = listOf(100f, 88f))))
        val bones = boneTransforms(wideCat, REST_POSE, 90f, frame)
        val torso = bones.getValue(Bone.TORSO)
        assertEquals(-BACK_PEEK_AU * 1.25f * 2f, torso.backPeekPx, 1e-3f)
    }

    @Test
    fun turnScaleXValues() {
        assertEquals(1f, turnScaleX(0f), 1e-5f)
        assertEquals(0.85f, turnScaleX(90f), 1e-5f)
        assertEquals(0.85f, turnScaleX(-90f), 1e-5f)
    }

    @Test
    fun clampYawValues() {
        assertEquals(90f, clampYaw(200f), 1e-6f)
        assertEquals(-90f, clampYaw(-500f), 1e-6f)
        assertEquals(45f, clampYaw(45f), 1e-6f)
    }

    @Test
    fun backPeekBounded() {
        assertTrue(BACK_PEEK_AU in 40f..60f)
    }
}
