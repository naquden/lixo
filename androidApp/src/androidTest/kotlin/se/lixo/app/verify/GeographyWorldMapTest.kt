package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.screens.map.MAP_LEVEL_NODE_TAG_PREFIX
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies the "Världsresan" geography world (plan 037) is reachable on the real
 * [CampaignMapScreen] with the real bundled content pack: it is listed as a world, its first
 * level is unlocked, and the rest are locked until completed.
 */
class GeographyWorldMapTest {

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
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots/verify037").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun geographyWorld_isListedWithFirstLevelUnlockedOnly() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        var selected: CampaignLevel? by mutableStateOf(null)

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = emptyMap(),
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = Subject.GEOGRAPHY,
                    onSelectSubject = {},
                    onSelectLevel = { selected = it },
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText("Världsresan").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Sagoskogen").assertExists()
        compose.onNodeWithText("Sifferdalen").assertExists()
        compose.onNodeWithText("Glosornas Hamn").assertExists()

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + "level_161").assertIsEnabled()
        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + "level_162").assertIsNotEnabled()
        shot("037_geo_map")

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + "level_161").performClick()
        // The level sheet's start button is the hard-coded "STARTA BANA" (CampaignMapScreen.kt ~447),
        // not LixoStrings.MAP_PLAY_LEVEL_BTN ("Starta bana", which is the tutorial screen's button).
        compose.onNodeWithText("STARTA BANA").performClick()
        compose.waitForIdle()

        assertTrue(selected?.id == "level_161")
    }
}
