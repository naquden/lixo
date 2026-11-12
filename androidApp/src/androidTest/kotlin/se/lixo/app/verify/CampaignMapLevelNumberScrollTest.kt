package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.screens.map.MAP_LEVEL_NODE_TAG_PREFIX
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies the per-node world-local level number (replacing the subject icon on unlocked nodes)
 * and the auto-scroll to the next available level on entering a world page (doc/plans/046).
 */
class CampaignMapLevelNumberScrollTest {

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
        outDir = File(baseDir, "lixo_screenshots/verify046").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun nodeShot(tag: String, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun world(repo: se.lixo.app.data.repository.LixoRepository, subject: Subject): List<CampaignLevel> =
        repo.levels.value.filter { it.subject == subject }.sortedBy { it.order }

    private fun done(levels: List<CampaignLevel>, stars: Int = 1): Map<String, LevelProgress> =
        levels.associate { level ->
            level.id to LevelProgress(
                profileId = "profile",
                levelId = level.id,
                levelOrder = level.order,
                starsEarned = stars,
                completedInAiMode = false,
                bestTimeSeconds = 30,
                bestWpm = 0
            )
        }

    @Test
    fun levelNodes_showWorldLocalNumber() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        var subject by mutableStateOf(Subject.GEOGRAPHY)
        var progress by mutableStateOf<Map<String, LevelProgress>>(emptyMap())

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progress,
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = subject,
                    onSelectSubject = { subject = it },
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        val unlockedNode = compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + "level_161")
        val texts = unlockedNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertEquals(1, texts.size)
        assertEquals("1", texts.joinToString("") { it.text })
        unlockedNode.assertContentDescriptionEquals("Bana 1")
        nodeShot(MAP_LEVEL_NODE_TAG_PREFIX + "level_161", "node_unlocked")

        val lockedNode = compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + "level_162")
        val lockedTexts = lockedNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertEquals("2", lockedTexts.joinToString("") { it.text })
        lockedNode.assertIsNotEnabled()
        nodeShot(MAP_LEVEL_NODE_TAG_PREFIX + "level_162", "node_locked")

        // 3 stars on the first Swedish level -> gold node, still shows "1".
        subject = Subject.SWEDISH
        val swedishLevels = world(repo, Subject.SWEDISH)
        progress = done(swedishLevels.take(1), stars = 3)
        compose.waitForIdle()
        nodeShot(MAP_LEVEL_NODE_TAG_PREFIX + swedishLevels.first().id, "node_gold")

        // 100th math level: the only 3-digit case.
        subject = Subject.MATH
        val mathLevels = world(repo, Subject.MATH)
        progress = done(mathLevels.take(99), stars = 1)
        compose.waitForIdle()
        val mathLast = mathLevels.last()
        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + mathLast.id).assertIsDisplayed()
        nodeShot(MAP_LEVEL_NODE_TAG_PREFIX + mathLast.id, "node_3digit")
    }

    @Test
    fun entering_scrollsToNextAvailableLevel() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        val swedishLevels = world(repo, Subject.SWEDISH)
        var subject by mutableStateOf(Subject.SWEDISH)
        var progress by mutableStateOf(done(swedishLevels.take(20)))

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progress,
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = subject,
                    onSelectSubject = { subject = it },
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + swedishLevels[20].id).assertIsDisplayed()
        compose.onNodeWithText("START").assertIsDisplayed()
        shot("swedish_scrolled")
    }

    @Test
    fun switchingWorld_scrollsThatWorld() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        val swedishLevels = world(repo, Subject.SWEDISH)
        val mathLevels = world(repo, Subject.MATH)
        var subject by mutableStateOf(Subject.SWEDISH)
        var progress by mutableStateOf(done(swedishLevels.take(20)) + done(mathLevels.take(15)))

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progress,
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = subject,
                    onSelectSubject = { subject = it },
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Sifferdalen").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + mathLevels[15].id).assertIsDisplayed()
        shot("math_scrolled")
    }

    @Test
    fun returningWithNewProgress_scrollsToNewTarget() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first()
        val swedishLevels = world(repo, Subject.SWEDISH)
        var subject by mutableStateOf(Subject.SWEDISH)
        var progress by mutableStateOf(done(swedishLevels.take(20)))

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progress,
                    content = repo.content.active.value,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = subject,
                    onSelectSubject = { subject = it },
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        progress = done(swedishLevels.take(30))
        compose.waitForIdle()

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + swedishLevels[30].id).assertIsDisplayed()
        shot("swedish_scrolled_30")
    }
}
