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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.sp
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
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Duolingo-styled Reading Activity Screen:
 * - Two distinct cards for mode selection (AI speech recognition vs. self-reading).
 * - Story card on clean white surface with legible Lexend typography.
 * - Word tracking with soft rounded chips.
 * - Gentle syllable & dictionary support card when tapping any word.
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

    DisposableEffect(Unit) {
        onDispose { sttEngine.cancelListening() }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top Bar: Close (X) + Progress Bar + Elapsed Seconds
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            scope.launch { sttEngine.stopListening() }
                            onCloseToMap()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(
                        icon = LixoIcons.Close,
                        tint = LixoColors.TextSecondary,
                        size = 22.dp
                    )
                }
                Spacer(Modifier.width(12.dp))
                LixoProgressBar(
                    progress = sessionState.progressFraction,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LixoColors.Surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    LixoIcon(icon = LixoIcons.Timer, tint = LixoColors.Orange, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${(sessionState.elapsedMillis / 1000L)}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = LixoColors.Orange
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Level Title + TTS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = level.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (showSwedishTtsButtons) {
                    TtsIconButton(
                        textToSpeak = level.title,
                        ttsEngine = ttsEngine
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!modeLocked) {
                // Mode Selection before reading starts
                Text(
                    text = LixoStrings.READING_MODE_SELECT_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.TextSecondary
                )

                Spacer(Modifier.height(12.dp))

                // Mode 1: AI Reading Assistant
                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    selected = chosenMode == ReadingMode.AI_LISTENING,
                    cornerRadius = 18.dp,
                    onClick = { chosenMode = ReadingMode.AI_LISTENING }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (chosenMode == ReadingMode.AI_LISTENING) LixoColors.Blue else LixoColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(
                                icon = LixoIcons.Mic,
                                tint = if (chosenMode == ReadingMode.AI_LISTENING) Color.White else LixoColors.TextSecondary,
                                size = 26.dp
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI-Läsassistent",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Lyssna och följ med i realtid · 1–3 stjärnor",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Mode 2: Manual Self-Reading
                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    selected = chosenMode == ReadingMode.MANUAL,
                    cornerRadius = 18.dp,
                    onClick = {
                        chosenMode = ReadingMode.MANUAL
                        timedChallengeChecked = false
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (chosenMode == ReadingMode.MANUAL) LixoColors.Blue else LixoColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(
                                icon = LixoIcons.Book,
                                tint = if (chosenMode == ReadingMode.MANUAL) Color.White else LixoColors.TextSecondary,
                                size = 26.dp
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Läsa själv",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Läs i egen lugn takt · 1 stjärna",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }
                }

                // Timed Challenge Switch
                if (chosenMode == ReadingMode.AI_LISTENING) {
                    Spacer(Modifier.height(16.dp))
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LixoIcon(icon = LixoIcons.Bolt, tint = LixoColors.Orange, size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${LixoStrings.READING_TIMED_CHALLENGE_TOGGLE} (${level.timeRequirementSeconds}s)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LixoColors.TextPrimary
                                )
                            }
                            Switch(
                                checked = timedChallengeChecked,
                                onCheckedChange = { timedChallengeChecked = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = LixoColors.Green
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                LixoButton(
                    onClick = {
                        sessionState = alignmentEngine.startSession(
                            storyText = storyText,
                            mode = chosenMode,
                            isTimedChallengeRequested = timedChallengeChecked
                        )
                        modeLocked = true
                    },
                    variant = LixoButtonVariant.PRIMARY,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "STARTA LÄSNING",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            } else {
                // Story Card with Real-Time Highlighting
                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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

                            val (bg, border, fg) = when {
                                isCurrent -> Triple(LixoColors.BlueTint, LixoColors.Blue, LixoColors.Blue)
                                token.isCompleted -> Triple(LixoColors.GreenTint, LixoColors.GreenLip, LixoColors.GreenLip)
                                else -> Triple(Color.Transparent, Color.Transparent, LixoColors.TextPrimary)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .then(
                                        if (isCurrent) Modifier.border(1.5.dp, border, RoundedCornerShape(8.dp))
                                        else Modifier
                                    )
                                    .clickable {
                                        inspectedDictionaryEntry = dict
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = renderedText,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = fg
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = LixoStrings.READING_TAP_WORD_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LixoColors.TextMuted
                )

                // Word Help Card when stuck or tapped
                val currentWordEntry = sessionState.currentWord?.let { lookupDictionary(it.normalizedText) }
                val activeSupportEntry = inspectedDictionaryEntry ?: if (sessionState.isWordStuckSupportVisible) currentWordEntry else null

                if (activeSupportEntry != null && !sessionState.isCompleted) {
                    Spacer(Modifier.height(14.dp))
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = LixoColors.GoldTint,
                        borderColor = LixoColors.GoldLip,
                        cornerRadius = 16.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${LixoStrings.READING_STUCK_TITLE} \"${activeSupportEntry.displayWord}\"",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = LixoColors.TextPrimary
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Stavelser: ${activeSupportEntry.syllables}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LixoColors.TextPrimary
                                    )
                                    Text(
                                        text = activeSupportEntry.explanationSv,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LixoColors.TextSecondary
                                    )
                                }
                                if (showSwedishTtsButtons) {
                                    TtsIconButton(
                                        textToSpeak = "${activeSupportEntry.displayWord}. ${activeSupportEntry.explanationSv}",
                                        ttsEngine = ttsEngine
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LixoButton(
                                    onClick = { showSyllablesInline = !showSyllablesInline },
                                    variant = LixoButtonVariant.SECONDARY,
                                    height = 42.dp
                                ) {
                                    Text(
                                        text = LixoStrings.READING_HELP_SYLLABLES,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                                if (sessionState.mode == ReadingMode.AI_LISTENING) {
                                    LixoButton(
                                        onClick = {
                                            sessionState = alignmentEngine.advanceCurrentWordWithGentleSupport(sessionState)
                                            millisSinceLastWordAdvance = 0L
                                            inspectedDictionaryEntry = null
                                        },
                                        variant = LixoButtonVariant.PRIMARY,
                                        height = 42.dp
                                    ) {
                                        Text(
                                            text = LixoStrings.READING_HELP_SKIP_NO_PENALTY,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Action Controls during reading
                if (!sessionState.isCompleted) {
                    if (sessionState.mode == ReadingMode.AI_LISTENING) {
                        LixoButton(
                            onClick = {
                                val word = sessionState.currentWord?.normalizedText ?: return@LixoButton
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
                            variant = LixoButtonVariant.PRIMARY,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LixoIcon(icon = LixoIcons.Mic, tint = Color.White, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = LixoStrings.READING_SIMULATE_VOICE_BTN.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        LixoButton(
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
                            variant = LixoButtonVariant.OUTLINE,
                            height = 46.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.READING_SIMULATE_FULL_SENTENCE_BTN,
                                style = MaterialTheme.typography.labelMedium,
                                color = LixoColors.Blue
                            )
                        }
                    } else {
                        LixoButton(
                            onClick = {
                                sessionState = alignmentEngine.tryCompleteManualReading(sessionState)
                            },
                            enabled = sessionState.isManualCompleteButtonEnabled,
                            variant = LixoButtonVariant.PRIMARY,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val label = if (sessionState.isManualCompleteButtonEnabled) {
                                LixoStrings.READING_MANUAL_DONE_BTN.uppercase()
                            } else {
                                "${LixoStrings.READING_MANUAL_CHARGING_LABEL} (${sessionState.manualModeRemainingSeconds}s)"
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }

                // Completion Celebration
                finalOutcome?.let { (scoreResult, completion) ->
                    Spacer(Modifier.height(20.dp))
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = LixoColors.Green,
                        backgroundColor = LixoColors.GreenTint,
                        cornerRadius = 24.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.ACTIVITY_FINISH_CELEBRATION_TITLE,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.GreenLip
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.Center) {
                                for (i in 1..3) {
                                    LixoIcon(
                                        icon = LixoIcons.Star,
                                        tint = if (i <= scoreResult.starsEarned) LixoColors.Gold else LixoColors.BorderDarker,
                                        size = 36.dp,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "+${completion.netNewStarsAwarded} ${LixoStrings.MAP_STARS_LABEL} · ${scoreResult.achievedWpm} WPM",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary
                            )

                            if (completion.newlyUnlockedItems.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                completion.newlyUnlockedItems.forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.GreenLip, size = 20.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${item.nameSv} (${LixoStrings.rarityName(item.rarity)})",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = LixoColors.GreenLip
                                        )
                                    }
                                }
                            } else if (completion.giftOutcome?.isDuplicate == true) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = LixoStrings.GIFT_DUPLICATE_NOTE,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.TextSecondary
                                )
                            }

                            Spacer(Modifier.height(20.dp))
                            LixoButton(
                                onClick = onCloseToMap,
                                variant = LixoButtonVariant.PRIMARY,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.ACTIVITY_BACK_TO_MAP_BTN,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
