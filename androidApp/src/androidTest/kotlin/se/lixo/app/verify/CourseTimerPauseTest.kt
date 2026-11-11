package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.EnglishChoices
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MATH_CHOICE_TAG_PREFIX
import se.lixo.app.ui.screens.math.MATH_TIMER_TAG
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 039 (doc/plans/039-pause-timer-while-awaiting-next.md): the course timer pauses
 * while the "Snyggt jobbat!" sheet is waiting for NÄSTA UPPGIFT, keeps running on a wrong answer,
 * and resumes from where it stopped once the next question is shown. Runs the same scenario on
 * math, English and geography since they all share [MathActivityScreen].
 */
class CourseTimerPauseTest {

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
        outDir = File(baseDir, "lixo_screenshots/verify039").apply { mkdirs() }
    }

    private fun shot(name: String) {
        // autoAdvance is off during the scenario; waitForIdle() would block forever, so just
        // capture the current frame directly.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun runPauseScenario(
        prefix: String,
        level: CampaignLevel,
        task: HomeworkTask,
        choiceSeed: Long?,
        clickCorrect: (Int) -> Unit,
        clickWrong: (Int) -> Unit
    ) {
        val repo = DummyData.populatedRepository()

        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    MathActivityScreen(
                        level = level,
                        task = task,
                        mathEngine = remember { MathEngine() },
                        ttsEngine = createPlatformTextToSpeechEngine(),
                        showSwedishTtsButtons = false,
                        onLogMathError = { _, _, _, _ -> },
                        onFinishMathSession = { _, _ -> error("Not used in verify runs") },
                        content = repo.content.active.value,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {},
                        choiceSeed = choiceSeed
                    )
                }
            }
        }

        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag(MATH_TIMER_TAG).assertTextEquals("0s")

        compose.mainClock.advanceTimeBy(3_500)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(MATH_TIMER_TAG).assertTextEquals("3s")

        clickCorrect(0)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Snyggt jobbat!").assertExists()

        compose.mainClock.advanceTimeBy(10_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(MATH_TIMER_TAG).assertTextEquals("3s")
        shot("${prefix}_paused")

        compose.onNodeWithText(LixoStrings.MATH_NEXT_PROBLEM_BTN.uppercase()).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(MATH_TIMER_TAG).assertTextEquals("5s")

        clickWrong(1)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Inte riktigt — prova igen!").assertExists()

        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(MATH_TIMER_TAG).assertTextEquals("7s")
        shot("${prefix}_wrong_running")

        compose.mainClock.autoAdvance = true
    }

    @Test
    fun math_timerPausesWhileAwaitingNext() {
        val repo = DummyData.populatedRepository()
        val tasks = repo.tasksById.value
        val level = repo.levels.value.sortedBy { it.order }.first {
            tasks[it.taskId]?.let { t -> t.type == TaskType.MATH && t.mathProblems.isNotEmpty() } == true
        }
        val task = tasks.getValue(level.taskId)
        assertTrue(task.mathProblems.size >= 2)

        runPauseScenario(
            prefix = "math",
            level = level,
            task = task,
            choiceSeed = null,
            clickCorrect = { i ->
                val problem = task.mathProblems[i]
                val idx = problem.choices.indexOf(problem.correctAnswer)
                compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + idx).performScrollTo().performClick()
            },
            clickWrong = { i ->
                val problem = task.mathProblems[i]
                val idx = problem.choices.indexOfFirst { it != problem.correctAnswer }
                compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + idx).performScrollTo().performClick()
            }
        )
    }

    @Test
    fun english_timerPausesWhileAwaitingNext() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_5" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val seed = 7L
        val lists = EnglishChoices.buildForTask(task.englishItems, Random(seed))

        runPauseScenario(
            prefix = "english",
            level = level,
            task = task,
            choiceSeed = seed,
            clickCorrect = { i ->
                val idx = lists[i].indexOf(task.englishItems[i].swedishTranslation)
                compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + idx).performScrollTo().performClick()
            },
            clickWrong = { i ->
                val correctWord = task.englishItems[i].swedishTranslation
                val idx = lists[i].indexOfFirst { it != correctWord }
                compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + idx).performScrollTo().performClick()
            }
        )
    }

    @Test
    fun geography_timerPausesWhileAwaitingNext() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_161" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val seed = 38L

        runPauseScenario(
            prefix = "geography",
            level = level,
            task = task,
            choiceSeed = seed,
            clickCorrect = { i ->
                val iso = task.flagQuestions[i].countryIso2
                compose.onNodeWithTag("flag_choice_$iso").performScrollTo().performClick()
            },
            clickWrong = { i ->
                val iso = task.flagQuestions[i].distractorIso2[0]
                compose.onNodeWithTag("flag_choice_$iso").performScrollTo().performClick()
            }
        )
    }
}
