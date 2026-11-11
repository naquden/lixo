package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.GeographyCountries
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 037's geography flag challenge against the real [MathActivityScreen]/
 * [se.lixo.app.ui.theme.FlagChoiceCard], using the real level_161 task from the seeded repository.
 */
class GeographyFlagChallengeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outDir: File
    private lateinit var outDir038: File

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
        outDir038 = File(baseDir, "lixo_screenshots/verify038").apply { mkdirs() }
    }

    private fun shot(name: String, dir: File = outDir) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun flagQuestion_showsPromptAndFourFlags_wrongThenCorrect() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_161" }
        val task = repo.tasksById.value.getValue(level.taskId)

        assertEquals(TaskType.GEOGRAPHY_FLAGS, task.type)
        assertEquals(8, task.flagQuestions.size)

        val q0 = task.flagQuestions[0]
        val name = GeographyCountries.byIso(q0.countryIso2).nameSv

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
                        choiceSeed = 37L
                    )
                }
            }
        }

        compose.onNodeWithText("Vilken flagga har $name?").assertExists()

        val isoChoices = q0.distractorIso2 + q0.countryIso2
        isoChoices.forEach { iso ->
            compose.onNodeWithTag("flag_choice_$iso").assertExists()
        }
        compose.onNodeWithTag("choice_0").assertDoesNotExist()

        val wrongIso = q0.distractorIso2[0]
        compose.onNodeWithTag("flag_choice_$wrongIso").performScrollTo().performClick()
        compose.onNodeWithText("Inte riktigt — prova igen!").assertExists()
        compose.onNodeWithText("Snyggt jobbat!").assertDoesNotExist()
        compose.onNodeWithText("$name ligger i", substring = true).assertExists()
        shot("037_flag_wrong")

        compose.onNodeWithTag("flag_choice_${q0.countryIso2}").performScrollTo().performClick()
        compose.onNodeWithText("Snyggt jobbat!").assertExists()
        shot("037_flag_challenge")

        compose.onNodeWithText(LixoStrings.MATH_NEXT_PROBLEM_BTN.uppercase()).performClick()

        val q1 = task.flagQuestions[1]
        val name1 = GeographyCountries.byIso(q1.countryIso2).nameSv
        compose.onNodeWithText("Vilken flagga har $name1?").assertExists()
    }

    @Test
    fun allEightQuestions_answeredCorrectly_everyOptionIsAFlag() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_161" }
        val task = repo.tasksById.value.getValue(level.taskId)
        assertEquals(8, task.flagQuestions.size)

        var finished = false

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
                        onFinishMathSession = { _, _ ->
                            finished = true
                            LevelCompletionOutcome(
                                updatedProfile = repo.profiles.value.first(),
                                updatedLevelProgress = LevelProgress(
                                    profileId = repo.profiles.value.first().id,
                                    levelId = level.id,
                                    levelOrder = level.order,
                                    starsEarned = 1,
                                    completedInAiMode = false,
                                    bestTimeSeconds = 0,
                                    bestWpm = 0,
                                    completedTimedChallenge = false,
                                    timesCompleted = 1,
                                    updatedAtEpochMillis = 0L
                                ),
                                netNewStarsAwarded = 0,
                                newlyUnlockedAvatarIds = emptyList(),
                                newlyUnlockedRewards = emptyList(),
                                giftOutcome = null,
                                streakIncrementedToday = false
                            )
                        },
                        content = content,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {},
                        choiceSeed = 38L
                    )
                }
            }
        }

        for (n in 0..7) {
            val q = task.flagQuestions[n]
            val name = GeographyCountries.byIso(q.countryIso2).nameSv
            compose.onNodeWithText("Vilken flagga har $name?").assertExists()

            val isoChoices = q.distractorIso2 + q.countryIso2
            isoChoices.forEach { iso ->
                compose.onNodeWithTag("flag_choice_$iso").assertExists()
            }
            compose.onAllNodesWithContentDescription(LixoStrings.FLAG_CHOICE_A11Y).assertCountEquals(4)
            shot("flag_q${n + 1}", outDir038)

            compose.onNodeWithTag("flag_choice_${q.countryIso2}").performScrollTo().performClick()
            if (n < 7) {
                compose.onNodeWithText("Snyggt jobbat!").assertExists()
                compose.onNodeWithText(LixoStrings.MATH_NEXT_PROBLEM_BTN.uppercase()).performClick()
            } else {
                compose.waitUntil(5000) { finished }
            }
        }
    }
}
