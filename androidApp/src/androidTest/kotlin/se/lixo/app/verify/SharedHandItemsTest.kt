package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.components.avatar.avatarContentDescription
import se.lixo.app.ui.screens.wardrobe.WARDROBE_ITEM_GRID_TAG
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 032 (shared hand items): the HAND_LEFT and HAND_RIGHT pills list the same pool of
 * unlocked hand items, equipping one moves it into that hand (mirrored), and it can move back,
 * rendering the real [WardrobeScreen] against the real bundled pack (doc/plans/032).
 */
class SharedHandItemsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var repo: LixoRepository

    private val SWORD = "item_hand_right_math_sword_1"
    private val SHIELD = "item_hand_left_rundskold_isbla"

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
            instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }

        repo = LixoRepository(
            FakeCloudGateway(),
            lexiconCache = null,
            isDebugBuild = true,
            profileStore = null,
            content = TestContent.repository
        )
        repo.unlockWardrobeItem(SWORD)
        repo.unlockWardrobeItem(SHIELD)
    }

    private fun render() {
        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize()) {
                    val profiles = repo.profiles.collectAsState().value
                    val unlocked = repo.unlockedItemIds.collectAsState().value
                    WardrobeScreen(
                        profile = profiles.first(),
                        content = TestContent.active,
                        unlockedItemIds = unlocked,
                        ttsEngine = NoOpTextToSpeechEngine,
                        showSwedishTtsButtons = false,
                        onSelectAvatar = repo::selectAvatarOnActiveProfile,
                        onEquipSlotItem = repo::equipItemOnActiveProfile,
                        onCustomizeBaseTraits = { h, e, s -> repo.customizeBaseAppearanceOnActiveProfile(h, e, s) },
                        onBack = {}
                    )
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun equipped() = repo.profiles.value.first().appearance.equipped

    private fun pill(slot: EquipmentSlot) =
        compose.onNodeWithText(LixoStrings.slotName(slot)).performScrollTo().performClick()

    private fun avatarDesc(): String =
        avatarContentDescription(TestContent.active, repo.profiles.value.first().appearance)

    @Test
    fun leftHand_listsBoth_equipsSword() {
        render()
        val content = TestContent.active
        val sword = content.itemsById.getValue(SWORD)
        val shield = content.itemsById.getValue(SHIELD)
        val swordName = content.itemName(sword)
        val shieldName = content.itemName(shield)

        pill(EquipmentSlot.HAND_LEFT)
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).assertExists()
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(shield.id)
        compose.onNodeWithText(shieldName).assertExists()

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).performClick()
        compose.waitForIdle()

        assertEquals(SWORD, equipped()[EquipmentSlot.HAND_LEFT])
        assertNull(equipped()[EquipmentSlot.HAND_RIGHT])

        compose.onNodeWithContentDescription(avatarDesc()).assertExists()
        assertTrue(avatarDesc().contains(swordName))

        shot("032_sword_left_hand")
    }

    @Test
    fun rightHand_listsShield_equipsIt() {
        render()
        val content = TestContent.active
        val sword = content.itemsById.getValue(SWORD)
        val shield = content.itemsById.getValue(SHIELD)
        val swordName = content.itemName(sword)
        val shieldName = content.itemName(shield)

        pill(EquipmentSlot.HAND_RIGHT)
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).assertExists()
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(shield.id)
        compose.onNodeWithText(shieldName).assertExists()

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(shield.id)
        compose.onNodeWithText(shieldName).performClick()
        compose.waitForIdle()

        assertEquals(SHIELD, equipped()[EquipmentSlot.HAND_RIGHT])
        assertTrue(avatarDesc().contains(shieldName))

        shot("032_shield_right_hand")
    }

    @Test
    fun swordMovesBetweenHands() {
        render()
        val content = TestContent.active
        val sword = content.itemsById.getValue(SWORD)
        val swordName = content.itemName(sword)

        pill(EquipmentSlot.HAND_RIGHT)
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).performClick()
        compose.waitForIdle()
        assertEquals(SWORD, equipped()[EquipmentSlot.HAND_RIGHT])

        pill(EquipmentSlot.HAND_LEFT)
        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).performClick()
        compose.waitForIdle()
        assertEquals(SWORD, equipped()[EquipmentSlot.HAND_LEFT])
        assertNull(equipped()[EquipmentSlot.HAND_RIGHT])

        pill(EquipmentSlot.HAND_RIGHT)
        compose.onNodeWithText(swordName).assertIsNotSelected()
        compose.onNodeWithText(LixoStrings.WARDROBE_NONE_ITEM).assertIsSelected()

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(sword.id)
        compose.onNodeWithText(swordName).performClick()
        compose.waitForIdle()
        assertEquals(SWORD, equipped()[EquipmentSlot.HAND_RIGHT])
        assertNull(equipped()[EquipmentSlot.HAND_LEFT])

        shot("032_sword_moved")
    }
}
