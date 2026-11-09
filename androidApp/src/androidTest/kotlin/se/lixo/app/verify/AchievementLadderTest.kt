package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RunFacts
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.screens.social.TrophiesScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 014 (achievement ladders / challenge cards) against the real
 * [TrophiesScreen]/[ProgressionAndGiftEngine]/[CelebrationSequenceHost], using real seeds.
 */
class AchievementLadderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File

    private class RecordingTts : TextToSpeechEngine {
        val spoken = mutableListOf<String>()
        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
        override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)
        override fun speak(text: String, languageIso: String, speechRate: Float) {
            spoken += text
        }
        override fun stop() {}
    }

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

    private val challengeCardTag = SemanticsMatcher("tag") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("challenge_") == true
    }

    @Test
    fun freshProfile_showsOneCardPerChainAtTier1() {
        val definitions = LixoRepository.buildSeedAchievements()
        val profile = ChildProfile(id = "p_fresh014", displayName = "Saga")

        compose.setContent {
            LixoTheme {
                TrophiesScreen(
                    achievements = AchievementEngine.withProfileProgress(definitions, profile),
                    ttsEngine = RecordingTts()
                )
            }
        }
        compose.waitForIdle()

        assertTrue(compose.onAllNodes(challengeCardTag).fetchSemanticsNodes().size == 9)
        assertTrue(compose.onAllNodesWithText("Läs 1 saga", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("ladder_tier_ach_read_story_50").assertExists()
        assertTrue(compose.onAllNodesWithContentDescription("Läs upp").fetchSemanticsNodes().isNotEmpty())

        shot("verify_014_trophies_fresh")
    }

    @Test
    fun afterTier1_nextChallengeAppears() {
        val definitions = LixoRepository.buildSeedAchievements()
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(catalog)
        val readingLevel = levels.first { it.subject == Subject.SWEDISH }
        val today = DailyActivityRecord(profileId = "p_tier1", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine().recordLevelCompletion(
            profile = ChildProfile(id = "p_tier1", displayName = "Saga"),
            level = readingLevel,
            previousProgress = null,
            earnedStarsThisRun = 2,
            completedInAiMode = false,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 6,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = definitions,
            runFacts = RunFacts(Subject.SWEDISH, readingLevel.id, isAiMode = false)
        )

        val profileState = mutableStateOf(outcome.updatedProfile)
        compose.setContent {
            LixoTheme {
                TrophiesScreen(
                    achievements = AchievementEngine.withProfileProgress(definitions, profileState.value),
                    ttsEngine = RecordingTts()
                )
            }
        }
        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText("Läs 3 sagor", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("ladder_tier_ach_read_story_1").assertExists()

        shot("verify_014_trophies_after_tier1")
    }

    @Test
    fun speakerButton_speaksInstruction() {
        val definitions = LixoRepository.buildSeedAchievements()
        val profile = ChildProfile(id = "p_speak014", displayName = "Saga")
        val tts = RecordingTts()

        compose.setContent {
            LixoTheme {
                TrophiesScreen(
                    achievements = AchievementEngine.withProfileProgress(definitions, profile),
                    ttsEngine = tts
                )
            }
        }
        compose.waitForIdle()

        compose.onNode(hasContentDescription("Läs upp") and hasAnyAncestor(hasTestTag("challenge_READ_STORY")))
            .performScrollTo()
            .performClick()

        assertTrue(tts.spoken.isNotEmpty())
        assertTrue("expected tier-1 (singular) text, got ${tts.spoken.last()}", tts.spoken.last().contains("Läs 1 saga"))
    }

    @Test
    fun celebration_showsNextChallenge() {
        val definitions = LixoRepository.buildSeedAchievements()
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(catalog)
        val readingLevel = levels.first { it.subject == Subject.SWEDISH }
        val today = DailyActivityRecord(profileId = "p_celeb014", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine().recordLevelCompletion(
            profile = ChildProfile(id = "p_celeb014", displayName = "Saga"),
            level = readingLevel,
            previousProgress = null,
            earnedStarsThisRun = 2,
            completedInAiMode = false,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 6,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = definitions,
            runFacts = RunFacts(Subject.SWEDISH, readingLevel.id, isAiMode = false)
        )
        val tts = RecordingTts()

        compose.setContent {
            LixoTheme {
                CelebrationSequenceHost(
                    outcome = outcome,
                    starsEarned = 3,
                    onEquipItem = {},
                    onSelectAvatar = {},
                    ttsEngine = tts
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Prestation: Sagoläsare 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(se.lixo.app.ui.components.celebration.REWARD_CHEST_TAG).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(LixoStrings.SHEET_CLOSE_BTN).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(LixoStrings.SHEET_CLOSE_BTN).performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Ny utmaning!").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("Läs 3 sagor", substring = true).fetchSemanticsNodes().isNotEmpty())

        shot("verify_014_next_challenge")
    }

    @Test
    fun ladderStrip_fitsAt360dp() {
        val definitions = LixoRepository.buildSeedAchievements()
        val profile = ChildProfile(id = "p_360_018", displayName = "Saga")

        compose.setContent {
            LixoTheme {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    TrophiesScreen(
                        achievements = AchievementEngine.withProfileProgress(definitions, profile),
                        ttsEngine = RecordingTts()
                    )
                }
            }
        }
        compose.waitForIdle()

        val lastTier = compose.onNodeWithTag("ladder_tier_ach_stars_480")
        lastTier.performScrollTo().assertIsDisplayed().assertWidthIsEqualTo(26.dp)

        val tierRight = lastTier.getBoundsInRoot().right
        val cardRight = compose.onNodeWithTag("challenge_COLLECT_STARS").getBoundsInRoot().right
        assertTrue("last tier right=$tierRight must be inside card right=$cardRight", tierRight <= cardRight)

        shot("verify_018_ladder_360dp")
    }
}
