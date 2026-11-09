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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 002 (level reward item ids, doc/plans/002-level-reward-item-ids.md): a task
 * with a stale, synced-in `rewardItemId` ends up with the app's own curated id after sync, and
 * clearing that level shows exactly one chest labeled "Banbelöning" for the curated item.
 */
class LevelRewardItemTest {

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
    fun syncedStaleRemoteTask_firstClearShowsCuratedLevelChest() {
        val content = TestContent.active
        val (seedLevels, seedTasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(content.dropRules.levelRewards)
        val seedLevel2 = seedLevels.first { it.id == "level_2" }
        val seedTask2 = seedTasks.getValue("task_2")

        val gw = object : CloudSyncGateway by FakeCloudGateway() {
            override suspend fun fetchRemoteCourses(): List<Course> = listOf(
                Course(
                    id = "course_math_1",
                    title = "Matteäventyret: Plus, Minus & Tiokompisar",
                    subject = Subject.MATH,
                    minGrade = 1,
                    version = 2,
                    updatedAt = 1793558400000L
                )
            )

            override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> =
                if (courseId == "course_math_1") listOf(seedLevel2) else emptyList()

            override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> =
                listOf(seedTask2.copy(rewardItemId = "item_hat_2"))
        }

        val repo = LixoRepository(gw, lexiconCache = null, profileStore = null, content = TestContent.repository)
        runBlocking { repo.syncCurriculumFromCloud() }

        val task = repo.tasksById.value.getValue("task_2")
        assertNotEquals("item_hat_2", task.rewardItemId)
        assertEquals(content.levelRewardItemId(2), task.rewardItemId)
        assertTrue(task.rewardItemId != null && content.itemsById.containsKey(task.rewardItemId))

        val level2 = repo.levels.value.first { it.id == "level_2" }
        val profile = ChildProfile(id = "p_verify_002", displayName = "Saga", totalStars = 0)
        val today = DailyActivityRecord(profileId = "p_verify_002", calendarDateIso = "2026-11-01")

        val outcome = ProgressionAndGiftEngine(random = Random(42)).recordLevelCompletion(
            profile = profile,
            level = level2,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(listOf(RewardSource.LEVEL), outcome.newlyUnlockedRewards.map { it.source })
        assertEquals(task.rewardItemId, outcome.newlyUnlockedRewards.single().item.id)

        compose.setContent {
            LixoTheme {
                CelebrationSequenceHost(
                    content = content,
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
        shot("verify_002_curated_level_chest")
    }
}
