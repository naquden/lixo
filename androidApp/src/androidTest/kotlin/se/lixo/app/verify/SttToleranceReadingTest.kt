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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.ReadingScoreResult
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.SttEngineState
import se.lixo.app.platform.SttProviderRank
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.onboarding.ProfileSelectAndTutorialScreen
import se.lixo.app.ui.screens.reading.ReadingActivityScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies doc/plans/036-stt-false-struggled-words.md end-to-end on the real
 * [ReadingActivityScreen] / [ProfileSelectAndTutorialScreen], using an injectable fake STT engine
 * (modeled on [HeardWordPopupTest.SilentSttEngine]) that also records what was passed as
 * expected/upcoming words.
 */
class SttToleranceReadingTest {

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

    /** Fake STT engine recording the expected/upcoming words it was given, as well as emitted tokens. */
    private class RecordingSttEngine : SpeechRecognitionEngine {
        val tokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 8)
        override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = tokens
        override val state: StateFlow<SttEngineState> = MutableStateFlow(SttEngineState.IDLE)
        override val lastLatencyMillis: StateFlow<Long> = MutableStateFlow(0L)
        override val activeProvider: SttProviderRank = SttProviderRank.SIMULATED_DETERMINISTIC

        var startedExpectedWords: List<String>? = null
        val positions = mutableListOf<List<String>>()

        override suspend fun requestMicrophonePermission(): Boolean = true

        override suspend fun startListening(languageIso: String, expectedWords: List<String>) {
            startedExpectedWords = expectedWords
        }

        override suspend fun stopListening() {}

        override fun updateExpectedPosition(nextWords: List<String>) {
            positions.add(nextWords)
        }

        override suspend fun emitSimulatedWords(words: List<String>, confidence: Float) {
            tokens.emit(
                words.map {
                    RecognizedSpeechToken(rawWord = it, normalizedWord = it, confidence = confidence, timestampMillis = 0L)
                }
            )
        }
    }

    private fun setReading(
        repo: se.lixo.app.data.repository.LixoRepository,
        level: se.lixo.app.domain.model.CampaignLevel,
        task: se.lixo.app.domain.model.HomeworkTask,
        stt: RecordingSttEngine,
        onScore: (ReadingScoreResult) -> Unit
    ) {
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

    @Test
    fun swallowedShortWords_noPracticeList() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_1" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val stt = RecordingSttEngine()
        var score: ReadingScoreResult? = null

        setReading(repo, level, task, stt) { score = it }
        startAiSession()
        compose.waitUntil(5_000) { stt.tokens.subscriptionCount.value > 0 }

        val tokens = ForcedAlignmentEngine().tokenizeStory(task.textContent!!)
            .filter { it.normalizedText.length > 2 }
            .map { RecognizedSpeechToken(it.displayText, it.normalizedText, 0.95f, 0L) }

        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(tokens))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.waitUntil(5_000) { score != null }

        assertTrue(score!!.struggledWordsToLog.isEmpty())
        compose.onNodeWithText(LixoStrings.READING_PRACTICE_WORDS_TITLE).assertDoesNotExist()

        assertTrue(stt.startedExpectedWords?.contains("leckso") == true)
        assertTrue(stt.startedExpectedWords?.contains("lixo") == true)
        assertTrue(stt.positions.isNotEmpty())
        assertTrue(stt.positions.first().firstOrNull() == "lixo")

        shot("036_no_false_practice_words")
    }

    @Test
    fun inventedNameAlias_lixoNotYellow() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_1" }
        val task = repo.tasksById.value.getValue(level.taskId)
        val stt = RecordingSttEngine()
        var score: ReadingScoreResult? = null

        setReading(repo, level, task, stt) { score = it }
        startAiSession()
        compose.waitUntil(5_000) { stt.tokens.subscriptionCount.value > 0 }

        val tokens = ForcedAlignmentEngine().tokenizeStory(task.textContent!!).map {
            if (it.normalizedText == "lixo") {
                RecognizedSpeechToken("leckso", "leckso", 0.95f, 0L)
            } else {
                RecognizedSpeechToken(it.displayText, it.normalizedText, 0.95f, 0L)
            }
        }

        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(tokens))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.waitUntil(5_000) { score != null }

        assertTrue(score!!.struggledWordsToLog.isEmpty())
        compose.onNodeWithText(LixoStrings.READING_PRACTICE_WORDS_TITLE).assertDoesNotExist()

        shot("036_alias_lixo")
    }

    @Test
    fun tutorial_aliasAndSwallowedWords_noYellow() {
        val profile = ChildProfile(id = "p_tutorial_036", displayName = "Ella", tutorialCompleted = false)
        val stt = RecordingSttEngine()

        compose.setContent {
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ProfileSelectAndTutorialScreen(
                        profiles = listOf(profile),
                        activeProfile = profile,
                        content = TestContent.active,
                        sttEngine = stt,
                        ttsEngine = remember { createPlatformTextToSpeechEngine() },
                        showSwedishTtsButtons = false,
                        onSelectProfile = {},
                        onCreateSiblingProfile = {},
                        startTutorialImmediately = true,
                        onTutorialCompleted = {}
                    )
                }
            }
        }

        compose.onNodeWithText(LixoStrings.TUTORIAL_ALLOW_MIC_BTN.uppercase()).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { stt.tokens.subscriptionCount.value > 0 }

        val tokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
            .filter { it.index != 3 && it.index != 6 }
            .map { token ->
                if (token.normalizedText == "lixo") {
                    RecognizedSpeechToken("leckso", "leckso", 0.95f, 0L)
                } else {
                    RecognizedSpeechToken(token.displayText, token.normalizedText, 0.95f, 0L)
                }
            }

        compose.runOnIdle {
            assertTrue(stt.tokens.tryEmit(tokens))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()).assertExists()
        compose.onNodeWithText(LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()).assertDoesNotExist()

        shot("036_tutorial_no_yellow")
    }
}
