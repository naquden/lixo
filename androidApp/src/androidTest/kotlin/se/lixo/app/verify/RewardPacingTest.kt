package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 001 (reward pacing, doc/plans/001-reward-pacing-early-chests.md) against the
 * real [ProgressionAndGiftEngine]/[CelebrationSequenceHost]: a fresh profile's first clear shows
 * exactly one chest labeled "Banbelöning", and a run that also fills the Flitkista shows a second,
 * distinctly labeled chest.
 */
class RewardPacingTest {

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

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun firstClear_showsExactlyOneChestLabeledBanbeloning() {
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(catalog)
        val mathLevel = levels.first { it.subject == Subject.MATH }
        val profile = ChildProfile(id = "p_verify_level", displayName = "Saga", totalStars = 0)
        val today = DailyActivityRecord(profileId = "p_verify_level", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine(random = Random(42)).recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 0,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(listOf(RewardSource.LEVEL), outcome.newlyUnlockedRewards.map { it.source })

        compose.setContent {
            LixoTheme {
                CelebrationSequenceHost(
                    outcome = outcome,
                    starsEarned = 3,
                    onEquipItem = {},
                    onSelectAvatar = {}
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(LixoStrings.rewardSourceLabel(RewardSource.LEVEL)).fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_001_level_chest")

        assertTrue(compose.onAllNodesWithText("Stjärnbelöning").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Flitkistan").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun stackedRun_levelThenFlitkista_distinctLabels() {
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(catalog)
        val mathLevel = levels.first { it.subject == Subject.MATH }
        val profile = ChildProfile(
            id = "p_verify_stack",
            displayName = "Saga",
            totalStars = 0,
            chestProgressHalfPoints = 18
        )
        val today = DailyActivityRecord(profileId = "p_verify_stack", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine(random = Random(42)).recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 0,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(listOf(RewardSource.LEVEL, RewardSource.FLITKISTA), outcome.newlyUnlockedRewards.map { it.source })

        compose.setContent {
            LixoTheme {
                CelebrationSequenceHost(
                    outcome = outcome,
                    starsEarned = 3,
                    onEquipItem = {},
                    onSelectAvatar = {}
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Banbelöning").fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_001_banbeloning_chest")

        // Advance past the LEVEL chest, then its equip prompt.
        pressBack()
        compose.waitForIdle()
        pressBack()
        compose.waitForIdle()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Flitkistan", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_001_flitkista_chest")

        assertTrue(compose.onAllNodesWithText("Banbelöning").fetchSemanticsNodes().isEmpty())
    }
}
