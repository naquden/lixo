package se.lixo.app.ui.screens.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.lixo.app.data.repository.AppPrivacySettings
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.MathErrorLog
import se.lixo.app.domain.model.StruggledWordLog
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

/**
 * Parent View & GDPR Controls (`project.md` §4, §5 & §6):
 * - Accessed via Settings behind a **hard multi-step math problem** (`(a × b) + c = ?`).
 * - Dashboard: Reading time, math problems solved, streaks, sibling profile selector.
 * - Local-only Pedagogical Logs:
 *   - "Ord som kan behöva övas" (`StruggledWordLog`, text + syllables, zero audio stored, never uploaded).
 *   - "Matte-mönster" (`MathErrorLog`, problem type & hint usage, never uploaded).
 * - Adjustable daily goals & tutorial skip toggle. No push notifications, no parent bonus gifts.
 * - Friend list oversight.
 * - GDPR "Data och integritet" section: Local-Only mode toggle, optional email account for public friend avatar,
 *   one-tap account & cloud data deletion, and Crashlytics consent control.
 */
@Composable
fun ParentGateAndDashboardScreen(
    profiles: List<ChildProfile>,
    activeProfile: ChildProfile,
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
            .background(LixoColors.SkyBackgroundBottom)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        if (!isGateUnlocked) {
            // Hard Math Gate protecting Parent View
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(LixoColors.CardSurface)
                    .border(2.dp, LixoColors.StarGold, RoundedCornerShape(24.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔐 ${LixoStrings.PARENT_GATE_TITLE}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.SoftTextWhite
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = LixoStrings.PARENT_GATE_SUBTITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.MutedText
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = challengePrompt,
                        style = MaterialTheme.typography.headlineLarge,
                        color = LixoColors.StarGold
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = gateInput,
                    onValueChange = {
                        gateInput = it
                        gateError = false
                    },
                    label = { Text(LixoStrings.PARENT_GATE_INPUT_LABEL) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (gateError) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = LixoStrings.PARENT_GATE_WRONG_MSG,
                        color = Color(0xFFE74C3C),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(14.dp))
                BouncyButton(
                    onClick = {
                        if (gateInput.trim().toIntOrNull() == challengeAnswer) {
                            isGateUnlocked = true
                        } else {
                            gateError = true
                            challengeSeed++
                            gateInput = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(LixoStrings.PARENT_GATE_OPEN_BTN, color = Color.White)
                }
            }
        } else {
            // Unlocked Parent Dashboard
            Text(
                text = LixoStrings.PARENT_DASHBOARD_TITLE,
                style = MaterialTheme.typography.headlineLarge,
                color = LixoColors.SoftTextWhite
            )
            Spacer(Modifier.height(12.dp))

            // Sibling Profile Switcher
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { child ->
                    BouncyButton(
                        onClick = { onSelectProfile(child) },
                        containerColor = if (child.id == activeProfile.id) LixoColors.PrimaryActionGreen
                        else LixoColors.CardSurfaceElevated
                    ) {
                        Text(child.displayName, color = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Overview Metrics Card (Reading minutes, Math solved, Streaks)
            val totalReadingSeconds = dailyActivity.values.sumOf { it.readingSecondsCompleted }
            val totalReadingMinutes = (totalReadingSeconds / 60).coerceAtLeast(if (totalReadingSeconds > 0) 1 else 0)
            val totalMathProblems = dailyActivity.values.sumOf { it.mathProblemsCompleted }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = "${activeProfile.displayName} — Statistik",
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "📖 Lästid totalt: $totalReadingMinutes min (Mål: ${activeProfile.dailyReadingGoalMinutes} min/dag)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.SoftTextWhite
                )
                Text(
                    text = "🧮 Lösta mattetal: $totalMathProblems st (Mål: ${activeProfile.dailyMathGoalMinutesOrProblemsLabel()})",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.SoftTextWhite
                )
                Text(
                    text = "🔥 Nuvarande streak: ${activeProfile.currentStreakDays} dagar (Bästa: ${activeProfile.bestStreakDays} dagar)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.SoftTextWhite
                )
            }

            Spacer(Modifier.height(16.dp))

            // Adjustable Daily Goals & Tutorial Toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = LixoStrings.PARENT_DAILY_GOALS_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${LixoStrings.PARENT_READING_GOAL_LABEL}: ${activeProfile.dailyReadingGoalMinutes} min",
                        color = LixoColors.SoftTextWhite
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BouncyButton(
                            onClick = {
                                onUpdateProfileGoalsAndSettings(
                                    activeProfile.copy(
                                        dailyReadingGoalMinutes = (activeProfile.dailyReadingGoalMinutes - 5).coerceAtLeast(5)
                                    )
                                )
                            },
                            containerColor = LixoColors.CardSurfaceElevated
                        ) { Text("-5", color = Color.White) }
                        BouncyButton(
                            onClick = {
                                onUpdateProfileGoalsAndSettings(
                                    activeProfile.copy(
                                        dailyReadingGoalMinutes = (activeProfile.dailyReadingGoalMinutes + 5).coerceAtMost(60)
                                    )
                                )
                            }
                        ) { Text("+5", color = Color.White) }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = activeProfile.tutorialSkippableByParent,
                        onCheckedChange = { checked ->
                            onUpdateProfileGoalsAndSettings(
                                activeProfile.copy(tutorialSkippableByParent = checked)
                            )
                        }
                    )
                    Text(
                        text = LixoStrings.PARENT_SKIP_TUTORIAL_TOGGLE,
                        color = LixoColors.SoftTextWhite
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Local-Only Struggled Words ("Ord som kan behöva övas")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = "📝 ${LixoStrings.PARENT_STRUGGLED_WORDS_TITLE}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(8.dp))
                if (struggledWords.isEmpty()) {
                    Text(LixoStrings.PARENT_STRUGGLED_WORDS_EMPTY, color = LixoColors.MutedText)
                } else {
                    struggledWords.forEach { log ->
                        Text(
                            text = "• ${log.wordNormalized} (${log.syllables}) — Övat ${log.encounterCount} ggr",
                            style = MaterialTheme.typography.bodyLarge,
                            color = LixoColors.SoftTextWhite
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Local-Only Math Error Patterns ("Matte-mönster")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = "📐 ${LixoStrings.PARENT_MATH_PATTERNS_TITLE}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(8.dp))
                if (mathErrors.isEmpty()) {
                    Text(LixoStrings.PARENT_MATH_PATTERNS_EMPTY, color = LixoColors.MutedText)
                } else {
                    mathErrors.take(10).forEach { err ->
                        Text(
                            text = "• ${LixoStrings.mathTypeName(err.problemType)}: \"${err.problemPrompt}\" (${err.hintsUsedCount} ledtrådar)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.SoftTextWhite
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Friend List Oversight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(18.dp)
            ) {
                Text(
                    text = "🤝 ${LixoStrings.PARENT_FRIENDS_OVERSIGHT_TITLE} (${friends.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(8.dp))
                friends.forEach { friend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("@${friend.username}", color = LixoColors.SoftTextWhite)
                        BouncyButton(
                            onClick = { onRemoveFriend(friend.userId) },
                            containerColor = LixoColors.CardSurfaceElevated
                        ) {
                            Text(LixoStrings.FRIENDS_REMOVE_BTN, color = Color.White)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // GDPR "Data och integritet" Section (`project.md` §3 & §6)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .border(1.5.dp, LixoColors.SecondaryBlue, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Text(
                    text = "🛡️ ${LixoStrings.PARENT_GDPR_TITLE}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = LixoStrings.PARENT_GDPR_PRIVACY_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LixoColors.MutedText
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = if (privacySettings.isLocalOnlyMode) {
                        "✓ ${LixoStrings.PARENT_LOCAL_ONLY_ACTIVE}"
                    } else {
                        "${LixoStrings.PARENT_CLOUD_ACCOUNT_ACTIVE} ${privacySettings.optionalAccountEmail}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = LixoColors.PrimaryActionGreen
                )

                Spacer(Modifier.height(10.dp))

                if (privacySettings.isLocalOnlyMode) {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text(LixoStrings.PARENT_EMAIL_INPUT_LABEL) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    BouncyButton(
                        onClick = {
                            scope.launch {
                                onSendEmailSignInLink(emailInput)
                            }
                        },
                        containerColor = LixoColors.PrimaryActionGreen,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✉️ ${LixoStrings.PARENT_SEND_EMAIL_LINK_BTN}", color = Color.White)
                    }
                    if (privacySettings.emailLinkSent || privacySettings.pendingIncomingEmailLink != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = LixoStrings.PARENT_EMAIL_LINK_SENT_BANNER,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.StarGold
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = emailLinkInput,
                            onValueChange = { emailLinkInput = it },
                            label = { Text(LixoStrings.PARENT_EMAIL_LINK_INPUT_LABEL) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        BouncyButton(
                            onClick = {
                                scope.launch {
                                    onCompleteEmailLinkSignIn(
                                        emailLinkInput,
                                        emailInput.takeIf { it.isNotBlank() }
                                    )
                                }
                            },
                            containerColor = LixoColors.SecondaryBlue,
                            lipColor = LixoColors.SecondaryBlueLip,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(LixoStrings.PARENT_COMPLETE_EMAIL_LINK_BTN, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BouncyButton(
                        onClick = {
                            scope.launch {
                                onConnectOptionalEmailAccount(emailInput, "lixo_optional_auth")
                            }
                        },
                        containerColor = LixoColors.CardSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(LixoStrings.PARENT_CONNECT_ACCOUNT_BTN, color = Color.White)
                    }
                } else {
                    BouncyButton(
                        onClick = onSwitchToLocalOnly,
                        containerColor = LixoColors.CardSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(LixoStrings.PARENT_SWITCH_LOCAL_ONLY_BTN, color = Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    BouncyButton(
                        onClick = {
                            scope.launch {
                                onDeleteCloudAccount()
                                emailInput = ""
                            }
                        },
                        containerColor = Color(0xFFE74C3C),
                        lipColor = Color(0xFFC0392B),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🗑️ ${LixoStrings.PARENT_DELETE_ACCOUNT_BTN}", color = Color.White)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = privacySettings.crashlyticsConsentGranted,
                        onCheckedChange = { onToggleCrashlyticsConsent(it) }
                    )
                    Text(
                        text = "Firebase Crashlytics (Samtycke efter 3:e appstarten)",
                        color = LixoColors.SoftTextWhite
                    )
                }
            }
        }
    }
}

private fun ChildProfile.dailyMathGoalMinutesOrProblemsLabel(): String =
    "$dailyMathGoalProblems tal/dag"
