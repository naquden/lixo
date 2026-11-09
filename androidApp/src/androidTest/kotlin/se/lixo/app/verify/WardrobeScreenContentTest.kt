package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.UnlockType
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 022 (the wardrobe runs on the content pack): the avatar picker lists every bundled
 * avatar with the right lock labels, and equipping an item updates both the active profile and
 * the avatar viewport's content description, rendering the real [WardrobeScreen] against the
 * real bundled pack.
 */
class WardrobeScreenContentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var repo: LixoRepository

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
        repo.unlockWardrobeItem("item_hand_right_math_sword_1")

        compose.setContent {
            LixoTheme {
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

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun avatarPicker_showsEveryPackAvatar() {
        val content = TestContent.active
        for (avatar in content.avatarsOrdered) {
            val name = content.string(avatar.nameKey)
            val label = if (avatar.unlock.type == UnlockType.DEFAULT) name else "$name (${avatar.unlock.stars}⭐)"
            compose.onNodeWithText(label).assertExists()
        }
        shot("022_wardrobe_picker")
    }

    @Test
    fun equipItem_updatesViewport() {
        val content = TestContent.active
        val hat = content.itemsById.getValue(content.dropRules.tutorialRewardItemId)
        val sword = content.itemsById.getValue("item_hand_right_math_sword_1")
        val catName = content.avatarName("avatar_cat")

        compose.onNodeWithContentDescription("$catName: ${content.itemName(hat)}").assertExists()

        compose.onNodeWithText(LixoStrings.slotName(EquipmentSlot.HAND_RIGHT)).performScrollTo().performClick()
        compose.onNodeWithText(content.itemName(sword)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(sword.id, repo.profiles.value.first().appearance.equipped[EquipmentSlot.HAND_RIGHT])

        compose.onNodeWithContentDescription(
            "$catName: ${content.itemName(hat)}, ${content.itemName(sword)}"
        ).assertExists()

        shot("022_wardrobe_equipped")
    }
}
