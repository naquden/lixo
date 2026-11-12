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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.ReadingScoreResult
import se.lixo.app.domain.engine.StoryWordToken
import se.lixo.app.domain.engine.spellingDiff
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.celebration.CelebrationSequenceHost
import se.lixo.app.ui.components.celebration.FlitChestMeter
import se.lixo.app.ui.components.celebration.LevelCompleteCard
import se.lixo.app.ui.components.celebration.chestFillAnimation
import se.lixo.app.ui.components.celebration.chestMeterStartDelayMillis
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton
import se.lixo.app.ui.theme.dashedBorder

/**
 * Duolingo-styled Reading Activity Screen:
 * - Two distinct cards for mode selection (AI speech recognition vs. self-reading).
 * - Story card on clean white surface with legible Lexend typography.
 * - Word tracking with soft rounded chips (blue = current, yellow = flagged/needs practice, green = done).
 * - Tap-any-word help popup (syllables, explanation, listen) that never blocks reading.
 */
private const val HEARD_WORD_READ_DISMISS_DELAY_MS = 800L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingActivityScreen(
    level: CampaignLevel,
    task: HomeworkTask,
    alignmentEngine: ForcedAlignmentEngine,
    sttEngine: SpeechRecognitionEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    showDebugSimulationControls: Boolean,
    lookupDictionary: (String) -> DictionaryEntry,
    onFinishReadingSession: (ReadingScoreResult, Int) -> LevelCompletionOutcome,
    content: ActiveContent,
    onEquipItem: (ContentItem) -> Unit,
    onSelectAvatar: (String) -> Unit,
    onCloseToMap: () -> Unit
) {
    val storyText = task.textContent ?: ""
    val speechLanguageIso = if (level.subject == se.lixo.app.domain.model.Subject.ENGLISH) "en-US" else "sv-SE"
    var modeLocked by remember { mutableStateOf(false) }
    var chosenMode by remember { mutableStateOf(ReadingMode.AI_LISTENING) }

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
    var helpPopupWordIndex by remember { mutableStateOf<Int?>(null) }
    var finalOutcome by remember { mutableStateOf<Pair<ReadingScoreResult, LevelCompletionOutcome>?>(null) }
    var celebrationSequenceFinished by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    DisposableEffect(Unit) {
        onDispose { sttEngine.cancelListening() }
    }

    LaunchedEffect(finalOutcome != null) {
        if (finalOutcome != null) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
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
                    sttEngine.updateExpectedPosition(sessionState.upcomingWordsForStt)
                }
            }
            sttEngine.updateExpectedPosition(sessionState.upcomingWordsForStt)
            sttEngine.startListening(
                languageIso = speechLanguageIso,
                expectedWords = sessionState.sttExpectedWords
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
                .verticalScroll(scrollState)
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
                    val elapsedSecondsLabel = (sessionState.elapsedMillis / 1000L).toString()
                    val timerText = if (sessionState.mode == ReadingMode.AI_LISTENING) {
                        "${elapsedSecondsLabel}s / ${level.timeRequirementSeconds}s"
                    } else {
                        "${elapsedSecondsLabel}s"
                    }
                    Text(
                        text = timerText,
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
                                text = "Lyssna och följ med i realtid · 1–3 stjärnor · Mål ${level.timeRequirementSeconds}s",
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
                    onClick = { chosenMode = ReadingMode.MANUAL }
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

                Spacer(Modifier.height(24.dp))

                LixoButton(
                    onClick = {
                        sessionState = alignmentEngine.startSession(
                            storyText = storyText,
                            mode = chosenMode,
                            isTimedChallengeRequested = chosenMode == ReadingMode.AI_LISTENING
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
                            val isCurrentStuck = isCurrent && sessionState.isWordStuckSupportVisible
                            val isYellowUnread = token.needsPractice && !token.isCompleted && !isCurrent
                            val isYellowRead = token.needsPractice && token.isCompleted

                            val dict = lookupDictionary(token.normalizedText)
                            val renderedText = if (isCurrentStuck) dict.syllables else token.displayText

                            val (bg, border, fg) = when {
                                isCurrentStuck -> Triple(LixoColors.GoldTint, LixoColors.Gold, LixoColors.GoldLip)
                                isCurrent -> Triple(LixoColors.BlueTint, LixoColors.Blue, LixoColors.Blue)
                                isYellowUnread -> Triple(LixoColors.GoldTint, LixoColors.Gold, LixoColors.GoldLip)
                                isYellowRead -> Triple(LixoColors.GoldTint, Color.Transparent, LixoColors.GoldLip)
                                token.isCompleted -> Triple(LixoColors.GreenTint, LixoColors.GreenLip, LixoColors.GreenLip)
                                else -> Triple(Color.Transparent, Color.Transparent, LixoColors.TextPrimary)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .then(
                                        when {
                                            isYellowUnread -> Modifier.dashedBorder(border, 8.dp)
                                            isCurrent -> Modifier.border(1.5.dp, border, RoundedCornerShape(8.dp))
                                            else -> Modifier
                                        }
                                    )
                                    .clickable { helpPopupWordIndex = idx }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = renderedText,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = fg
                                )
                                if ((isYellowUnread || isYellowRead) && token.heardText != null) {
                                    Box(Modifier.matchParentSize()) {
                                        LixoIcon(
                                            icon = LixoIcons.Mic,
                                            tint = LixoColors.GoldLip,
                                            size = 12.dp,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 4.dp, y = (-2).dp)
                                        )
                                    }
                                }
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

                if (sessionState.isAwaitingUnreadWords) {
                    Spacer(Modifier.height(14.dp))
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = LixoColors.GoldTint,
                        borderColor = LixoColors.GoldLip,
                        cornerRadius = 14.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LixoIcon(icon = LixoIcons.Star, tint = LixoColors.GoldLip, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = LixoStrings.READING_READ_YELLOW_WORDS,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    LixoButton(
                        onClick = {
                            sessionState = alignmentEngine.finishWithUnreadWords(sessionState)
                        },
                        variant = LixoButtonVariant.PRIMARY,
                        height = 52.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Action Controls during reading
                if (!sessionState.isCompleted) {
                    if (sessionState.mode == ReadingMode.AI_LISTENING) {
                        if (showDebugSimulationControls) {
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
                        }

                        if (alignmentEngine.isLastWordMissedCandidate(sessionState, millisSinceLastWordAdvance)) {
                            if (showDebugSimulationControls) Spacer(Modifier.height(10.dp))
                            LixoButton(
                                onClick = {
                                    sessionState = alignmentEngine.creditMissedLastWord(sessionState)
                                    millisSinceLastWordAdvance = 0L
                                },
                                variant = LixoButtonVariant.OUTLINE,
                                height = 46.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.READING_LAST_WORD_MISSED_BTN,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LixoColors.Blue
                                )
                            }
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
                    LevelCompleteCard(
                        starsEarned = scoreResult.starsEarned,
                        netNewStars = completion.netNewStarsAwarded,
                        statsSuffix = "${scoreResult.achievedWpm} WPM",
                        showDuplicateGiftNote = completion.newlyUnlockedItems.isEmpty() &&
                            completion.giftOutcome?.isDuplicate == true,
                        showDuplicateChestNote = completion.chestOutcome?.isDuplicate == true,
                        onContinue = onCloseToMap,
                        continueEnabled = celebrationSequenceFinished,
                        modifier = Modifier.fillMaxWidth(),
                        cornerContent = {
                            FlitChestMeter(
                                halfPoints = completion.updatedProfile.chestProgressHalfPoints,
                                thresholdHalf = content.dropRules.chestCostHalfPoints,
                                size = 44.dp,
                                animation = chestFillAnimation(
                                    completion.updatedProfile.chestProgressHalfPoints,
                                    completion.chestPointsAwardedHalf,
                                    completion.chestOutcome != null,
                                    content.dropRules.chestCostHalfPoints
                                ),
                                startDelayMillis = chestMeterStartDelayMillis(scoreResult.starsEarned)
                            )
                        }
                    ) {
                        if (completion.chestOutcome?.newlyUnlockedItem != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = LixoStrings.CHEST_OPENED,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.TextSecondary
                            )
                        }

                        if (sessionState.mode == ReadingMode.AI_LISTENING) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "${LixoStrings.READING_TIME_ROW_PREFIX} ${(sessionState.elapsedMillis / 1000L)}s · " +
                                    "${LixoStrings.READING_TIME_ROW_BEST_PREFIX} ${completion.updatedLevelProgress.bestTimeSeconds}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.TextSecondary
                            )
                            if (scoreResult.eligibleForTimedChallengeReward) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = LixoStrings.READING_TIMED_CHALLENGE_COMPLETED,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LixoColors.OrangeLip
                                )
                            }
                            if (completion.netNewStarsAwarded == 0 && completion.updatedLevelProgress.starsEarned == 3) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = LixoStrings.READING_TIME_RECORD_AT_MAX_STARS_NOTE,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LixoColors.TextMuted
                                )
                            }
                        }

                        val practiceWords = sessionState.words
                            .filter { it.needsPractice }
                            .distinctBy { it.normalizedText }
                        if (practiceWords.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = LixoStrings.READING_PRACTICE_WORDS_TITLE,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                practiceWords.forEach { word ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LixoColors.GoldTint)
                                            .border(1.5.dp, LixoColors.Gold, RoundedCornerShape(8.dp))
                                            .clickable { helpPopupWordIndex = word.index }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = word.displayText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = LixoColors.GoldLip
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tap-any-word help popup: works during and after the session, never touches sttEngine.
        val popupWord = helpPopupWordIndex?.let { sessionState.words.getOrNull(it) }
        val popupEntry = popupWord?.let { lookupDictionary(it.normalizedText) }
        val showHeardCompare = popupWord != null && popupWord.needsPractice &&
            sessionState.mode == ReadingMode.AI_LISTENING
        val popupOpenedUnread = remember(helpPopupWordIndex) { popupWord?.isCompleted == false }
        var showPopupReadCheck by remember(helpPopupWordIndex) { mutableStateOf(false) }
        LaunchedEffect(helpPopupWordIndex, popupWord?.isCompleted) {
            if (showHeardCompare && popupOpenedUnread && popupWord?.isCompleted == true) {
                showPopupReadCheck = true
                delay(HEARD_WORD_READ_DISMISS_DELAY_MS)
                helpPopupWordIndex = null
            }
        }
        if (popupEntry != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LixoColors.Scrim)
                    .clickable { helpPopupWordIndex = null },
                contentAlignment = Alignment.BottomCenter
            ) {
                LixoCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    backgroundColor = LixoColors.GoldTint,
                    borderColor = LixoColors.GoldLip,
                    cornerRadius = 22.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    onClick = { /* absorb taps so they don't fall through to the dismiss scrim */ }
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${LixoStrings.READING_STUCK_TITLE} \"${popupEntry.displayWord}\"",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { helpPopupWordIndex = null },
                                contentAlignment = Alignment.Center
                            ) {
                                LixoIcon(icon = LixoIcons.Close, tint = LixoColors.TextSecondary, size = 18.dp)
                            }
                        }
                        if (showHeardCompare) {
                            Spacer(Modifier.height(10.dp))
                            HeardWordCompareBlock(
                                word = popupWord!!,
                                isSessionCompleted = sessionState.isCompleted,
                                showTtsButtons = showSwedishTtsButtons,
                                ttsEngine = ttsEngine,
                                languageIso = speechLanguageIso,
                                showReadCheck = showPopupReadCheck
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = popupEntry.syllables,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = LixoColors.GoldLip
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = popupEntry.explanationSv,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextSecondary
                        )
                        if (showSwedishTtsButtons) {
                            Spacer(Modifier.height(14.dp))
                            TtsIconButton(
                                textToSpeak = "${popupEntry.displayWord}. ${popupEntry.explanationSv}",
                                ttsEngine = ttsEngine
                            )
                        }
                    }
                }
            }
        }

        finalOutcome?.let { (s, c) ->
            CelebrationSequenceHost(
                content = content,
                outcome = c,
                starsEarned = s.starsEarned,
                onEquipItem = onEquipItem,
                onSelectAvatar = onSelectAvatar,
                onFinished = { celebrationSequenceFinished = true },
                ttsEngine = ttsEngine,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * "Tryck och jämför": shows the story word next to what the AI heard for a yellow word, with
 * per-letter diff highlighting and an optional "try again" hint while the word is still unread.
 */
@Composable
private fun HeardWordCompareBlock(
    word: StoryWordToken,
    isSessionCompleted: Boolean,
    showTtsButtons: Boolean,
    ttsEngine: TextToSpeechEngine,
    languageIso: String,
    showReadCheck: Boolean
) {
    val target = word.displayText.trim { !it.isLetterOrDigit() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LixoStrings.READING_HEARD_WORD_LABEL,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = LixoColors.TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildAnnotatedString {
                    spellingDiff(target, word.heardText.orEmpty()).forEach { d ->
                        withStyle(
                            if (d.matched) {
                                SpanStyle(color = LixoColors.TextPrimary, fontWeight = FontWeight.Bold)
                            } else {
                                SpanStyle(color = LixoColors.GoldLip, fontWeight = FontWeight.ExtraBold)
                            }
                        ) {
                            append(d.char)
                        }
                    }
                },
                style = MaterialTheme.typography.titleLarge,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            if (showTtsButtons) {
                TtsIconButton(textToSpeak = target, ttsEngine = ttsEngine, languageIso = languageIso)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (word.isCompleted) {
                    LixoStrings.READING_HEARD_AI_FIRST_LABEL
                } else {
                    LixoStrings.READING_HEARD_AI_LABEL
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = LixoColors.TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = word.heardText ?: LixoStrings.READING_HEARD_NOTHING,
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                color = LixoColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (showTtsButtons && word.heardText != null) {
                TtsIconButton(textToSpeak = word.heardText, ttsEngine = ttsEngine, languageIso = languageIso)
            }
        }
        if (showReadCheck) {
            Spacer(Modifier.height(10.dp))
            LixoIcon(icon = LixoIcons.Check, tint = LixoColors.Green, size = 28.dp)
        } else if (!word.isCompleted && !isSessionCompleted) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LixoIcon(icon = LixoIcons.Mic, tint = LixoColors.Blue, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = LixoStrings.READING_HEARD_TRY_AGAIN_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = LixoColors.Blue
                )
            }
        }
    }
}
