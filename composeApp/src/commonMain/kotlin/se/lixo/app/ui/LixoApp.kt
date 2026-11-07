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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RunFacts
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.PlatformFeatureFlags
import se.lixo.app.platform.createPlatformSpeechRecognitionEngine
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.screens.onboarding.FirstProfileSetupScreen
import se.lixo.app.ui.screens.onboarding.ProfileSelectAndTutorialScreen
import se.lixo.app.ui.screens.parent.ParentGateAndDashboardScreen
import se.lixo.app.ui.screens.reading.ReadingActivityScreen
import se.lixo.app.ui.screens.social.AvatarMapScreen
import se.lixo.app.ui.screens.social.TrophiesScreen
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoBottomNavBar
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoNavItem
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

enum class LixoMainDestination {
    PROFILE_SELECT_AND_TUTORIAL,
    MAP_HOME,
    AVATAR_MAP,
    WARDROBE,
    TROPHIES,
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
    val friendsByProfile by repository.friendsByProfile.collectAsState()
    val privacySettings by repository.privacySettings.collectAsState()
    val incomingEmailLink by EmailLinkAuthDispatcher.incomingLink.collectAsState()
    val profilesLoaded by repository.profilesLoaded.collectAsState()

    LaunchedEffect(Unit) {
        repository.loadCachedLexicon()
        repository.syncCurriculumFromCloud()
    }

    LaunchedEffect(incomingEmailLink) {
        val link = incomingEmailLink
        if (!link.isNullOrBlank()) {
            repository.completeEmailLinkSignIn(link)
            EmailLinkAuthDispatcher.consumeIncomingLink()
        }
    }

    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()
    val catalogById = remember(wardrobeCatalog) { wardrobeCatalog.associateBy { it.id } }
    val ttsAvailable by ttsEngine.isAvailable.collectAsState()
    val showSwedishTts = featureFlags.isSwedishTtsSupported && ttsAvailable

    var currentDestination by remember {
        mutableStateOf(LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL)
    }
    var activeGameplayLevel by remember { mutableStateOf<CampaignLevel?>(null) }
    var autoStartTutorialForProfileId by remember { mutableStateOf<String?>(null) }

    // Selected subject "world" on the campaign map (plan: "Ämnesväljare med tre världar").
    // Survives tab switches/rotation; after finishing a level the child returns to the same world.
    val subjectSaver = remember { Saver<Subject, String>(save = { it.name }, restore = { Subject.valueOf(it) }) }
    var selectedWorld by rememberSaveable(stateSaver = subjectSaver) { mutableStateOf(Subject.SWEDISH) }

