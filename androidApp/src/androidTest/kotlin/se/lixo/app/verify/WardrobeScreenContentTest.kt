package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.UnlockType
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.accepts
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.components.avatar.avatarContentDescription
import se.lixo.app.ui.screens.wardrobe.WARDROBE_ITEM_GRID_TAG
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 022 (the wardrobe runs on the content pack): the avatar picker lists every bundled
 * avatar with the right lock labels, and equipping an item updates both the active profile and
 * the avatar viewport's content description, rendering the real [WardrobeScreen] against the
 * real bundled pack.
 *
 * Also verifies 031 (the avatar always stays visible while browsing and choosing items): the
 * avatar stage is pinned above an adaptive-row item grid, and switching slots resets that grid
 * back to its first ("Ingen") card.
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
    }

    /**
     * Renders the real [WardrobeScreen]. [compose.setContent] can only be called once per test,
     * so every test calls this exactly once, after any extra unlocks it needs. [container] lets
     * the small-screen test bound the available space the way the real app's Scaffold does.
     */
    private fun render(container: Modifier = Modifier.fillMaxSize()) {
        compose.setContent {
            LixoTheme {
                Box(container) {
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

    /** Unlocks the first [n] items of [slot] (by id) through the repository, and returns them. */
    private fun unlockSlotItems(slot: EquipmentSlot, n: Int = 20): List<ContentItem> {
        val items = TestContent.active.items.filter { it.slot == slot }.sortedBy { it.id }.take(n)
        items.forEach { repo.unlockWardrobeItem(it.id) }
        return items
    }

    /** The active profile's exact avatar viewport content description, for assertions. */
    private fun avatarDesc(): String =
        avatarContentDescription(TestContent.active, repo.profiles.value.first().appearance)

    /** Unlocked items of [slot] in the same order the screen's item grid shows them. */
    private fun screenOrder(slot: EquipmentSlot): List<ContentItem> {
        val content = TestContent.active
        val unlocked = repo.unlockedItemIds.value
        return content.items
            .filter { slot.accepts(it.slot) && unlocked.contains(it.id) }
            .sortedWith(compareByDescending<ContentItem> { it.rarity.ordinal }.thenBy { it.id })
    }

    @Test
    fun avatarPicker_showsEveryPackAvatar() {
        render()
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
        render()
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

    @Test
    fun avatarStaysVisible_whileBrowsingItems() {
        unlockSlotItems(EquipmentSlot.HAT)
        render()

        val content = TestContent.active

        compose.onNodeWithContentDescription(avatarDesc()).assertIsDisplayed()

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(avatarDesc()).assertIsDisplayed()

        val target = screenOrder(EquipmentSlot.HAT)
            .last { it.id != content.dropRules.tutorialRewardItemId }

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performScrollToKey(target.id)
        compose.onNodeWithText(content.itemName(target)).performClick()
        compose.waitForIdle()

        assertEquals(target.id, repo.profiles.value.first().appearance.equipped[EquipmentSlot.HAT])
        assertTrue(avatarDesc().contains(content.itemName(target)))

        compose.onNodeWithContentDescription(avatarDesc()).assertIsDisplayed()

        shot("wardrobe_avatar_pinned")
    }

    @Test
    fun slotSwitch_resetsGridToFirstCard() {
        // Both slots need to overflow; otherwise a shared grid state would clamp back to index 0
        // anyway, and the test wouldn't catch a missing per-slot reset.
        unlockSlotItems(EquipmentSlot.HAT)
        unlockSlotItems(EquipmentSlot.HAND_RIGHT)
        render()

        compose.onNodeWithTag(WARDROBE_ITEM_GRID_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        val none = compose.onAllNodesWithText(LixoStrings.WARDROBE_NONE_ITEM)
        if (none.fetchSemanticsNodes().isNotEmpty()) {
            none.onFirst().assertIsNotDisplayed()
        }

        compose.onNodeWithText(LixoStrings.slotName(EquipmentSlot.HAND_RIGHT)).performScrollTo().performClick()
        compose.onNodeWithText(LixoStrings.WARDROBE_NONE_ITEM).assertIsDisplayed()

        compose.onNodeWithText(LixoStrings.slotName(EquipmentSlot.HAT)).performScrollTo().performClick()
        compose.onNodeWithText(LixoStrings.WARDROBE_NONE_ITEM).assertIsDisplayed()

        shot("wardrobe_slot_reset")
    }

    @Test
    fun smallScreen_avatarAndOneRowVisible() {
        unlockSlotItems(EquipmentSlot.HAT)
        render(Modifier.size(360.dp, 640.dp))

        val content = TestContent.active

        compose.onNodeWithContentDescription(avatarDesc()).assertIsDisplayed()
        compose.onNodeWithText(LixoStrings.WARDROBE_NONE_ITEM).assertIsDisplayed()
        compose.onNodeWithText(content.itemName(screenOrder(EquipmentSlot.HAT).first())).assertIsDisplayed()

        shot("wardrobe_small_screen")
    }
}
