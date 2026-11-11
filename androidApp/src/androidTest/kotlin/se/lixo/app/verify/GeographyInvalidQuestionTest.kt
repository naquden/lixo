package se.lixo.app.verify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.GeographyCountries
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathSessionScoreResult
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.FlagQuestion
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 038: flag questions that don't map to a known country/flag are excluded when
 * building problems, so a tainted question never reaches the player, and a task that ends up
 * with zero valid questions closes back to the map instead of auto-completing.
 */
class GeographyInvalidQuestionTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AndroidPlatformContext.init(instrumentation.targetContext)
    }

    private fun stubOutcome(profile: ChildProfile, levelId: String, levelOrder: Int) = LevelCompletionOutcome(
        updatedProfile = profile,
        updatedLevelProgress = LevelProgress(
            profileId = profile.id,
            levelId = levelId,
            levelOrder = levelOrder,
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

    @Test
    fun invalidMiddleQuestion_isSkipped() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_161" }
        val seeded = repo.tasksById.value.getValue(level.taskId)
        val task = seeded.copy(
            id = "task_038_mid",
            flagQuestions = listOf(
                seeded.flagQuestions[0],
                FlagQuestion("geo_bad_mid", "jp", listOf("cn", "ca", "xx")),
                seeded.flagQuestions[1]
            )
        )

        var score: MathSessionScoreResult? = null

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
                        onFinishMathSession = { result, _ ->
                            score = result
                            stubOutcome(repo.profiles.value.first(), level.id, level.order)
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

        val q0 = seeded.flagQuestions[0]
        compose.onNodeWithText("Vilken flagga har ${GeographyCountries.byIso(q0.countryIso2).nameSv}?").assertExists()
        compose.onNodeWithText("Vilken flagga har Japan?").assertDoesNotExist()
        compose.onNodeWithTag("flag_choice_xx").assertDoesNotExist()
        (q0.distractorIso2 + q0.countryIso2).forEach { iso ->
            compose.onNodeWithTag("flag_choice_$iso").assertExists()
        }

        compose.onNodeWithTag("flag_choice_${q0.countryIso2}").performScrollTo().performClick()
        compose.onNodeWithText(LixoStrings.MATH_NEXT_PROBLEM_BTN.uppercase()).performClick()

        val q1 = seeded.flagQuestions[1]
        compose.onNodeWithText("Vilken flagga har ${GeographyCountries.byIso(q1.countryIso2).nameSv}?").assertExists()
        compose.onNodeWithText("Vilken flagga har Japan?").assertDoesNotExist()
        compose.onNodeWithTag("flag_choice_xx").assertDoesNotExist()
        compose.onNodeWithTag("flag_choice_${q1.countryIso2}").performScrollTo().performClick()

        compose.waitUntil(5000) { score != null }
        assertEquals(2, score!!.totalProblems)
    }

    @Test
    fun allInvalid_closesWithoutRecording() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_161" }
        val seeded = repo.tasksById.value.getValue(level.taskId)
        val task = seeded.copy(
            id = "task_038_all_invalid",
            flagQuestions = listOf(
                FlagQuestion("bad_a", "xx", listOf("se", "no", "dk")),
                FlagQuestion("bad_b", "se", listOf("no", "dk")),
                FlagQuestion("", "se", listOf("no", "dk", "fi"))
            )
        )

        var closed = false
        var finishCalled = false

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
                            finishCalled = true
                            stubOutcome(repo.profiles.value.first(), level.id, level.order)
                        },
                        content = content,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = { closed = true },
                        choiceSeed = 38L
                    )
                }
            }
        }

        compose.waitUntil(5000) { closed }
        assertFalse(finishCalled)
        compose.onNodeWithText("Vilken flagga har", substring = true).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(LixoStrings.FLAG_CHOICE_A11Y).assertCountEquals(0)
    }
}