    // System back: close level -> back to map -> back to profile selection -> (system exits).
    PlatformBackHandler(
        enabled = activeGameplayLevel != null ||
            currentDestination != LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
    ) {
        when {
            activeGameplayLevel != null -> activeGameplayLevel = null
            currentDestination == LixoMainDestination.MAP_HOME ->
                currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
            currentDestination == LixoMainDestination.WARDROBE ->
                currentDestination = LixoMainDestination.AVATAR_MAP
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

            if (!profilesLoaded) {
                // Profiles are still loading from Room: show just the sky background (no white
                // flash, no setup-screen flash) instead of deciding too early that there is no
                // active profile.
                Box(Modifier.fillMaxSize())
            } else if (activeProfile == null) {
                FirstProfileSetupScreen(
                    ttsEngine = ttsEngine,
                    showSwedishTtsButtons = showSwedishTts,
                    onCreateProfile = { name, age ->
                        val created = repository.createProfile(name, age)
                        autoStartTutorialForProfileId = created.id
                        activeGameplayLevel = null
                        currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
                    }
                )
            } else if (playingLevel != null && playingTask != null) {
                // Fullscreen Homework Activity View (Reading, Math, or English)
                if (playingTask.type == TaskType.READING) {
                    ReadingActivityScreen(
                        level = playingLevel,
                        task = playingTask,
                        alignmentEngine = alignmentEngine,
                        sttEngine = sttEngine,
                        ttsEngine = ttsEngine,
                        showSwedishTtsButtons = showSwedishTts,
                        showDebugSimulationControls = featureFlags.isDebugBuild,
                        lookupDictionary = { repository.lookupDictionaryWord(it) },
                        onFinishReadingSession = { scoreResult, elapsedSeconds ->
                            scoreResult.struggledWordsToLog.forEach { word ->
                                repository.logStruggledWord(activeProfile.id, word, playingLevel.id)
                            }
                            val todayIso = repository.currentLocalDateIso()
                            val prevProgress = levelProgressByProfile[activeProfile.id]?.get(playingLevel.id)
                            val clearedLevelsCountBeforeRun = levelProgressByProfile[activeProfile.id].orEmpty().size
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
                                clearedLevelsCountBeforeRun = clearedLevelsCountBeforeRun,
                                allWardrobeItems = wardrobeCatalog,
                                currentlyUnlockedItemIds = unlockedItemIds,
                                todayActivityBeforeRun = todayRecord,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                achievementDefinitions = achievements,
                                runFacts = RunFacts(
                                    subject = playingLevel.subject,
                                    levelId = playingLevel.id,
                                    isAiMode = scoreResult.eligibleForGift,
                                    achievedWpm = scoreResult.achievedWpm,
                                    referenceWpm = scoreResult.referenceWpm,
                                    pronunciationEligible = scoreResult.eligibleForPronunciationAchievement
                                )
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
                        onEquipItem = { item -> repository.equipItemOnActiveProfile(item.slot, item.id) },
                        onSelectAvatar = { repository.selectAvatarTypeOnActiveProfile(it) },
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
                            val clearedLevelsCountBeforeRun = levelProgressByProfile[activeProfile.id].orEmpty().size
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
                                clearedLevelsCountBeforeRun = clearedLevelsCountBeforeRun,
                                allWardrobeItems = wardrobeCatalog,
                                currentlyUnlockedItemIds = unlockedItemIds,
                                todayActivityBeforeRun = todayRecord,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                achievementDefinitions = achievements,
                                runFacts = RunFacts(
                                    subject = playingLevel.subject,
                                    levelId = playingLevel.id,
                                    isAiMode = true,
                                    mathFirstTryByType = scoreResult.firstTryCorrectByType
                                )
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
                        onEquipItem = { item -> repository.equipItemOnActiveProfile(item.slot, item.id) },
                        onSelectAvatar = { repository.selectAvatarTypeOnActiveProfile(it) },
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
                    onSelectProfile = { autoStartTutorialForProfileId = null; repository.selectProfile(it.id) },
                    onCreateSiblingProfile = { name -> repository.createProfile(name, BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) },
                    startTutorialImmediately = autoStartTutorialForProfileId == activeProfile.id,
                    onTutorialCompleted = { profile ->
                        autoStartTutorialForProfileId = null
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
                        LixoBottomNavBar(
                            items = listOf(
                                LixoNavItem(
                                    icon = LixoIcons.Home,
                                    label = LixoStrings.NAV_MAP,
                                    selected = currentDestination == LixoMainDestination.MAP_HOME,
                                    onClick = { currentDestination = LixoMainDestination.MAP_HOME }
                                ),
                                LixoNavItem(
                                    icon = LixoIcons.Friends,
                                    label = LixoStrings.NAV_FRIENDS,
                                    selected = currentDestination == LixoMainDestination.AVATAR_MAP ||
                                        currentDestination == LixoMainDestination.WARDROBE,
                                    onClick = { currentDestination = LixoMainDestination.AVATAR_MAP }
                                ),
                                LixoNavItem(
                                    icon = LixoIcons.Trophy,
                                    label = LixoStrings.NAV_TROPHIES,
                                    selected = currentDestination == LixoMainDestination.TROPHIES,
                                    onClick = { currentDestination = LixoMainDestination.TROPHIES }
                                ),
                                LixoNavItem(
                                    icon = LixoIcons.Shield,
                                    label = LixoStrings.NAV_PARENT_SETTINGS,
                                    selected = currentDestination == LixoMainDestination.PARENT_SETTINGS,
                                    onClick = { currentDestination = LixoMainDestination.PARENT_SETTINGS }
                                )
                            )
                        )
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
                                    selectedSubject = selectedWorld,
                                    onSelectSubject = { selectedWorld = it },
                                    onSelectLevel = { activeGameplayLevel = it },
                                    onSwitchProfileClick = {
                                        currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
                                    }
                                )
                            }
                            LixoMainDestination.AVATAR_MAP -> {
                                AvatarMapScreen(
                                    profile = activeProfile,
                                    friends = friendsByProfile[activeProfile.id].orEmpty(),
                                    catalogById = catalogById,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    onOpenWardrobe = { currentDestination = LixoMainDestination.WARDROBE },
                                    onAddFriendByQuery = { repository.addFriendByQuery(it) },
                                    onRemoveFriend = { repository.removeFriend(it) }
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
                                    },
                                    onBack = { currentDestination = LixoMainDestination.AVATAR_MAP }
                                )
                            }
                            LixoMainDestination.TROPHIES -> {
                                TrophiesScreen(achievements = AchievementEngine.withProfileProgress(achievements, activeProfile))
                            }
                            LixoMainDestination.PARENT_SETTINGS -> {
                                ParentGateAndDashboardScreen(
                                    profiles = profiles,
                                    activeProfile = activeProfile,
                                    dailyActivity = dailyActivityByProfile[activeProfile.id].orEmpty(),
                                    struggledWords = struggledWordsByProfile[activeProfile.id].orEmpty(),
                                    mathErrors = mathErrorsByProfile[activeProfile.id].orEmpty(),
                                    friends = friendsByProfile[activeProfile.id].orEmpty(),
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
                        .background(LixoColors.Scrim)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 24.dp,
                        contentPadding = PaddingValues(24.dp)
                    ) {
                        Column {
                            Text(
                                text = LixoStrings.CRASHLYTICS_DIALOG_TITLE,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LixoColors.TextPrimary
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = LixoStrings.CRASHLYTICS_DIALOG_BODY,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LixoColors.TextSecondary
                            )
                            Spacer(Modifier.height(20.dp))
                            LixoButton(
                                onClick = { repository.setCrashlyticsConsent(true) },
                                variant = LixoButtonVariant.PRIMARY,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.CRASHLYTICS_ACCEPT_BTN,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LixoButton(
                                onClick = { repository.setCrashlyticsConsent(false) },
                                variant = LixoButtonVariant.GHOST,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = LixoStrings.CRASHLYTICS_DECLINE_BTN,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LixoColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
