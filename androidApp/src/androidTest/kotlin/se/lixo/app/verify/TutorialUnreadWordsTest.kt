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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.SttEngineState
import se.lixo.app.platform.SttProviderRank
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.ui.screens.onboarding.ProfileSelectAndTutorialScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 007 (tutorial: skipped words block completion, doc/plans/007-tutorial-stuck-unread-words.md)
 * against the real [ProfileSelectAndTutorialScreen] with a fake STT engine that lets us inject
 * tokens deterministically instead of relying on the real microphone/permission flow.
 */
class TutorialUnreadWordsTest {

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

    /** Fake STT engine: no real mic/permission flow, tokens are injected via [tokens] directly. */
    private class FakeSttEngine : SpeechRecognitionEngine {
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

    private fun setTutorial(stt: FakeSttEngine, onDone: (ChildProfile) -> Unit) {
        val profile = ChildProfile(id = "p_tutorial", displayName = "Ella", tutorialCompleted = false)
        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ProfileSelectAndTutorialScreen(
                        profiles = listOf(profile),
                        activeProfile = profile,
                        catalogById = emptyMap(),
                        sttEngine = stt,
                        ttsEngine = remember { createPlatformTextToSpeechEngine() },
                        showSwedishTtsButtons = false,
                        onSelectProfile = {},
                        onCreateSiblingProfile = {},
                        startTutorialImmediately = true,
                        onTutorialCompleted = onDone
                    )
                }
            }
        }
    }

    private fun startAiTutorialAndSkipWords(stt: FakeSttEngine) {
        compose.onNodeWithText(LixoStrings.TUTORIAL_ALLOW_MIC_BTN.uppercase()).performClick()
        compose.waitForIdle()

        compose.waitUntil(5_000) { stt.tokens.subscriptionCount.value > 0 }

        val tokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
            .filter { it.index != 1 && it.index != 8 }
            .map { RecognizedSpeechToken(it.displayText, it.normalizedText, 0.95f, 0L) }

        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(tokens))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun skippedWords_showGaVidare_thenCompletionCard() {
        var completed: ChildProfile? = null
        val stt = FakeSttEngine()
        setTutorial(stt) { completed = it }
        startAiTutorialAndSkipWords(stt)

        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).assertExists()
        compose.onNodeWithText(LixoStrings.READING_READ_YELLOW_WORDS).assertExists()
        compose.onNodeWithText(LixoStrings.READING_SIMULATE_VOICE_BTN.uppercase()).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).assertDoesNotExist()

        shot("007_01_tutorial_awaiting_yellow")

        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).performScrollTo()
        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.TUTORIAL_REWARD_BANNER).assertExists()
        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).assertExists()

        shot("007_02_tutorial_completed")

        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).performScrollTo()
        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).performClick()
        compose.waitForIdle()

        assertEquals("p_tutorial", completed?.id)
    }

    @Test
    fun readingYellowWordsAfterEnd_completesWithoutGaVidare() {
        val stt = FakeSttEngine()
        setTutorial(stt) {}
        startAiTutorialAndSkipWords(stt)

        compose.runOnIdle {
            assertTrue(
                stt.tokens.tryEmit(
                    listOf(
                        RecognizedSpeechToken("Lixo", "lixo", 0.95f, 0L),
                        RecognizedSpeechToken("Hatten", "hatten", 0.95f, 0L)
                    )
                )
            )
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).assertExists()

        shot("007_03_tutorial_yellow_read")
    }
}
