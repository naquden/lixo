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
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.screens.social.TrophiesScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 004 (achievement tracking, doc/plans/004-achievement-tracking.md) against the
 * real [TrophiesScreen]/[ProgressionAndGiftEngine]/[CelebrationSequenceHost]: a fresh profile's
 * Trophies screen shows 0 progress for every achievement, and an achievement unlock shows a
 * "Prestation: ..." chest.
 */
class AchievementTrackingTest {

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

    @Test
    fun trophies_freshProfile_showsZeroProgress() {
        val definitions = LixoRepository.buildSeedAchievements()
        val freshProfile = ChildProfile(id = "p", displayName = "Saga")
        // setContent may only be called once per test; swap the profile via state instead.
        val profileState = androidx.compose.runtime.mutableStateOf(freshProfile)

        compose.setContent {
            LixoTheme {
                TrophiesScreen(achievements = AchievementEngine.withProfileProgress(definitions, profileState.value))
            }
        }
        compose.waitForIdle()

        for (text in listOf("Blixtläsaren", "Stjärnfrö", "0/5", "0/15", "0/20", "0/480")) {
            assertTrue(
                "Expected to find \"$text\" on a fresh profile's Trophies screen",
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            )
        }
        shot("verify_004_trophies_zero")

        val partialProfile = freshProfile.copy(achievementProgress = mapOf("ach_math_clock_money" to 6))
        compose.runOnIdle { profileState.value = partialProfile }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("6/15", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun achievementUnlock_showsPrestationChest() {
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(catalog)
        val mathLevel = levels.first { it.subject == Subject.MATH }
        val achievements = LixoRepository.buildSeedAchievements()
        val profile = ChildProfile(
            id = "p_verify_ach",
            displayName = "Saga",
            achievementProgress = mapOf("ach_math_clock_money" to 13)
        )
        val today = DailyActivityRecord(profileId = "p_verify_ach", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine().recordLevelCompletion(
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
            clearedLevelsCountBeforeRun = 6,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(
                subject = Subject.MATH,
                levelId = mathLevel.id,
                isAiMode = true,
                mathFirstTryByType = mapOf(MathProblemType.CLOCK to 2)
            )
        )

        assertTrue(outcome.newlyUnlockedRewards.map { it.source } == listOf(RewardSource.ACHIEVEMENT))

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
            compose.onAllNodesWithText("Prestation: Tid- & Pengamästaren").fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_004_prestation_chest")
    }
}
