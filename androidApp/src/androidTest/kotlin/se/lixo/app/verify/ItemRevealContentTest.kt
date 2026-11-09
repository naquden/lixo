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
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.celebration.ChestRevealStage
import se.lixo.app.ui.components.celebration.ItemUnlockedDialog
import se.lixo.app.ui.components.celebration.REWARD_CHEST_TAG
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 022's item reveal path against the real bundled pack: [ChestRevealStage] (and
 * [ItemUnlockedDialog]) render a real [se.lixo.app.domain.content.ContentItem] by [ItemPreview],
 * with its localized name and rarity/slot label.
 */
class ItemRevealContentTest {

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
    fun commonItem_revealShowsNameAndRaritySlot() {
        runReveal(Rarity.COMMON)
    }

    @Test
    fun legendaryItem_revealShowsNameAndRaritySlot() {
        runReveal(Rarity.LEGENDARY)
    }

    private fun runReveal(rarity: Rarity) {
        val content = TestContent.active
        val item = content.standardPoolByRarity.getValue(rarity).minBy { it.id }

        compose.mainClock.autoAdvance = false
        var opened = false

        compose.setContent {
            LixoTheme {
                ChestRevealStage(
                    content = content,
                    item = item,
                    source = RewardSource.FLITKISTA,
                    onOpened = { opened = true },
                    onFinished = {}
                )
            }
        }

        awaitComposeHierarchy()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText(LixoStrings.CHEST_TAP_TO_OPEN).assertExists()

        compose.onNodeWithTag(REWARD_CHEST_TAG).performClick()
        compose.mainClock.advanceTimeBy(2000)

        assertTrue("open animation did not finish", opened)
        compose.onNodeWithText(LixoStrings.ITEM_UNLOCKED_TITLE).assertExists()
        compose.onNodeWithText(content.itemName(item)).assertExists()
        compose.onNodeWithText(
            "${LixoStrings.rarityName(rarity).uppercase()} · ${LixoStrings.slotName(item.slot)}"
        ).assertExists()
        shot("022_reveal_${rarity.id}")
    }

    @Test
    fun itemUnlockedDialog_showsContentItemName() {
        val content = TestContent.active
        val item = content.standardPoolByRarity.getValue(Rarity.COMMON).minBy { it.id }

        compose.setContent {
            LixoTheme {
                ItemUnlockedDialog(content = content, item = item, onEquipNow = {}, onClose = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(content.itemName(item)).assertExists()
    }
}
