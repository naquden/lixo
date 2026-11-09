package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.DropBoost
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.celebration.ChestRevealStage
import se.lixo.app.ui.components.celebration.REWARD_CHEST_TAG
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 025's themed chest (red/gold/sparkle instead of wood/gold) during the Jul 2026
 * holiday special, against the real [ChestRevealStage] and the real bundled content pack.
 */
class EventChestThemeTest {

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

    private val base = TestContent.active
    private val boosted = ActiveContent.from(
        base.pack.copy(events = base.pack.events.map { it.copy(dropBoost = DropBoost(1f, 1f)) })
    )
    private val tz = TimeZone.currentSystemDefault()
    private val snap = EventEngine.snapshot(
        boosted,
        LocalDateTime(2026, 12, 10, 12, 0).toInstant(tz).toEpochMilliseconds(),
        tz
    )

    @Test
    fun eventChest_usesThemeAndRevealsEventItem() {
        var outcome: se.lixo.app.domain.engine.GiftRollOutcome? = null
        for (seed in 0 until 20) {
            val candidate = ProgressionAndGiftEngine(Random(seed)).rollChest(
                boosted.items, emptySet(), boosted.dropRules, events = snap
            )
            if (candidate?.rolledItem?.eventId == "event_jul_2026") {
                outcome = candidate
                break
            }
        }
        val result = outcome
        assertNotNull("expected a rollChest seed to give an event item", result)
        val item = result!!.rolledItem
        assertEquals("event_jul_2026", item.eventId)

        compose.setContent {
            LixoTheme {
                ChestRevealStage(
                    content = boosted,
                    item = item,
                    source = RewardSource.FLITKISTA,
                    chestTheme = snap.chestTheme,
                    onOpened = {},
                    onFinished = {}
                )
            }
        }
        awaitComposeHierarchy()

        compose.onNodeWithContentDescription(LixoStrings.EVENT_CHEST_DESCRIPTION).assertExists()
        shot("verify_025_chest_closed")

        compose.onNodeWithTag(REWARD_CHEST_TAG).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(boosted.itemName(item)).fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_025_chest_opened")
    }

    @Test
    fun nonEventChest_hasNoTheme() {
        val item = base.items.first { it.id == "item_hat_starter_explorer" }
        compose.setContent {
            LixoTheme {
                ChestRevealStage(
                    content = base,
                    item = item,
                    source = RewardSource.FLITKISTA,
                    onOpened = {},
                    onFinished = {}
                )
            }
        }
        awaitComposeHierarchy()
        compose.onNodeWithContentDescription(LixoStrings.EVENT_CHEST_DESCRIPTION).assertDoesNotExist()
    }
}
