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
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
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
 * Verifies feature 005 (Gula ord: visa vad AI hörde, doc/plans/005-heard-word-compare-popup.md)
 * against the real [ReadingActivityScreen] with a silent fake STT engine that lets us inject
 * tokens deterministically instead of relying on the real microphone/permission flow.
 */
class HeardWordPopupTest {

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

    /** "lxq" is heard for "Lixo" (word 0), then "är" (word 1) skips past it, so "Lixo" turns yellow. */
    private fun mishearFirstWordThenSkip(stt: SilentSttEngine) {
        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(listOf(RecognizedSpeechToken("lxq", "lxq", 0.96f, 0L))))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(listOf(RecognizedSpeechToken("är", "är", 0.96f, 0L))))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun startAiSession() {
        compose.onNodeWithText("STARTA LÄSNING").performScrollTo()
        compose.onNodeWithText("STARTA LÄSNING").performClick()
        compose.waitForIdle()
    }

    @Test
    fun skippedWord_showsHeardTextInPopup() {
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
                        onFinishReadingSession = { _, _ -> error("not expected") },
                        content = repo.content.active.value,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {}
                    )
                }
            }
        }

        startAiSession()

        mishearFirstWordThenSkip(stt)

        compose.onAllNodesWithText("Lixo")[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_HEARD_WORD_LABEL).assertExists()
        compose.onNodeWithText(LixoStrings.READING_HEARD_AI_LABEL).assertExists()
        compose.onNodeWithText("lxq").assertExists()
        compose.onNodeWithText(LixoStrings.READING_HEARD_TRY_AGAIN_HINT).assertExists()

        shot("005_heard_word_popup")
    }

    @Test
    fun hesitationWithoutSpeech_showsHeardNothing() {
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
                        onFinishReadingSession = { _, _ -> error("not expected") },
                        content = repo.content.active.value,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {}
                    )
                }
            }
        }

        startAiSession()

        compose.mainClock.advanceTimeBy(BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS + 500)
        compose.waitForIdle()

        val syllables = repo.lookupDictionaryWord("Lixo").syllables
        compose.onAllNodesWithText(syllables)[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_HEARD_NOTHING).assertExists()

        shot("005_heard_nothing")
    }

    @Test
    fun readingYellowWordWhilePopupOpen_closesPopup() {
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
                        onFinishReadingSession = { _, _ -> error("not expected") },
                        content = repo.content.active.value,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {}
                    )
                }
            }
        }

        startAiSession()

        mishearFirstWordThenSkip(stt)

        compose.onAllNodesWithText("Lixo")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.READING_HEARD_AI_LABEL).assertExists()

        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(listOf(RecognizedSpeechToken("Lixo", "lixo", 0.95f, 0L))))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_HEARD_AI_LABEL).assertDoesNotExist()

        compose.onAllNodesWithText("Lixo")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.READING_HEARD_AI_FIRST_LABEL).assertExists()
        compose.onNodeWithText(LixoStrings.READING_HEARD_TRY_AGAIN_HINT).assertDoesNotExist()

        shot("005_heard_first")
    }

    @Test
    fun manualMode_noCompareBlock() {
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
                        onFinishReadingSession = { _, _ -> error("not expected") },
                        content = repo.content.active.value,
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

        startAiSession()

        compose.onAllNodesWithText("Lixo")[0].performClick()
        compose.waitForIdle()

        val entry = repo.lookupDictionaryWord("lixo")
        compose.onNodeWithText("${LixoStrings.READING_STUCK_TITLE} \"${entry.displayWord}\"").assertExists()
        compose.onNodeWithText(LixoStrings.READING_HEARD_WORD_LABEL).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.READING_HEARD_AI_LABEL).assertDoesNotExist()

        shot("005_manual_no_compare")
    }
}
