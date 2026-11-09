package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.EnglishChoices
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies bug 016 (English choices always have the correct answer first, and the "star" item
 * has only 3 options, doc/plans/016-english-choice-order.md) against the real
 * [MathActivityScreen] with a seeded English level/task and the real [EnglishChoices] helper.
 */
class EnglishChoiceOrderTest {

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

    @Test
    fun englishQuestion_hasFourOptions_correctNotAlwaysFirst() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_5" }
        val task = repo.tasksById.value.getValue(level.taskId)

        fun expected(seed: Long) = EnglishChoices.buildForTask(task.englishItems, Random(seed))

        val correctWord = task.englishItems.first().swedishTranslation
        val seed = (1L..200L).first { expected(it)[0].indexOf(correctWord) != 0 }

        compose.setContent {
            val content = repo.content.active.value
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
                        content = content,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {},
                        choiceSeed = seed
                    )
                }
            }
        }

        val choices = expected(seed)[0]
        val correctIdx = choices.indexOf(correctWord)
        assertTrue("correct answer should be in the choices", correctIdx >= 0)
        assertTrue("correct answer should not be at index 0", correctIdx != 0)

        for (i in 0..3) {
            compose.onNodeWithTag("choice_$i").assertExists().assert(hasText(choices[i]))
        }
        compose.onNodeWithTag("choice_4").assertDoesNotExist()
        compose.onNodeWithTag("choice_0").assert(!hasText(correctWord))

        compose.onNodeWithTag("choice_$correctIdx").performScrollTo().performClick()
        compose.onNodeWithText("Snyggt jobbat!").assertExists()

        shot("016_english_choice_order")
    }

    @Test
    fun starQuestion_hasFourOptions() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_5" }
        val task = repo.tasksById.value.getValue(level.taskId)

        val starIdx = task.englishItems.indexOfFirst { it.englishWord == "star" }
        assertTrue("task should contain a 'star' item", starIdx >= 0)

        val seed = 7L
        fun expected() = EnglishChoices.buildForTask(task.englishItems, Random(seed))

        compose.setContent {
            val content = repo.content.active.value
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
                        content = content,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {},
                        choiceSeed = seed
                    )
                }
            }
        }

        val choiceLists = expected()

        // Walk through every question before the star item, solving it and tapping "next".
        for (qIdx in 0 until starIdx) {
            val correctWord = task.englishItems[qIdx].swedishTranslation
            val correctChoiceIdx = choiceLists[qIdx].indexOf(correctWord)
            compose.onNodeWithTag("choice_$correctChoiceIdx").performScrollTo().performClick()
            compose.onNodeWithText(LixoStrings.MATH_NEXT_PROBLEM_BTN.uppercase()).performClick()
        }

        val starChoices = choiceLists[starIdx]
        for (i in 0..3) {
            compose.onNodeWithTag("choice_$i").assertExists().assert(hasText(starChoices[i]))
        }
        compose.onNodeWithTag("choice_4").assertDoesNotExist()
        assertEquals(1, compose.onAllNodesWithText("stjärna").fetchSemanticsNodes().size)

        shot("016_star_four_options")
    }
}
