package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.screens.social.TrophiesScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 004 (achievement tracking, doc/plans/004-achievement-tracking.md), tiered into
 * ladders by doc/plans/014-achievement-ladders.md, against the real
 * [TrophiesScreen]/[ProgressionAndGiftEngine]/[CelebrationSequenceHost]: a fresh profile's
 * Trophies screen shows 0 progress for every challenge chain, and an achievement unlock shows a
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
        val definitions = LixoRepository.buildSeedAchievements(TestContent.active.dropRules.achievementRewards)
        val freshProfile = ChildProfile(id = "p", displayName = "Saga")
        // setContent may only be called once per test; swap the profile via state instead.
        val profileState = androidx.compose.runtime.mutableStateOf(freshProfile)

        compose.setContent {
            LixoTheme {
                TrophiesScreen(
                    achievements = AchievementEngine.withProfileProgress(definitions, profileState.value),
                    ttsEngine = NoOpTextToSpeechEngine
                )
            }
        }
        compose.waitForIdle()

        for (text in listOf("Läs 1 saga", "Samla 5 stjärnor")) {
            assertTrue(
                "Expected to find \"$text\" on a fresh profile's Trophies screen",
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            )
        }
        val challengeCardTag = SemanticsMatcher("tag") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("challenge_") == true
        }
        assertTrue(
            "Expected 10 challenge_* cards, one per chain",
            compose.onAllNodes(challengeCardTag).fetchSemanticsNodes().size == 10
        )
        shot("verify_014_trophies_zero")

        val partialProfile = freshProfile.copy(
            achievementProgress = mapOf("chain:FIRST_TRY" to 12),
            unlockedAchievementIds = setOf("ach_first_try_10")
        )
        compose.runOnIdle { profileState.value = partialProfile }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("12 / 30", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun achievementUnlock_showsPrestationChest() {
        val catalog = TestContent.active.items
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(TestContent.active.dropRules.levelRewards)
        val mathLevel = levels.first { it.subject == Subject.MATH }
        val achievements = LixoRepository.buildSeedAchievements(TestContent.active.dropRules.achievementRewards)
        // Pre-unlock every tier except ach_math_clock_money/ach_clock_money_40, so this run's
        // extra first-try clock/money answers unlock exactly ach_math_clock_money (13 + 2 = 15)
        // and the celebration shows its "Prestation: ..." chest, not some lower, earlier tier.
        val allOtherIds = achievements.map { it.id }.toSet() - setOf("ach_math_clock_money", "ach_clock_money_40")
        val profile = ChildProfile(
            id = "p_verify_ach",
            displayName = "Saga",
            achievementProgress = mapOf("ach_math_clock_money" to 13, "chain:CLOCK_MONEY" to 13),
            unlockedAchievementIds = allOtherIds
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
            clearedLevelsCountBeforeRun = 6,
            content = TestContent.active,
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
                    content = TestContent.active,
                    outcome = outcome,
                    starsEarned = 3,
                    onEquipItem = {},
                    onSelectAvatar = {},
                    ttsEngine = NoOpTextToSpeechEngine
                )
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Prestation: Tid- & Pengamästaren").fetchSemanticsNodes().isNotEmpty()
        }
        shot("verify_014_prestation_chest")
    }
}
