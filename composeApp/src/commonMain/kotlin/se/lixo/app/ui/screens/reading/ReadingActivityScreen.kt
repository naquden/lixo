package se.lixo.app.ui.screens.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.ReadingScoreResult
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.ParticleProgressBar
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Fullscreen Reading Activity View (`project.md` §2 & §4):
 * - Mode chosen at start (AI-Läsassistent vs. Manuellt läsläge); switching mid-session is locked.
 * -Timed challenge toggle (only active in AI mode; timer starts immediately when story appears).
 * - Real-time word highlighting via ForcedAlignmentEngine.
 * - Gentle support when stuck or when any word is tapped: Swedish TTS, syllable breakdown (`rid-dar-vagn`),
 *   offline dictionary card, and zero-penalty "Gå vidare" (logged locally for Parent View).
 * - Manual mode enforces minimum reading duration with visible button charge and 1-star cap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingActivityScreen(
    level: CampaignLevel,
    task: HomeworkTask,
    alignmentEngine: ForcedAlignmentEngine,
    sttEngine: SpeechRecognitionEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    lookupDictionary: (String) -> DictionaryEntry,
    onFinishReadingSession: (ReadingScoreResult, Int) -> LevelCompletionOutcome,
    onCloseToMap: () -> Unit
) {
    val storyText = task.textContent ?: ""
    var modeLocked by remember { mutableStateOf(false) }
    var chosenMode by remember { mutableStateOf(ReadingMode.AI_LISTENING) }
    var timedChallengeChecked by remember { mutableStateOf(false) }

    var sessionState by remember {
        mutableStateOf(
            alignmentEngine.startSession(
                storyText = storyText,
                mode = ReadingMode.AI_LISTENING,
                isTimedChallengeRequested = false
            )
        )
    }

    var millisSinceLastWordAdvance by remember { mutableLongStateOf(0L) }
    var inspectedDictionaryEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showSyllablesInline by remember { mutableStateOf(false) }
    var finalOutcome by remember { mutableStateOf<Pair<ReadingScoreResult, LevelCompletionOutcome>?>(null) }

    val scope = rememberCoroutineScope()

    // Always release the microphone when leaving this screen (back button, X, or completion).
    DisposableEffect(Unit) {
        onDispose { sttEngine.cancelListening() }
    }

    // Collect real-time STT tokens when in AI mode
    LaunchedEffect(modeLocked, chosenMode) {
        if (modeLocked && chosenMode == ReadingMode.AI_LISTENING) {
            launch {
                sttEngine.recognizedTokens.collect { tokens ->
                    val prevIndex = sessionState.currentWordIndex
                    sessionState = alignmentEngine.processRecognizedTokens(sessionState, tokens)
                    if (sessionState.currentWordIndex > prevIndex) {
                        millisSinceLastWordAdvance = 0L
                    }
                }
            }
            sttEngine.startListening(
                languageIso = if (level.subject == se.lixo.app.domain.model.Subject.ENGLISH) "en-US" else "sv-SE",
                expectedWords = sessionState.words.map { it.normalizedText }
            )
        }
    }

    // Timer starts as soon as the story is shown on screen (project.md §2 "Tiden börjar när sagan visas på skärmen")
    LaunchedEffect(modeLocked, sessionState.isCompleted) {
        if (modeLocked && !sessionState.isCompleted) {
            while (!sessionState.isCompleted) {
                delay(250L)
                millisSinceLastWordAdvance += 250L
                sessionState = alignmentEngine.onTick(
                    state = sessionState,
                    deltaMillis = 250L,
                    millisSinceLastWordAdvance = millisSinceLastWordAdvance
                )
            }
        }
        if (modeLocked && sessionState.isCompleted && finalOutcome == null) {
            sttEngine.stopListening()
            val score = alignmentEngine.evaluateSessionScore(
                state = sessionState,
                referenceWpm = level.referenceWpm,
                timeRequirementSeconds = level.timeRequirementSeconds
            )
            val elapsedSec = (sessionState.elapsedMillis / 1000L).toInt().coerceAtLeast(1)
            val completion = onFinishReadingSession(score, elapsedSec)
            finalOutcome = score to completion
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.SkyBackgroundBottom)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        // Top Header with Close (X) button & Particle Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LixoColors.CardSurfaceElevated)
                    .clickable {
                        scope.launch { sttEngine.stopListening() }
                        onCloseToMap()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = LixoColors.SoftTextWhite)
            }
            Spacer(Modifier.width(12.dp))
            ParticleProgressBar(
                progress = sessionState.progressFraction,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "⏱️ ${(sessionState.elapsedMillis / 1000L)}s",
                style = MaterialTheme.typography.titleMedium,
                color = LixoColors.StarGold
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = level.title,
            style = MaterialTheme.typography.headlineMedium,
            color = LixoColors.SoftTextWhite
        )

        Spacer(Modifier.height(12.dp))

        if (!modeLocked) {
            // Mode Selection before reading starts ("Väljs vid start; byte mitt i tillåts inte" - project.md §2)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = LixoStrings.READING_MODE_SELECT_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { chosenMode = ReadingMode.AI_LISTENING }
                ) {
                    Checkbox(
                        checked = chosenMode == ReadingMode.AI_LISTENING,
                        onCheckedChange = { if (it) chosenMode = ReadingMode.AI_LISTENING }
                    )
                    Text(LixoStrings.READING_MODE_AI_LABEL, color = LixoColors.SoftTextWhite)
                }

                if (chosenMode == ReadingMode.AI_LISTENING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 24.dp)
                    ) {
                        Checkbox(
                            checked = timedChallengeChecked,
                            onCheckedChange = { timedChallengeChecked = it }
                        )
                        Text(
                            text = "⚡ ${LixoStrings.READING_TIMED_CHALLENGE_TOGGLE} (${level.timeRequirementSeconds}s)",
                            color = LixoColors.AccentOrange
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        chosenMode = ReadingMode.MANUAL
                        timedChallengeChecked = false
                    }
                ) {
                    Checkbox(
                        checked = chosenMode == ReadingMode.MANUAL,
                        onCheckedChange = {
                            if (it) {
                                chosenMode = ReadingMode.MANUAL
                                timedChallengeChecked = false
                            }
                        }
                    )
                    Text(LixoStrings.READING_MODE_MANUAL_LABEL, color = LixoColors.SoftTextWhite)
                }

                Spacer(Modifier.height(14.dp))

                BouncyButton(
                    onClick = {
                        sessionState = alignmentEngine.startSession(
                            storyText = storyText,
                            mode = chosenMode,
                            isTimedChallengeRequested = timedChallengeChecked
                        )
                        modeLocked = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(LixoStrings.MAP_PLAY_LEVEL_BTN, color = Color.White)
                }
            }
        } else {
            // Mode is now locked for this reading session
            Text(
                text = "🔒 ${LixoStrings.READING_MODE_LOCKED_BADGE}: " +
                    if (sessionState.mode == ReadingMode.AI_LISTENING) LixoStrings.READING_MODE_AI_LABEL
                    else LixoStrings.READING_MODE_MANUAL_LABEL,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.MutedText
            )

            Spacer(Modifier.height(10.dp))

            // Story Card with Real-Time Word Highlighting & Tap-for-Dictionary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(LixoColors.CardSurface)
                    .border(2.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sessionState.words.forEachIndexed { idx, token ->
                        val isCurrent = idx == sessionState.currentWordIndex &&
                            sessionState.mode == ReadingMode.AI_LISTENING &&
                            !sessionState.isCompleted

                        val dict = lookupDictionary(token.normalizedText)
                        val renderedText = if (showSyllablesInline && isCurrent) {
                            dict.syllables
                        } else {
                            token.displayText
                        }

                        val bg = when {
                            token.isCompleted -> LixoColors.PrimaryActionGreen.copy(alpha = 0.28f)
                            isCurrent -> LixoColors.StarGold
                            else -> Color.Transparent
                        }
                        val fg = if (isCurrent) Color(0xFF0F172A) else LixoColors.SoftTextWhite

                        Text(
                            text = renderedText,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                            color = fg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(bg)
                                .clickable {
                                    inspectedDictionaryEntry = dict
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = LixoStrings.READING_TAP_WORD_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.MutedText
            )

            // Gentle Support Drawer when stuck or when a word is tapped
            val currentWordEntry = sessionState.currentWord?.let { lookupDictionary(it.normalizedText) }
            val activeSupportEntry = inspectedDictionaryEntry ?: if (sessionState.isWordStuckSupportVisible) currentWordEntry else null

            if (activeSupportEntry != null && !sessionState.isCompleted) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.CardSurfaceElevated)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${LixoStrings.READING_STUCK_TITLE} \"${activeSupportEntry.displayWord}\"",
                                style = MaterialTheme.typography.titleMedium,
                                color = LixoColors.StarGold
                            )
                            Text(
                                text = "${LixoStrings.READING_HELP_SYLLABLES}: ${activeSupportEntry.syllables}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = LixoColors.SoftTextWhite
                            )
                            Text(
                                text = "${LixoStrings.READING_HELP_EXPLANATION}: ${activeSupportEntry.explanationSv}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.SoftTextWhite
                            )
                        }
                        if (showSwedishTtsButtons) {
                            TtsIconButton(
                                textToSpeak = "${activeSupportEntry.displayWord}. ${activeSupportEntry.explanationSv}",
                                ttsEngine = ttsEngine
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BouncyButton(
                            onClick = { showSyllablesInline = !showSyllablesInline },
                            containerColor = LixoColors.SecondaryBlue,
                            lipColor = LixoColors.SecondaryBlueLip
                        ) {
                            Text(LixoStrings.READING_HELP_SYLLABLES, color = Color.White)
                        }
                        if (sessionState.mode == ReadingMode.AI_LISTENING) {
                            BouncyButton(
                                onClick = {
                                    sessionState = alignmentEngine.advanceCurrentWordWithGentleSupport(sessionState)
                                    millisSinceLastWordAdvance = 0L
                                    inspectedDictionaryEntry = null
                                }
                            ) {
                                Text(LixoStrings.READING_HELP_SKIP_NO_PENALTY, color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Controls while reading is in progress
            if (!sessionState.isCompleted) {
                if (sessionState.mode == ReadingMode.AI_LISTENING) {
                    BouncyButton(
                        onClick = {
                            val word = sessionState.currentWord?.normalizedText ?: return@BouncyButton
                            sessionState = alignmentEngine.processRecognizedTokens(
                                state = sessionState,
                                spokenTokens = listOf(
                                    RecognizedSpeechToken(
                                        rawWord = word,
                                        normalizedWord = word,
                                        confidence = 0.96f,
                                        timestampMillis = sessionState.elapsedMillis
                                    )
                                )
                            )
                            millisSinceLastWordAdvance = 0L
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🎙️ ${LixoStrings.READING_SIMULATE_VOICE_BTN}", color = Color.White)
                    }

                    Spacer(Modifier.height(10.dp))

                    BouncyButton(
                        onClick = {
                            val remainingTokens = sessionState.words
                                .drop(sessionState.currentWordIndex)
                                .map {
                                    RecognizedSpeechToken(
                                        rawWord = it.displayText,
                                        normalizedWord = it.normalizedText,
                                        confidence = 0.95f,
                                        timestampMillis = sessionState.elapsedMillis
                                    )
                                }
                            sessionState = alignmentEngine.processRecognizedTokens(sessionState, remainingTokens)
                        },
                        containerColor = LixoColors.SecondaryBlue,
                        lipColor = LixoColors.SecondaryBlueLip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ ${LixoStrings.READING_SIMULATE_FULL_SENTENCE_BTN}", color = Color.White)
                    }
                } else {
                    // Manual Reading Mode button with visible charge bar
                    BouncyButton(
                        onClick = {
                            sessionState = alignmentEngine.tryCompleteManualReading(sessionState)
                        },
                        enabled = sessionState.isManualCompleteButtonEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = if (sessionState.isManualCompleteButtonEnabled) {
                            "✅ ${LixoStrings.READING_MANUAL_DONE_BTN}"
                        } else {
                            "⏳ ${LixoStrings.READING_MANUAL_CHARGING_LABEL} (${sessionState.manualModeRemainingSeconds} s)"
                        }
                        Text(label, color = Color.White)
                    }
                }
            }

            // Completion Star Rain & Present Celebration
            finalOutcome?.let { (scoreResult, completion) ->
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(LixoColors.CardSurfaceElevated)
                        .border(3.dp, LixoColors.StarGold, RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = LixoStrings.ACTIVITY_FINISH_CELEBRATION_TITLE,
                        style = MaterialTheme.typography.headlineMedium,
                        color = LixoColors.StarGold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = (1..3).joinToString(" ") { if (it <= scoreResult.starsEarned) "⭐" else "☆" },
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "+${completion.netNewStarsAwarded} ${LixoStrings.MAP_STARS_LABEL} • ${scoreResult.achievedWpm} WPM",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LixoColors.SoftTextWhite
                    )

                    if (completion.newlyUnlockedItems.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        completion.newlyUnlockedItems.forEach { item ->
                            Text(
                                text = "🎁 Nytt föremål i garderoben: ${item.nameSv} (${LixoStrings.rarityName(item.rarity)})",
                                style = MaterialTheme.typography.titleMedium,
                                color = LixoColors.PrimaryActionGreen
                            )
                        }
                    } else if (completion.giftOutcome?.isDuplicate == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = LixoStrings.GIFT_DUPLICATE_NOTE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.MutedText
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    BouncyButton(
                        onClick = onCloseToMap,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(LixoStrings.ACTIVITY_BACK_TO_MAP_BTN, color = Color.White)
                    }
                }
            }
        }
    }
}
