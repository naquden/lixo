package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.ReadingScoreResult
import se.lixo.app.domain.engine.StoryWordToken
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.SttEngineState
import se.lixo.app.platform.SttProviderRank
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.reading.ReadingActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 043 (doc/plans/043-reading-last-word-fallback.md): the debug "Säg fel ord" /
 * "Hoppa över ord" / "Läs hela meningen" buttons are gone, and the "Jag har läst klart" fallback
 * only appears once the child is stalled on the last story word (the recognizer missed it).
 */
class ReadingLastWordFallbackTest {

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

    private class SilentSttEngine : SpeechRecognitionEngine {
        val tokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 8)
        override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = tokens
        override val state: StateFlow<SttEngineState> = MutableStateFlow(SttEngineState.IDLE)
        override val lastLatencyMillis: StateFlow<Long> = MutableStateFlow(0L)
        override val activeProvider: SttProviderRank = SttProviderRank.SIMULATED_DETERMINISTIC

        override suspend fun requestMicrophonePermission(): Boolean = true
        override suspend fun startListening(languageIso: String, expectedWords: List<String>) {}
        override suspend fun stopListening() {}

        override suspend fun emitSimulatedWords(words: List<String>, confidence: Float) {
            tokens.emit(words.map { RecognizedSpeechToken(it, it, confidence, 0L) })
        }
    }

    private class Fixture(
        val repo: LixoRepository,
        val level: CampaignLevel,
        val task: HomeworkTask,
        val words: List<StoryWordToken>
    )

    private fun fixture(): Fixture {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_1" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val words = ForcedAlignmentEngine()
            .startSession(task.textContent ?: "", ReadingMode.AI_LISTENING, false)
            .words
        return Fixture(repo, level, task, words)
    }

    private fun setReading(f: Fixture, stt: SilentSttEngine, onScore: (ReadingScoreResult) -> Unit) {
        val repo = f.repo
        val level = f.level
        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ReadingActivityScreen(
                        level = level,
                        task = f.task,
                        alignmentEngine = remember { ForcedAlignmentEngine() },
                        sttEngine = stt,
                        ttsEngine = remember { createPlatformTextToSpeechEngine() },
                        showSwedishTtsButtons = true,
                        showDebugSimulationControls = false,
                        lookupDictionary = { repo.lookupDictionaryWord(it) },
                        onFinishReadingSession = { score, _ ->
                            onScore(score)
                            LevelCompletionOutcome(
                                updatedProfile = repo.profiles.value.first(),
                                updatedLevelProgress = LevelProgress(
                                    profileId = repo.profiles.value.first().id,
                                    levelId = level.id,
                                    levelOrder = level.order,
                                    starsEarned = score.starsEarned,
                                    completedInAiMode = true,
                                    bestTimeSeconds = 0,
                                    bestWpm = score.achievedWpm,
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
                        content = repo.content.active.value,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {}
                    )
                }
            }
        }
    }

    private fun startAiSession() {
        compose.onNodeWithText("STARTA LÄSNING").performScrollTo()
        compose.onNodeWithText("STARTA LÄSNING").performClick()
        compose.waitForIdle()
    }

    private fun emit(stt: SilentSttEngine, words: List<StoryWordToken>) {
        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(words.map { RecognizedSpeechToken(it.displayText, it.normalizedText, 0.96f, 0L) }))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun stallPastFallbackDelay() {
        compose.mainClock.advanceTimeBy(BalanceConfig.LAST_WORD_MISSED_FALLBACK_DELAY_MS + 750)
        compose.waitForIdle()
    }

    @Test
    fun fallbackHiddenAtStart_removedButtonsGone() {
        val f = fixture()
        val stt = SilentSttEngine()
        setReading(f, stt) {}
        startAiSession()
        stallPastFallbackDelay()

        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).assertDoesNotExist()
        compose.onNodeWithText("Säg fel ord").assertDoesNotExist()
        compose.onNodeWithText("Hoppa över ord").assertDoesNotExist()
        compose.onNodeWithText("Läs hela meningen").assertDoesNotExist()
        shot("043_fallback_hidden_at_start")
    }

    @Test
    fun fallbackAppearsWhenStuckOnLastWord_andFinishes() {
        val f = fixture()
        val stt = SilentSttEngine()
        var score: ReadingScoreResult? = null
        setReading(f, stt) { score = it }
        startAiSession()

        emit(stt, f.words.dropLast(1))
        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).assertDoesNotExist()

        stallPastFallbackDelay()
        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).performScrollTo().assertExists()
        shot("043_fallback_visible_on_last_word")

        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertTrue("onFinishReadingSession must be called", score != null)
        assertFalse(
            "credited last word must not be logged as struggled",
            score!!.struggledWordsToLog.contains(f.words.last().normalizedText)
        )
        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).assertDoesNotExist()
        shot("043_fallback_finished")
    }

    @Test
    fun fallbackWithYellowWords_goesToYellowFlow() {
        val f = fixture()
        val stt = SilentSttEngine()
        var score: ReadingScoreResult? = null
        setReading(f, stt) { score = it }
        startAiSession()

        val skipped = f.words.subList(1, f.words.lastIndex).first { w ->
            w.normalizedText.length > BalanceConfig.STT_SHORT_WORD_MAX_LETTERS &&
                f.words.count { it.normalizedText == w.normalizedText } == 1
        }
        emit(stt, f.words.dropLast(1).filter { it.index != skipped.index })
        stallPastFallbackDelay()

        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).performScrollTo().assertExists()
        compose.onNodeWithText(LixoStrings.READING_LAST_WORD_MISSED_BTN).assertDoesNotExist()
        assertTrue("session must not finish while yellow words remain", score == null)
        shot("043_fallback_yellow_flow")
    }
}
