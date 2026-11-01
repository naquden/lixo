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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.ParticleProgressBar
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Screen 1 (`project.md` §2 & §4):
 * - Start & Profile Selection: Tap large animated 3D avatar to enter.
 * - Zero-State Onboarding & Interactive Tutorial: Asks parent for microphone permission early,
 *   runs a real-time AI word-highlighting reading lesson (or falls back to Manual Reading Mode if declined),
 *   and unlocks the player's very first hat in the Universal Wardrobe.
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
                .background(LixoColors.SkyBackgroundBottom)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = LixoStrings.PROFILE_SELECT_TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    color = LixoColors.SoftTextWhite
                )
                if (showSwedishTtsButtons) {
                    Spacer(Modifier.width(12.dp))
                    TtsIconButton(
                        textToSpeak = LixoStrings.PROFILE_SELECT_TITLE,
                        ttsEngine = ttsEngine
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = LixoStrings.PROFILE_TAP_TO_START,
                style = MaterialTheme.typography.bodyLarge,
                color = LixoColors.MutedText
            )

            Spacer(Modifier.height(24.dp))

            // Large animated 3D avatar cards for each sibling profile
            profiles.forEach { profile ->
                val isSelected = profile.id == activeProfile.id
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(LixoColors.CardSurface)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) LixoColors.StarGold else Color(0xFF334155),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            onSelectProfile(profile)
                            if (profile.tutorialCompleted) {
                                onTutorialCompleted(profile)
                            } else {
                                showTutorialStage = true
                            }
                        }
                        .padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Avatar3DViewport(
                            appearance = profile.appearance,
                            catalogById = catalogById,
                            modifier = Modifier.size(150.dp),
                            interactiveRotation = false
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LixoColors.SoftTextWhite
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "⭐ ${profile.totalStars} ${LixoStrings.MAP_STARS_LABEL}   🔥 ${profile.currentStreakDays} ${LixoStrings.MAP_STREAK_LABEL}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.StarGold
                            )
                            Spacer(Modifier.height(12.dp))
                            BouncyButton(
                                onClick = {
                                    onSelectProfile(profile)
                                    if (profile.tutorialCompleted) {
                                        onTutorialCompleted(profile)
                                    } else {
                                        showTutorialStage = true
                                    }
                                }
                            ) {
                                Text(
                                    text = LixoStrings.MAP_PLAY_LEVEL_BTN,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!showCreateSiblingForm) {
                BouncyButton(
                    onClick = { showCreateSiblingForm = true },
                    containerColor = LixoColors.SecondaryBlue,
                    lipColor = LixoColors.SecondaryBlueLip
                ) {
                    Text(
                        text = LixoStrings.PROFILE_ADD_SIBLING,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.CardSurface)
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = newSiblingName,
                        onValueChange = { newSiblingName = it },
                        label = { Text(LixoStrings.PROFILE_NEW_NAME_LABEL) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    BouncyButton(
                        onClick = {
                            if (newSiblingName.isNotBlank()) {
                                onCreateSiblingProfile(newSiblingName)
                                newSiblingName = ""
                                showCreateSiblingForm = false
                            }
                        }
                    ) {
                        Text(LixoStrings.PROFILE_CREATE_BTN, color = Color.White)
                    }
                }
            }
        }
    } else {
        // Zero-State Interactive Reading Tutorial
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
                .background(LixoColors.SkyBackgroundBottom)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = LixoStrings.TUTORIAL_WELCOME_TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.SoftTextWhite
                )
                if (showSwedishTtsButtons) {
                    Spacer(Modifier.width(10.dp))
                    TtsIconButton(
                        textToSpeak = LixoStrings.TUTORIAL_MIC_PROMPT,
                        ttsEngine = ttsEngine
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Zero-state avatar preview (transforms when tutorial completes!)
            val previewAppearance = if (sessionState.isCompleted) {
                activeProfile.appearance.copy(
                    equippedItems = mapOf(
                        se.lixo.app.domain.model.EquipmentSlot.HAT to BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID
                    )
                )
            } else {
                activeProfile.appearance
            }

            Avatar3DViewport(
                appearance = previewAppearance,
                catalogById = catalogById,
                modifier = Modifier.size(180.dp)
            )

            if (selectedMode == null) {
                Text(
                    text = LixoStrings.TUTORIAL_MIC_PROMPT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.SoftTextWhite,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                BouncyButton(
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🎤 ${LixoStrings.TUTORIAL_ALLOW_MIC_BTN}", color = Color.White)
                }

                Spacer(Modifier.height(12.dp))

                BouncyButton(
                    onClick = {
                        selectedMode = ReadingMode.MANUAL
                        sessionState = alignmentEngine.startSession(
                            storyText = BalanceConfig.TUTORIAL_STORY_SV,
                            mode = ReadingMode.MANUAL,
                            isTimedChallengeRequested = false
                        )
                    },
                    containerColor = LixoColors.SecondaryBlue,
                    lipColor = LixoColors.SecondaryBlueLip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(LixoStrings.TUTORIAL_MANUAL_FALLBACK_BTN, color = Color.White)
                }

                if (activeProfile.tutorialSkippableByParent) {
                    Spacer(Modifier.height(12.dp))
                    BouncyButton(
                        onClick = { onTutorialCompleted(activeProfile) },
                        containerColor = LixoColors.CardSurfaceElevated,
                        lipColor = LixoColors.CardSurface
                    ) {
                        Text(LixoStrings.TUTORIAL_SKIP_BTN, color = LixoColors.SoftTextWhite)
                    }
                }
            } else {
                ParticleProgressBar(
                    progress = sessionState.progressFraction,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Real-time word highlighting box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.CardSurface)
                        .padding(18.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        sessionState.words.forEachIndexed { idx, token ->
                            val isCurrent = idx == sessionState.currentWordIndex && !sessionState.isCompleted
                            val bg = when {
                                token.isCompleted -> LixoColors.PrimaryActionGreen.copy(alpha = 0.28f)
                                isCurrent -> LixoColors.StarGold
                                else -> Color.Transparent
                            }
                            val textColor = if (isCurrent) Color(0xFF0F172A) else LixoColors.SoftTextWhite
                            Text(
                                text = token.displayText,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = textColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (!sessionState.isCompleted) {
                    if (selectedMode == ReadingMode.AI_LISTENING) {
                        BouncyButton(
                            onClick = {
                                val nextWord = sessionState.currentWord?.normalizedText ?: return@BouncyButton
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎙️ ${LixoStrings.READING_SIMULATE_VOICE_BTN}", color = Color.White)
                        }
                    } else {
                        BouncyButton(
                            onClick = {
                                sessionState = alignmentEngine.tryCompleteManualReading(sessionState)
                            },
                            enabled = sessionState.isManualCompleteButtonEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val pct = (sessionState.manualModeButtonChargeFraction * 100).toInt()
                            val label = if (sessionState.isManualCompleteButtonEnabled) {
                                LixoStrings.READING_MANUAL_DONE_BTN
                            } else {
                                "${LixoStrings.READING_MANUAL_CHARGING_LABEL} ($pct%)"
                            }
                            Text(label, color = Color.White)
                        }
                    }
                } else {
                    // Tutorial Completed Celebration!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(LixoColors.rarityBrush(se.lixo.app.domain.model.Rarity.COMMON))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "🎉 ⭐⭐⭐ 🎉",
                                style = MaterialTheme.typography.headlineLarge
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = LixoStrings.TUTORIAL_REWARD_BANNER,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(14.dp))
                            BouncyButton(
                                onClick = { onTutorialCompleted(activeProfile) }
                            ) {
                                Text(LixoStrings.TUTORIAL_CONTINUE_TO_MAP_BTN, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
