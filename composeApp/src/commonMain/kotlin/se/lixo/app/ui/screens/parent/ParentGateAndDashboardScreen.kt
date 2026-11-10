package se.lixo.app.ui.screens.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.launch
import se.lixo.app.data.repository.AppPrivacySettings
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.EMAIL_BACKUP_FEATURE_ENABLED
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.MathErrorLog
import se.lixo.app.domain.model.StruggledWordLog
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings

/**
 * Duolingo-styled Parent Gate & Dashboard:
 * - Clean math security gate for adults.
 * - 2x2 clear metric tiles for learning progress.
 * - Single-line aligned stepper controls for daily goals.
 * - GDPR and pedagogical logs presented in clean white cards without clutter.
 */
@Composable
fun ParentGateAndDashboardScreen(
    profiles: List<ChildProfile>,
    activeProfile: ChildProfile,
    content: ActiveContent,
    dailyActivity: Map<String, DailyActivityRecord>,
    struggledWords: List<StruggledWordLog>,
    mathErrors: List<MathErrorLog>,
    friends: List<FriendProfile>,
    privacySettings: AppPrivacySettings,
    mathEngine: MathEngine,
    onSelectProfile: (ChildProfile) -> Unit,
    onUpdateProfileGoalsAndSettings: (ChildProfile) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onSendEmailSignInLink: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onCompleteEmailLinkSignIn: suspend (String, String?) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onConnectOptionalEmailAccount: suspend (String, String) -> Result<Unit>,
    onSwitchToLocalOnly: () -> Unit,
    onDeleteCloudAccount: suspend () -> Result<Unit>,
    onToggleCrashlyticsConsent: (Boolean) -> Unit
) {
    var isGateUnlocked by remember { mutableStateOf(false) }
    var challengeSeed by remember { mutableIntStateOf(7) }
    val (challengePrompt, challengeAnswer) = remember(challengeSeed) {
        mathEngine.generateParentGateChallenge(challengeSeed)
    }
    var gateInput by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf(false) }

    var emailInput by remember(privacySettings.optionalAccountEmail, privacySettings.pendingSignInEmail) {
        mutableStateOf(
            privacySettings.optionalAccountEmail
                ?: privacySettings.pendingSignInEmail
                ?: ""
        )
    }
    var emailLinkInput by remember(privacySettings.pendingIncomingEmailLink) {
        mutableStateOf(privacySettings.pendingIncomingEmailLink ?: "")
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        if (!isGateUnlocked) {
            // Parent Math Gate
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(LixoColors.BlueTint),
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(icon = LixoIcons.Lock, tint = LixoColors.Blue, size = 32.dp)
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = LixoStrings.PARENT_GATE_TITLE,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = LixoStrings.PARENT_GATE_SUBTITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(LixoColors.Surface)
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = challengePrompt,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.TextPrimary
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        OutlinedTextField(
                            value = gateInput,
                            onValueChange = {
                                gateInput = it
                                gateError = false
                            },
                            label = { Text(LixoStrings.PARENT_GATE_INPUT_LABEL) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LixoColors.Blue,
                                unfocusedBorderColor = LixoColors.Border
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (gateError) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = LixoStrings.PARENT_GATE_WRONG_MSG,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.Red
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        LixoButton(
                            onClick = {
                                if (gateInput.trim().toIntOrNull() == challengeAnswer) {
                                    isGateUnlocked = true
                                } else {
                                    gateError = true
                                    challengeSeed++
                                    gateInput = ""
                                }
                            },
                            variant = LixoButtonVariant.PRIMARY,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.PARENT_GATE_OPEN_BTN,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        } else {
            // Unlocked Parent Dashboard
            Text(
                text = LixoStrings.PARENT_DASHBOARD_TITLE,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary
            )

            Spacer(Modifier.height(14.dp))

            // Sibling profile switcher
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { child ->
                    val isSelected = child.id == activeProfile.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) LixoColors.Green else LixoColors.Surface)
                            .then(
                                if (!isSelected) Modifier.border(1.dp, LixoColors.Border, RoundedCornerShape(12.dp))
                                else Modifier
                            )
                            .clickable { onSelectProfile(child) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = child.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (isSelected) Color.White else LixoColors.TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 2x2 Metric Tiles
            val totalReadingSeconds = dailyActivity.values.sumOf { it.readingSecondsCompleted }
            val totalReadingMinutes = (totalReadingSeconds / 60).coerceAtLeast(if (totalReadingSeconds > 0) 1 else 0)
            val totalMathProblems = dailyActivity.values.sumOf { it.mathProblemsCompleted }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Metric 1: Reading
                    LixoCard(
                        modifier = Modifier.weight(1f),
                        cornerRadius = 16.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            LixoIcon(icon = LixoIcons.Book, tint = LixoColors.Blue, size = 22.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "$totalReadingMinutes min",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Mål: ${activeProfile.dailyReadingGoalMinutes} min/dag",
                                style = MaterialTheme.typography.labelSmall,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }

                    // Metric 2: Math
                    LixoCard(
                        modifier = Modifier.weight(1f),
                        cornerRadius = 16.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            LixoIcon(icon = LixoIcons.Calculator, tint = LixoColors.GreenLip, size = 22.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "$totalMathProblems st",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Mål: ${activeProfile.dailyMathGoalProblems}/dag",
                                style = MaterialTheme.typography.labelSmall,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Metric 3: Streak
                    LixoCard(
                        modifier = Modifier.weight(1f),
                        cornerRadius = 16.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            LixoIcon(icon = LixoIcons.Flame, tint = LixoColors.Orange, size = 22.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${activeProfile.currentStreakDays} dagar",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Bästa: ${activeProfile.bestStreakDays} dagar",
                                style = MaterialTheme.typography.labelSmall,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }

                    // Metric 4: Stars
                    LixoCard(
                        modifier = Modifier.weight(1f),
                        cornerRadius = 16.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            LixoIcon(icon = LixoIcons.Star, tint = LixoColors.GoldLip, size = 22.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${activeProfile.totalStars}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.TextPrimary
                            )
                            Text(
                                text = "Stjärnor totalt",
                                style = MaterialTheme.typography.labelSmall,
                                color = LixoColors.TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Daily Goals Steppers
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                Column {
                    Text(
                        text = LixoStrings.PARENT_DAILY_GOALS_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                    Spacer(Modifier.height(14.dp))

                    // Reading Goal Stepper
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.PARENT_READING_GOAL_LABEL,
                            style = MaterialTheme.typography.bodyLarge,
                            color = LixoColors.TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LixoColors.Surface)
                                    .border(1.dp, LixoColors.Border, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onUpdateProfileGoalsAndSettings(
                                            activeProfile.copy(
                                                dailyReadingGoalMinutes = (activeProfile.dailyReadingGoalMinutes - 5).coerceAtLeast(5)
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                LixoIcon(icon = LixoIcons.Minus, tint = LixoColors.TextPrimary, size = 18.dp)
                            }
                            Text(
                                text = "${activeProfile.dailyReadingGoalMinutes} min",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LixoColors.TextPrimary,
                                modifier = Modifier.width(60.dp),
                                textAlign = TextAlign.Center
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LixoColors.Surface)
                                    .border(1.dp, LixoColors.Border, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onUpdateProfileGoalsAndSettings(
                                            activeProfile.copy(
                                                dailyReadingGoalMinutes = (activeProfile.dailyReadingGoalMinutes + 5).coerceAtMost(60)
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                LixoIcon(icon = LixoIcons.Plus, tint = LixoColors.TextPrimary, size = 18.dp)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Tutorial Skip Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.PARENT_SKIP_TUTORIAL_TOGGLE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextPrimary,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Switch(
                            checked = activeProfile.tutorialSkippableByParent,
                            onCheckedChange = { checked ->
                                onUpdateProfileGoalsAndSettings(
                                    activeProfile.copy(tutorialSkippableByParent = checked)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = LixoColors.Green
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Local-Only Struggled Words
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                Column {
                    Text(
                        text = LixoStrings.PARENT_STRUGGLED_WORDS_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                    Spacer(Modifier.height(10.dp))
                    if (struggledWords.isEmpty()) {
                        Text(
                            text = LixoStrings.PARENT_STRUGGLED_WORDS_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            struggledWords.forEach { log ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Text(
                                            text = log.wordNormalized,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = LixoColors.TextPrimary
                                        )
                                        Text(
                                            text = log.syllables,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = LixoColors.TextSecondary
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LixoColors.Surface)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Övat ${log.encounterCount} ggr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LixoColors.TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Local-Only Math Errors
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                Column {
                    Text(
                        text = LixoStrings.PARENT_MATH_PATTERNS_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                    Spacer(Modifier.height(10.dp))
                    if (mathErrors.isEmpty()) {
                        Text(
                            text = LixoStrings.PARENT_MATH_PATTERNS_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            mathErrors.take(8).forEach { err ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = LixoStrings.mathTypeName(err.problemType),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LixoColors.Blue
                                        )
                                        Text(
                                            text = err.problemPrompt,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = LixoColors.TextPrimary
                                        )
                                    }
                                    Text(
                                        text = "${err.hintsUsedCount} ledtrådar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LixoColors.TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Friends Oversight
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                Column {
                    Text(
                        text = "${LixoStrings.PARENT_FRIENDS_OVERSIGHT_TITLE} (${friends.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                    Spacer(Modifier.height(10.dp))
                    if (friends.isEmpty()) {
                        Text(
                            text = "Inga kompisar tillagda ännu",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            friends.forEach { friend ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        // doc/plans/030 §7: "Kalle (@ModigRäv42)" when a label is
                                        // set, else just "@ModigRäv42" -- the parent always sees
                                        // the real generated nickname next to any private label.
                                        text = friend.label?.let { label ->
                                            "$label (@${friend.nick.render(content.nicknameWords)})"
                                        } ?: "@${friend.nick.render(content.nicknameWords)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = LixoColors.TextPrimary
                                    )
                                    LixoButton(
                                        onClick = { onRemoveFriend(friend.userId) },
                                        variant = LixoButtonVariant.GHOST,
                                        height = 36.dp
                                    ) {
                                        Text(
                                            text = LixoStrings.FRIENDS_REMOVE_BTN,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LixoColors.Red
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // GDPR & Data Security
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LixoIcon(icon = LixoIcons.Shield, tint = LixoColors.Blue, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = LixoStrings.PARENT_GDPR_TITLE,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LixoColors.TextPrimary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = LixoStrings.PARENT_GDPR_PRIVACY_NOTE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.TextSecondary
                    )

                    // doc/plans/030 §1: the email/account block is hidden from the UI (kept here,
                    // not deleted) until email backup comes back as its own feature. Every child
                    // now identifies to Firestore via anonymous auth (`ensureCloudIdentity`), and
                    // hiding this prevents a parent from accidentally replacing that anonymous
                    // user with an email-signed-in one, which would orphan every friend card.
                    if (EMAIL_BACKUP_FEATURE_ENABLED) {
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = if (privacySettings.isLocalOnlyMode) LixoStrings.PARENT_LOCAL_ONLY_ACTIVE
                        else "${LixoStrings.PARENT_CLOUD_ACCOUNT_ACTIVE} ${privacySettings.optionalAccountEmail}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.GreenLip
                    )

                    Spacer(Modifier.height(14.dp))

                    if (privacySettings.isLocalOnlyMode) {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            placeholder = { Text(LixoStrings.PARENT_EMAIL_INPUT_LABEL) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LixoColors.Blue,
                                unfocusedBorderColor = LixoColors.Border
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        LixoButton(
                            onClick = {
                                scope.launch { onSendEmailSignInLink(emailInput) }
                            },
                            variant = LixoButtonVariant.PRIMARY,
                            height = 48.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.PARENT_SEND_EMAIL_LINK_BTN,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }

                        if (privacySettings.emailLinkSent || privacySettings.pendingIncomingEmailLink != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = LixoStrings.PARENT_EMAIL_LINK_SENT_BANNER,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.GoldLip
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = emailLinkInput,
                                onValueChange = { emailLinkInput = it },
                                placeholder = { Text(LixoStrings.PARENT_EMAIL_LINK_INPUT_LABEL) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            LixoButton(
                                onClick = {
                                    scope.launch {
                                        onCompleteEmailLinkSignIn(
                                            emailLinkInput,
                                            emailInput.takeIf { it.isNotBlank() }
                                        )
                                    }
                                },
                                variant = LixoButtonVariant.SECONDARY,
                                height = 48.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.PARENT_COMPLETE_EMAIL_LINK_BTN,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        LixoButton(
                            onClick = onSwitchToLocalOnly,
                            variant = LixoButtonVariant.OUTLINE,
                            height = 48.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.PARENT_SWITCH_LOCAL_ONLY_BTN,
                                style = MaterialTheme.typography.labelMedium,
                                color = LixoColors.TextPrimary
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LixoButton(
                            onClick = {
                                scope.launch {
                                    onDeleteCloudAccount()
                                    emailInput = ""
                                }
                            },
                            variant = LixoButtonVariant.DANGER,
                            height = 48.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.PARENT_DELETE_ACCOUNT_BTN,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                    } // if (false): email/account block (doc/plans/030 §1)

                    Spacer(Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Crashlytics samtycke",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextPrimary
                        )
                        Switch(
                            checked = privacySettings.crashlyticsConsentGranted,
                            onCheckedChange = { onToggleCrashlyticsConsent(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = LixoColors.Green
                            )
                        )
                    }
                }
            }
        }
    }
}
