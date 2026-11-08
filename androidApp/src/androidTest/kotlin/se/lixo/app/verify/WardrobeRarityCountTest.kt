package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.screens.wardrobe.wardrobeRarityCountTag
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 011 section 5 (doc/plans/011-rarity-odds-and-feel.md): the wardrobe shows
 * per-rarity owned/total collection counts against the whole catalog, rendering the real
 * [WardrobeScreen] with a real [LixoRepository].
 */
class WardrobeRarityCountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File

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
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * Blocks until the compose test rule can actually see a semantics tree, retrying
     * [androidx.compose.ui.test.junit4.ComposeTestRule.waitForIdle] instead of asserting after a
     * single call. On some physical devices the test [android.app.Activity] has resumed after
     * `setContent` returned, so the very first [androidx.compose.ui.test.onRoot] lookup threw
     * `IllegalStateException: No compose hierarchies found in the app`.
     */
    private fun awaitComposeHierarchy() {
        var lastError: IllegalStateException? = null
        repeat(20) {
            try {
                compose.waitForIdle()
                compose.onRoot().assertExists()
                return
            } catch (e: IllegalStateException) {
                lastError = e
                Thread.sleep(100)
            }
        }
        lastError?.let { throw it }
    }

    /**
     * Shared fixture for both wardrobe rarity tests: 2 unlocked COMMON HATs and 1 unlocked
     * LEGENDARY HAT (plus a stale unknown id), rendering the real [WardrobeScreen].
     */
    private fun setUpWardrobeWithCommonAndLegendary(): Triple<LixoRepository, List<WardrobeItem>, WardrobeItem> {
        val repo = LixoRepository(FakeCloudGateway(), lexiconCache = null, isDebugBuild = false, profileStore = null)
        val profile = repo.createProfile("Testa", 7)
        val catalog = repo.wardrobeCatalog.value

        val commons = catalog
            .filter { it.rarity == Rarity.COMMON && it.slot == EquipmentSlot.HAT }
            .sortedBy { it.id }
            .take(2)
        val legendary = catalog
            .filter { it.rarity == Rarity.LEGENDARY && it.slot == EquipmentSlot.HAT }
            .minBy { it.id }
        assertTrue("need at least 2 COMMON HAT items", commons.size == 2)

        commons.forEach { repo.unlockWardrobeItem(it.id) }
        repo.unlockWardrobeItem(legendary.id)
        repo.unlockWardrobeItem("item_does_not_exist")
        assertEquals(4, repo.unlockedItemIds.value.size)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            LixoTheme {
                WardrobeScreen(
                    profile = profile,
                    allWardrobeItems = catalog,
                    unlockedItemIds = repo.unlockedItemIds.collectAsState().value,
                    ttsEngine = remember { createPlatformTextToSpeechEngine() },
                    showSwedishTtsButtons = false,
                    onSelectAvatarType = {},
                    onEquipSlotItem = { _, _ -> },
                    onCustomizeBaseTraits = { _, _, _ -> },
                    onBack = {}
                )
            }
        }
        awaitComposeHierarchy()
        compose.mainClock.advanceTimeBy(700)
        return Triple(repo, catalog, legendary)
    }

    @Test
    fun wardrobe_showsPerRarityCollectionCounts() {
        val (repo, catalog, legendary) = setUpWardrobeWithCommonAndLegendary()

        for (r in Rarity.entries) {
            val total = catalog.count { it.rarity == r }
            val owned = when (r) {
                Rarity.COMMON -> 2
                Rarity.LEGENDARY -> 1
                else -> 0
            }
            compose.onNodeWithTag(wardrobeRarityCountTag(r))
                .assertExists()
                .assertTextEquals(LixoStrings.wardrobeRarityCount(r, owned, total))
        }
        compose.onNodeWithTag(wardrobeRarityCountTag(Rarity.COMMON)).assertTextContains("2/", substring = true)
        compose.onNodeWithTag(wardrobeRarityCountTag(Rarity.LEGENDARY)).assertTextContains("1/", substring = true)
        compose.onNodeWithText(legendary.nameSv).assertExists()

        shot("wardrobe_rarity_counts")
    }

    @Test
    fun wardrobe_legendaryBadgeStaysOnOneLine() {
        setUpWardrobeWithCommonAndLegendary()

        val legendaryText = LixoStrings.rarityName(Rarity.LEGENDARY).uppercase()
        val commonText = LixoStrings.rarityName(Rarity.COMMON).uppercase()

        val legendaryNode = compose.onNodeWithText(legendaryText).assertExists().fetchSemanticsNode()
        // There are TWO COMMON (VANLIG) cards unlocked, so onNodeWithText would throw on multiple
        // matches; take the first one.
        val commonNode = compose.onAllNodesWithText(commonText)[0].fetchSemanticsNode()

        assertEquals(commonNode.size.height, legendaryNode.size.height)

        val layouts = mutableListOf<TextLayoutResult>()
        legendaryNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.first().lineCount)
        assertFalse(layouts.first().didOverflowWidth)

        shot("012_wardrobe_legendary_label")
    }
}
