package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.model.LevelProgress
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
 * Verifies feature 017 (doc/plans/017-manual-reading-15s-delay.md): in "Läsa själv" (Manual
 * Reading Mode) the done button unlocks after a fixed 15s wait, independent of story length.
 */
class ManualReadingDelayTest {

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

    /** Silent fake STT engine: no real mic/permission flow, tokens are injected via [tokens] directly. */
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
            tokens.emit(
                words.map {
                    RecognizedSpeechToken(
                        rawWord = it,
                        normalizedWord = it,
                        confidence = confidence,
                        timestampMillis = 0L
                    )
                }
            )
        }
    }

    @Test
    fun manualMode_doneButtonUnlocksAfter15Seconds() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_1" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val stt = SilentSttEngine()

        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ReadingActivityScreen(
                        level = level,
                        task = task,
                        alignmentEngine = remember { ForcedAlignmentEngine() },
                        sttEngine = stt,
                        ttsEngine = remember { createPlatformTextToSpeechEngine() },
                        showSwedishTtsButtons = true,
                        showDebugSimulationControls = true,
                        lookupDictionary = { repo.lookupDictionaryWord(it) },
                        // The done button is never clicked in this test (we only verify its
                        // locked/unlocked state), but a valid outcome is returned anyway in
                        // case it ever runs, so it doesn't crash.
                        content = repo.content.active.value,
                        onFinishReadingSession = { _, _ ->
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
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {}
                    )
                }
            }
        }

        compose.onNodeWithText("Läsa själv").performScrollTo()
        compose.onNodeWithText("Läsa själv").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("STARTA LÄSNING").performScrollTo()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("STARTA LÄSNING").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("${LixoStrings.READING_MANUAL_CHARGING_LABEL} (15s)")
            .assertExists()
            .assertIsNotEnabled()
        shot("017_manual_locked_15s")

        // 14_200 (not 14_000): the 250ms tick loop starts a frame after the click; the margin
        // lets the 56th tick (elapsed 14_000) land and recompose. Still < 15_000, so the button
        // must stay locked.
        compose.mainClock.advanceTimeBy(14_200)
        compose.onNodeWithText("${LixoStrings.READING_MANUAL_CHARGING_LABEL} (1s)")
            .assertIsNotEnabled()
        compose.onNodeWithText(LixoStrings.READING_MANUAL_DONE_BTN.uppercase()).assertDoesNotExist()
        shot("017_manual_locked_1s")

        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(LixoStrings.READING_MANUAL_DONE_BTN.uppercase())
            .assertExists()
            .assertIsEnabled()
        shot("017_manual_unlocked")
    }
}
