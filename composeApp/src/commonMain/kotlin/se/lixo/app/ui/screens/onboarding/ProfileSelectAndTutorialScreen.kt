package se.lixo.app.ui.screens.onboarding

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.StatPill
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Duolingo-styled Onboarding & Profile Selection:
 * - Crisp centered title and subtitle.
 * - Profile cards with avatar, stats, and 3D lip.
 * - Simple "+ Ny profil" card.
 * - Clean two-step tutorial with microphone permission and reading demo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSelectAndTutorialScreen(
    profiles: List<ChildProfile>,
    activeProfile: ChildProfile,
    catalogById: Map<String, WardrobeItem>,
    sttEngine: SpeechRecognitionEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectProfile: (ChildProfile) -> Unit,
    onCreateSiblingProfile: (String) -> Unit,
    onTutorialCompleted: (ChildProfile) -> Unit
) {
    var showTutorialStage by remember(activeProfile.id, activeProfile.tutorialCompleted) {
        mutableStateOf(false)
    }
    var showCreateSiblingForm by remember { mutableStateOf(false) }
    var newSiblingName by remember { mutableStateOf("") }

    if (!showTutorialStage) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LixoColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(16.dp))

            // Title & Speaker
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = LixoStrings.PROFILE_SELECT_TITLE,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )
                if (showSwedishTtsButtons) {
                    Spacer(Modifier.width(12.dp))
                    TtsIconButton(
                        textToSpeak = LixoStrings.PROFILE_SELECT_TITLE,
                        ttsEngine = ttsEngine
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = LixoStrings.PROFILE_TAP_TO_START,
                style = MaterialTheme.typography.bodyLarge,
                color = LixoColors.TextSecondary
            )

            Spacer(Modifier.height(32.dp))

            // Profile Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                profiles.forEach { profile ->
                    val isSelected = profile.id == activeProfile.id
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        selected = isSelected,
                        cornerRadius = 20.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        onClick = {
                            onSelectProfile(profile)
                            if (profile.tutorialCompleted) {
                                onTutorialCompleted(profile)
                            } else {
                                showTutorialStage = true
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(LixoColors.Surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Avatar3DViewport(
                                    appearance = profile.appearance,
                                    catalogById = catalogById,
                                    modifier = Modifier.size(100.dp),
                                    interactiveRotation = false
                                )
                            }

                            Spacer(Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.displayName,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = LixoColors.TextPrimary
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatPill(
                                        icon = LixoIcons.Star,
                                        value = "${profile.totalStars}",
                                        tint = LixoColors.GoldLip
                                    )
                                    StatPill(
                                        icon = LixoIcons.Flame,
                                        value = "${profile.currentStreakDays}",
                                        tint = LixoColors.Orange
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                LixoButton(
                                    onClick = {
                                        onSelectProfile(profile)
                                        if (profile.tutorialCompleted) {
                                            onTutorialCompleted(profile)
                                        } else {
                                            showTutorialStage = true
                                        }
                                    },
                                    variant = LixoButtonVariant.PRIMARY,
                                    height = 40.dp,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = LixoStrings.MAP_PLAY_LEVEL_BTN,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }

                            LixoIcon(
                                icon = LixoIcons.Check,
                                tint = if (isSelected) LixoColors.Blue else Color.Transparent,
                                size = 24.dp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Add Sibling Card
            if (!showCreateSiblingForm) {
                LixoButton(
                    onClick = { showCreateSiblingForm = true },
                    variant = LixoButtonVariant.OUTLINE,
                    height = 50.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LixoIcon(icon = LixoIcons.Plus, tint = LixoColors.Blue, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = LixoStrings.PROFILE_ADD_SIBLING.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = LixoColors.Blue
                    )
                }
            } else {
                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp
                ) {
                    Column {
                        Text(
                            text = LixoStrings.PROFILE_NEW_NAME_LABEL,
                            style = MaterialTheme.typography.titleMedium,
                            color = LixoColors.TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newSiblingName,
                            onValueChange = { newSiblingName = it },
                            placeholder = { Text("T.ex. Saga") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LixoColors.Blue,
                                unfocusedBorderColor = LixoColors.Border
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LixoButton(
                                onClick = { showCreateSiblingForm = false },
                                variant = LixoButtonVariant.GHOST,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Avbryt", style = MaterialTheme.typography.labelLarge)
                            }
                            LixoButton(
                                onClick = {
                                    if (newSiblingName.isNotBlank()) {
                                        onCreateSiblingProfile(newSiblingName)
                                        newSiblingName = ""
                                        showCreateSiblingForm = false
                                    }
                                },
                                enabled = newSiblingName.isNotBlank(),
                                variant = LixoButtonVariant.PRIMARY,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = LixoStrings.PROFILE_CREATE_BTN,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Interactive Tutorial Stage
        val alignmentEngine = remember { ForcedAlignmentEngine() }
        var selectedMode by remember { mutableStateOf<ReadingMode?>(null) }
        var sessionState by remember {
            mutableStateOf(
                alignmentEngine.startSession(
                    storyText = BalanceConfig.TUTORIAL_STORY_SV,
                    mode = ReadingMode.AI_LISTENING,
                    isTimedChallengeRequested = false
                )
            )
        }
        val scope = rememberCoroutineScope()

        DisposableEffect(Unit) {
            onDispose { sttEngine.cancelListening() }
        }

        LaunchedEffect(selectedMode) {
            if (selectedMode == ReadingMode.AI_LISTENING) {
                sttEngine.recognizedTokens.collect { tokens ->
                    sessionState = alignmentEngine.processRecognizedTokens(sessionState, tokens)
                }
            }
        }

        PlatformBackHandler(enabled = true) {
            sttEngine.cancelListening()
            showTutorialStage = false
        }

        LaunchedEffect(selectedMode, sessionState.isCompleted) {
            if (selectedMode != null && !sessionState.isCompleted) {
                while (!sessionState.isCompleted) {
                    delay(250L)
                    sessionState = alignmentEngine.onTick(
                        state = sessionState,
                        deltaMillis = 250L,
                        millisSinceLastWordAdvance = 0L
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LixoColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = LixoStrings.TUTORIAL_WELCOME_TITLE,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )
                if (showSwedishTtsButtons) {
                    Spacer(Modifier.width(10.dp))
                    TtsIconButton(
                        textToSpeak = LixoStrings.TUTORIAL_MIC_PROMPT,
                        ttsEngine = ttsEngine
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (selectedMode == null) {
                Text(
                    text = LixoStrings.TUTORIAL_MIC_PROMPT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(32.dp))

                LixoButton(
                    onClick = {
                        scope.launch {
                            val granted = sttEngine.requestMicrophonePermission()
                            val mode = if (granted) ReadingMode.AI_LISTENING else ReadingMode.MANUAL
                            selectedMode = mode
                            sessionState = alignmentEngine.startSession(
                                storyText = BalanceConfig.TUTORIAL_STORY_SV,
                                mode = mode,
                                isTimedChallengeRequested = false
                            )
                            if (mode == ReadingMode.AI_LISTENING) {
                                sttEngine.startListening(
                                    languageIso = "sv-SE",
                                    expectedWords = sessionState.words.map { it.normalizedText }
                                )
                            }
                        }
                    },
                    variant = LixoButtonVariant.PRIMARY,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LixoIcon(icon = LixoIcons.Mic, tint = Color.White, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = LixoStrings.TUTORIAL_ALLOW_MIC_BTN.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }

                Spacer(Modifier.height(12.dp))

                LixoButton(
                    onClick = {
                        selectedMode = ReadingMode.MANUAL
                        sessionState = alignmentEngine.startSession(
                            storyText = BalanceConfig.TUTORIAL_STORY_SV,
                            mode = ReadingMode.MANUAL,
                            isTimedChallengeRequested = false
                        )
                    },
                    variant = LixoButtonVariant.OUTLINE,
                    height = 50.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.TUTORIAL_MANUAL_FALLBACK_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = LixoColors.Blue
                    )
                }

                if (activeProfile.tutorialSkippableByParent) {
                    Spacer(Modifier.height(12.dp))
                    LixoButton(
                        onClick = { onTutorialCompleted(activeProfile) },
                        variant = LixoButtonVariant.GHOST,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.TUTORIAL_SKIP_BTN,
                            style = MaterialTheme.typography.labelMedium,
                            color = LixoColors.TextMuted
                        )
                    }
                }
            } else {
                LixoProgressBar(
                    progress = sessionState.progressFraction,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Spacer(Modifier.height(16.dp))

                // Real-time word highlighting box
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
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = token.displayText,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = fg
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (!sessionState.isCompleted) {
                    if (selectedMode == ReadingMode.AI_LISTENING) {
                        LixoButton(
                            onClick = {
                                val nextWord = sessionState.currentWord?.normalizedText ?: return@LixoButton
                                sessionState = alignmentEngine.processRecognizedTokens(
                                    state = sessionState,
                                    spokenTokens = listOf(
                                        RecognizedSpeechToken(
                                            rawWord = nextWord,
                                            normalizedWord = nextWord,
                                            confidence = 0.96f,
                                            timestampMillis = 0L
                                        )
                                    )
                                )
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
                } else {
                    // Tutorial Completed Celebration!
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = LixoColors.Green,
                        backgroundColor = LixoColors.GreenTint,
                        cornerRadius = 24.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.Center) {
                                for (i in 1..3) {
                                    LixoIcon(
                                        icon = LixoIcons.Star,
                                        tint = LixoColors.Gold,
                                        size = 36.dp,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = LixoStrings.TUTORIAL_REWARD_BANNER,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.GreenLip,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))
                            LixoButton(
                                onClick = { onTutorialCompleted(activeProfile) },
                                variant = LixoButtonVariant.PRIMARY,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase(),
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
