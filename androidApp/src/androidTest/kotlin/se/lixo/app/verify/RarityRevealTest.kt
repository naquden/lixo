package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.celebration.ChestRevealStage
import se.lixo.app.ui.components.celebration.REWARD_CHEST_TAG
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 011 section 4 (doc/plans/011-rarity-odds-and-feel.md): the closed chest's rarity
 * teaser (glow, pulsing frame, sparkles, LEGENDARY shake) and the ~30% longer LEGENDARY open,
 * rendering the real [ChestRevealStage] for an EPIC and a LEGENDARY item from the real catalog.
 */
class RarityRevealTest {

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
     * [ComposeTestRule.waitForIdle] instead of asserting after a single call. Physical devices
     * have occasionally resumed the test [android.app.Activity] after `setContent` returned,
     * so the very first [androidx.compose.ui.test.onRoot] lookup threw
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

    @Test
    fun commonChest_teaserThenReveal() {
        runReveal(Rarity.COMMON)
    }

    @Test
    fun epicChest_teaserThenReveal() {
        runReveal(Rarity.EPIC)
    }

    @Test
    fun legendaryChest_teaserThenReveal() {
        runReveal(Rarity.LEGENDARY)
    }

    private fun runReveal(rarity: Rarity) {
        val candidates = LixoRepository.buildSeedWardrobeCatalog().filter { it.rarity == rarity }
        assertTrue("no catalog item found for rarity $rarity", candidates.isNotEmpty())
        val item = candidates.minBy { it.id }

        compose.mainClock.autoAdvance = false
        var opened = false

        compose.setContent {
            LixoTheme {
                ChestRevealStage(
                    item = item,
                    source = RewardSource.FLITKISTA,
                    onOpened = { opened = true },
                    onFinished = {}
                )
            }
        }

        // On some physical devices the first frame after setContent races with the activity
        // reaching RESUMED state, so getAllSemanticsNodes() can momentarily find no compose
        // hierarchy. Retry idling until the hierarchy is actually attached before relying on
        // it for the frozen-clock advances below.
        awaitComposeHierarchy()
        compose.mainClock.advanceTimeBy(700)

        compose.onNodeWithText(LixoStrings.CHEST_TAP_TO_OPEN).assertExists()
        compose.onNodeWithText(LixoStrings.rewardSourceLabel(RewardSource.FLITKISTA)).assertExists()
        compose.onNodeWithTag(REWARD_CHEST_TAG).assertExists()
        shot("rarity_${rarity.id}_closed")

        compose.onNodeWithTag(REWARD_CHEST_TAG).performClick()
        compose.mainClock.advanceTimeBy(2000)

        assertTrue("open animation did not finish", opened)
        compose.onNodeWithText(LixoStrings.ITEM_UNLOCKED_TITLE).assertExists()
        compose.onNodeWithText(item.nameSv).assertExists()
        compose.onNodeWithText(
            "${LixoStrings.rarityName(rarity).uppercase()} · ${LixoStrings.slotName(item.slot)}"
        ).assertExists()
        shot("rarity_${rarity.id}_opened")
    }
}
