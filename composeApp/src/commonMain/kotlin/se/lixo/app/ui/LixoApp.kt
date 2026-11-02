package se.lixo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.PlatformFeatureFlags
import se.lixo.app.platform.createPlatformSpeechRecognitionEngine
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.screens.onboarding.ProfileSelectAndTutorialScreen
import se.lixo.app.ui.screens.parent.ParentGateAndDashboardScreen
import se.lixo.app.ui.screens.reading.ReadingActivityScreen
import se.lixo.app.ui.screens.social.TrophiesAndFriendsScreen
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

enum class LixoMainDestination {
    PROFILE_SELECT_AND_TUTORIAL,
    MAP_HOME,
    WARDROBE,
    TROPHIES_AND_FRIENDS,
    PARENT_SETTINGS
}

/**
 * Root Compose Multiplatform application wiring all screens, engines, platform `expect/actual`
 * services, and 3rd-launch Crashlytics consent dialog (`project.md` §4 & §6).
 */
@Composable
fun LixoApp(
    repository: LixoRepository = remember { LixoRepository() }
) {
    val featureFlags = remember { PlatformFeatureFlags() }
    val sttEngine = remember { createPlatformSpeechRecognitionEngine() }
    val ttsEngine = remember { createPlatformTextToSpeechEngine() }
    val alignmentEngine = remember { ForcedAlignmentEngine() }
    val mathEngine = remember { MathEngine() }
    val progressionEngine = remember { ProgressionAndGiftEngine() }

    val profiles by repository.profiles.collectAsState()
    val activeProfileId by repository.activeProfileId.collectAsState()
    val levels by repository.levels.collectAsState()
    val tasksById by repository.tasksById.collectAsState()
    val wardrobeCatalog by repository.wardrobeCatalog.collectAsState()
    val unlockedItemIds by repository.unlockedItemIds.collectAsState()
    val levelProgressByProfile by repository.levelProgressByProfile.collectAsState()
    val dailyActivityByProfile by repository.dailyActivityByProfile.collectAsState()
    val struggledWordsByProfile by repository.struggledWordsByProfile.collectAsState()
    val mathErrorsByProfile by repository.mathErrorsByProfile.collectAsState()
    val achievements by repository.achievements.collectAsState()
    val friends by repository.friends.collectAsState()
    val privacySettings by repository.privacySettings.collectAsState()
    val incomingEmailLink by EmailLinkAuthDispatcher.incomingLink.collectAsState()

    LaunchedEffect(Unit) {
        repository.syncCurriculumFromCloud()
    }

    LaunchedEffect(incomingEmailLink) {
        val link = incomingEmailLink
        if (!link.isNullOrBlank()) {
            repository.completeEmailLinkSignIn(link)
            EmailLinkAuthDispatcher.consumeIncomingLink()
        }
    }

    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.first()
    val catalogById = remember(wardrobeCatalog) { wardrobeCatalog.associateBy { it.id } }
    val ttsAvailable by ttsEngine.isAvailable.collectAsState()
    val showSwedishTts = featureFlags.isSwedishTtsSupported && ttsAvailable

    var currentDestination by remember {
        mutableStateOf(LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL)
    }
    var activeGameplayLevel by remember { mutableStateOf<CampaignLevel?>(null) }

    // System back: close level -> back to map -> back to profile selection -> (system exits).
    PlatformBackHandler(
        enabled = activeGameplayLevel != null ||
            currentDestination != LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
    ) {
        when {
            activeGameplayLevel != null -> activeGameplayLevel = null
            currentDestination == LixoMainDestination.MAP_HOME ->
                currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
            else -> currentDestination = LixoMainDestination.MAP_HOME
        }
    }

    LixoTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LixoColors.SkyBackgroundBottom)
                .safeDrawingPadding()
        ) {
            val playingLevel = activeGameplayLevel
            val playingTask = playingLevel?.let { tasksById[it.taskId] }

            if (playingLevel != null && playingTask != null) {
                // Fullscreen Homework Activity View (Reading, Math, or English)
                if (playingTask.type == TaskType.READING) {
                    ReadingActivityScreen(
                        level = playingLevel,
                        task = playingTask,
                        alignmentEngine = alignmentEngine,
                        sttEngine = sttEngine,
                        ttsEngine = ttsEngine,
                        showSwedishTtsButtons = showSwedishTts,
                        lookupDictionary = { repository.lookupDictionaryWord(it) },
                        onFinishReadingSession = { scoreResult, elapsedSeconds ->
                            scoreResult.struggledWordsToLog.forEach { word ->
                                repository.logStruggledWord(activeProfile.id, word, playingLevel.id)
                            }
                            val todayIso = repository.currentLocalDateIso()
                            val prevProgress = levelProgressByProfile[activeProfile.id]?.get(playingLevel.id)
                            val todayRecord = dailyActivityByProfile[activeProfile.id]?.get(todayIso)
                                ?: DailyActivityRecord(activeProfile.id, todayIso)

                            val outcome = progressionEngine.recordLevelCompletion(
                                profile = activeProfile,
                                level = playingLevel,
                                previousProgress = prevProgress,
                                earnedStarsThisRun = scoreResult.starsEarned,
                                completedInAiMode = scoreResult.eligibleForGift,
                                elapsedSeconds = elapsedSeconds,
                                achievedWpm = scoreResult.achievedWpm,
                                completedTimedChallenge = scoreResult.eligibleForTimedChallengeReward,
                                eligibleForRepetitionGift = scoreResult.eligibleForGift,
                                rewardItemIdForTask = playingTask.rewardItemId,
                                allWardrobeItems = wardrobeCatalog,
                                currentlyUnlockedItemIds = unlockedItemIds,
                                todayActivityBeforeRun = todayRecord,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds()
                            )
                            repository.updateProfile(outcome.updatedProfile)
                            repository.saveLevelProgress(outcome.updatedLevelProgress)
                            outcome.newlyUnlockedItems.forEach { repository.unlockWardrobeItem(it.id) }
                            repository.saveDailyActivity(
                                todayRecord.copy(
                                    readingSecondsCompleted = todayRecord.readingSecondsCompleted + elapsedSeconds,
                                    readingTasksCompleted = todayRecord.readingTasksCompleted + 1
                                )
                            )
                            outcome
                        },
                        onCloseToMap = { activeGameplayLevel = null }
                    )
                } else {
                    MathActivityScreen(
                        level = playingLevel,
                        task = playingTask,
                        mathEngine = mathEngine,
                        ttsEngine = ttsEngine,
                        showSwedishTtsButtons = showSwedishTts,
                        onLogMathError = { type, prompt, wrong, hints ->
                            repository.logMathError(activeProfile.id, type, prompt, wrong, hints)
                        },
                        onFinishMathSession = { scoreResult, elapsedSeconds ->
                            val todayIso = repository.currentLocalDateIso()
                            val prevProgress = levelProgressByProfile[activeProfile.id]?.get(playingLevel.id)
                            val todayRecord = dailyActivityByProfile[activeProfile.id]?.get(todayIso)
                                ?: DailyActivityRecord(activeProfile.id, todayIso)

                            val outcome = progressionEngine.recordLevelCompletion(
                                profile = activeProfile,
                                level = playingLevel,
                                previousProgress = prevProgress,
                                earnedStarsThisRun = scoreResult.starsEarned,
                                completedInAiMode = true,
                                elapsedSeconds = elapsedSeconds,
                                achievedWpm = 0,
                                completedTimedChallenge = scoreResult.completedTimedChallenge,
                                eligibleForRepetitionGift = true,
                                rewardItemIdForTask = playingTask.rewardItemId,
                                allWardrobeItems = wardrobeCatalog,
                                currentlyUnlockedItemIds = unlockedItemIds,
                                todayActivityBeforeRun = todayRecord,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds()
                            )
                            repository.updateProfile(outcome.updatedProfile)
                            repository.saveLevelProgress(outcome.updatedLevelProgress)
                            outcome.newlyUnlockedItems.forEach { repository.unlockWardrobeItem(it.id) }
                            repository.saveDailyActivity(
                                todayRecord.copy(
                                    mathTasksCompleted = todayRecord.mathTasksCompleted + 1,
                                    mathProblemsCompleted = todayRecord.mathProblemsCompleted + scoreResult.totalProblems
                                )
                            )
                            outcome
                        },
                        onCloseToMap = { activeGameplayLevel = null }
                    )
                }
            } else if (currentDestination == LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL) {
                ProfileSelectAndTutorialScreen(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    catalogById = catalogById,
                    sttEngine = sttEngine,
                    ttsEngine = ttsEngine,
                    showSwedishTtsButtons = showSwedishTts,
                    onSelectProfile = { repository.selectProfile(it.id) },
                    onCreateSiblingProfile = { name -> repository.createSiblingProfile(name, 8) },
                    onTutorialCompleted = { profile ->
                        repository.unlockWardrobeItem(BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID)
                        val updated = profile.copy(
                            tutorialCompleted = true,
                            appearance = profile.appearance.copy(
                                equippedItems = profile.appearance.equippedItems +
                                    (EquipmentSlot.HAT to BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID)
                            )
                        )
                        repository.updateProfile(updated)
                        currentDestination = LixoMainDestination.MAP_HOME
                    }
                )
            } else {
                // Main Scaffold with Bouncy Tactile Bottom Navigation (`project.md` §4 "Bottom nav")
                Scaffold(
                    // Root Box already applies safeDrawingPadding; avoid double insets.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LixoColors.CardSurface)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BouncyButton(
                                onClick = { currentDestination = LixoMainDestination.MAP_HOME },
                                containerColor = if (currentDestination == LixoMainDestination.MAP_HOME)
                                    LixoColors.PrimaryActionGreen else LixoColors.CardSurfaceElevated,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "🗺️ ${LixoStrings.NAV_MAP}",
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            BouncyButton(
                                onClick = { currentDestination = LixoMainDestination.WARDROBE },
                                containerColor = if (currentDestination == LixoMainDestination.WARDROBE)
                                    LixoColors.PrimaryActionGreen else LixoColors.CardSurfaceElevated,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "👑 ${LixoStrings.NAV_WARDROBE}",
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            BouncyButton(
                                onClick = { currentDestination = LixoMainDestination.TROPHIES_AND_FRIENDS },
                                containerColor = if (currentDestination == LixoMainDestination.TROPHIES_AND_FRIENDS)
                                    LixoColors.PrimaryActionGreen else LixoColors.CardSurfaceElevated,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text(
                                    text = "🏆 ${LixoStrings.NAV_TROPHIES_FRIENDS}",
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            BouncyButton(
                                onClick = { currentDestination = LixoMainDestination.PARENT_SETTINGS },
                                containerColor = if (currentDestination == LixoMainDestination.PARENT_SETTINGS)
                                    LixoColors.SecondaryBlue else LixoColors.CardSurfaceElevated,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "⚙️",
                                    color = Color.White,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentDestination) {
                            LixoMainDestination.MAP_HOME -> {
                                CampaignMapScreen(
                                    profile = activeProfile,
                                    levels = levels,
                                    progressByLevelId = levelProgressByProfile[activeProfile.id].orEmpty(),
                                    catalogById = catalogById,
                                    progressionEngine = progressionEngine,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    onSelectLevel = { activeGameplayLevel = it },
                                    onSwitchProfileClick = {
                                        currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
                                    }
                                )
                            }
                            LixoMainDestination.WARDROBE -> {
                                WardrobeScreen(
                                    profile = activeProfile,
                                    allWardrobeItems = wardrobeCatalog,
                                    unlockedItemIds = unlockedItemIds,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    onSelectAvatarType = { repository.selectAvatarTypeOnActiveProfile(it) },
                                    onEquipSlotItem = { slot, itemId ->
                                        repository.equipItemOnActiveProfile(slot, itemId)
                                    },
                                    onCustomizeBaseTraits = { hex, eye, smile ->
                                        repository.customizeBaseAppearanceOnActiveProfile(hex, eye, smile)
                                    }
                                )
                            }
                            LixoMainDestination.TROPHIES_AND_FRIENDS -> {
                                TrophiesAndFriendsScreen(
                                    activeProfile = activeProfile,
                                    achievements = achievements,
                                    friends = friends,
                                    catalogById = catalogById,
                                    onAddFriendByQuery = { repository.addFriendByQuery(it) },
                                    onRemoveFriend = { repository.removeFriend(it) }
                                )
                            }
                            LixoMainDestination.PARENT_SETTINGS -> {
                                ParentGateAndDashboardScreen(
                                    profiles = profiles,
                                    activeProfile = activeProfile,
                                    dailyActivity = dailyActivityByProfile[activeProfile.id].orEmpty(),
                                    struggledWords = struggledWordsByProfile[activeProfile.id].orEmpty(),
                                    mathErrors = mathErrorsByProfile[activeProfile.id].orEmpty(),
                                    friends = friends,
                                    privacySettings = privacySettings,
                                    mathEngine = mathEngine,
                                    onSelectProfile = { repository.selectProfile(it.id) },
                                    onUpdateProfileGoalsAndSettings = { repository.updateProfile(it) },
                                    onRemoveFriend = { repository.removeFriend(it) },
                                    onSendEmailSignInLink = { email ->
                                        repository.sendEmailSignInLink(email)
                                    },
                                    onCompleteEmailLinkSignIn = { link, emailOverride ->
                                        repository.completeEmailLinkSignIn(link, emailOverride)
                                    },
                                    onConnectOptionalEmailAccount = { email, pw ->
                                        repository.connectOptionalEmailAccount(email, pw)
                                    },
                                    onSwitchToLocalOnly = { repository.enableLocalOnlyMode() },
                                    onDeleteCloudAccount = { repository.deleteCloudAccountAndSwitchToLocalOnly() },
                                    onToggleCrashlyticsConsent = { repository.setCrashlyticsConsent(it) }
                                )
                            }
                            LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL -> Unit
                        }
                    }
                }
            }

            // Crashlytics Consent Dialog on 3rd App Launch (`project.md` §6:
            // "Firebase Crashlytics: Samtyckesdialog efter 3:e appstarten (stör inte första testsessionerna)")
            if (privacySettings.showCrashlyticsDialogNow) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(LixoColors.CardSurface)
                            .border(2.dp, LixoColors.StarGold, RoundedCornerShape(24.dp))
                            .padding(22.dp)
                    ) {
                        Text(
                            text = LixoStrings.CRASHLYTICS_DIALOG_TITLE,
                            style = MaterialTheme.typography.headlineMedium,
                            color = LixoColors.StarGold
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = LixoStrings.CRASHLYTICS_DIALOG_BODY,
                            style = MaterialTheme.typography.bodyLarge,
                            color = LixoColors.SoftTextWhite
                        )
                        Spacer(Modifier.height(18.dp))
                        BouncyButton(
                            onClick = { repository.setCrashlyticsConsent(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(LixoStrings.CRASHLYTICS_ACCEPT_BTN, color = Color.White)
                        }
                        Spacer(Modifier.height(10.dp))
                        BouncyButton(
                            onClick = { repository.setCrashlyticsConsent(false) },
                            containerColor = LixoColors.CardSurfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(LixoStrings.CRASHLYTICS_DECLINE_BTN, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
