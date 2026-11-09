package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.components.EVENT_BANNER_TAG
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 025 (Jul 2026 holiday special banner + sheet) against the real [CampaignMapScreen]
 * with the real bundled content pack.
 */
class EventBannerTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun renderMap(activeEvents: List<se.lixo.app.domain.content.ContentEvent>, unlockedItemIds: Set<String>) {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        val progressByLevelId = repo.levelProgressByProfile.value[profile.id].orEmpty()

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progressByLevelId,
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = Subject.SWEDISH,
                    onSelectSubject = {},
                    onSelectLevel = {},
                    onSwitchProfileClick = {},
                    activeEvents = activeEvents,
                    unlockedItemIds = unlockedItemIds
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun bannerShownInsideWindow() {
        val repo = DummyData.populatedRepository()
        val content = repo.content.active.value
        val tz = TimeZone.currentSystemDefault()
        val inside = LocalDateTime(2026, 12, 10, 12, 0).toInstant(tz).toEpochMilliseconds()
        val events = EventEngine.activeEvents(content, inside, tz)
        assertTrue("expected an active event on Dec 10 2026", events.isNotEmpty())

        val event = events.first { it.id == "event_jul_2026" }
        val firstJulItemId = event.itemIds.first()

        renderMap(events, setOf(firstJulItemId))

        compose.onNodeWithText("God Jul!").assertExists()
        shot("verify_025_banner")

        compose.onNodeWithTag(EVENT_BANNER_TAG).performClick()
        compose.waitForIdle()

        for (item in EventEngine.eventItems(content, event)) {
            compose.onNodeWithContentDescription(content.itemName(item)).assertExists()
        }
        compose.onAllNodesWithContentDescription(LixoStrings.EVENT_ITEM_OWNED).assertCountEquals(1)
        shot("verify_025_event_sheet")
    }

    @Test
    fun bannerHiddenBeforeWindow() {
        val repo = DummyData.populatedRepository()
        val content = repo.content.active.value
        val tz = TimeZone.currentSystemDefault()
        val before = LocalDateTime(2026, 11, 30, 23, 59).toInstant(tz).toEpochMilliseconds()
        val events = EventEngine.activeEvents(content, before, tz)
        assertTrue(events.isEmpty())

        renderMap(events, emptySet())

        compose.onNodeWithText("God Jul!").assertDoesNotExist()
        shot("verify_025_no_banner")
    }

    @Test
    fun bannerHiddenAfterWindow() {
        val repo = DummyData.populatedRepository()
        val content = repo.content.active.value
        val tz = TimeZone.currentSystemDefault()
        val after = LocalDateTime(2026, 12, 27, 0, 0).toInstant(tz).toEpochMilliseconds()
        val events = EventEngine.activeEvents(content, after, tz)
        assertTrue(events.isEmpty())

        renderMap(events, emptySet())

        compose.onNodeWithText("God Jul!").assertDoesNotExist()
        shot("verify_025_no_banner")
    }
}
