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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import se.lixo.app.data.local.createPlatformContentPackCache
import se.lixo.app.data.local.decodeProfileBackup
import se.lixo.app.data.local.encodeProfileBackup
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RunFacts
import se.lixo.app.domain.engine.TrustedClock
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.EMAIL_BACKUP_FEATURE_ENABLED
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.platform.PlatformBackHandler
import se.lixo.app.platform.PlatformFeatureFlags
import se.lixo.app.platform.createPlatformProfileFilePicker
import se.lixo.app.platform.createPlatformQrScanner
import se.lixo.app.platform.createPlatformSpeechRecognitionEngine
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.platform.createPlatformTrustedClockStore
import se.lixo.app.platform.isReducedMotionEnabled
import se.lixo.app.ui.feedback.LocalReducedMotion
import se.lixo.app.ui.screens.debug.ContentPreviewRoute
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
    PARENT_SETTINGS,
    CONTENT_PREVIEW
}

/**
 * Loads the bundled content pack and the repository before showing anything else. Used by
 * `MainActivity`/`MainViewController`. Shows a plain sky-blue frame while loading.
 */
@Composable
fun LixoApp() {
    val repository by produceState<LixoRepository?>(null) {
        val gateway = FirebaseSyncService()
        value = LixoRepository(
            cloudGateway = gateway,
            content = ContentRepository.loadInitial(
                gateway = gateway,
                cache = createPlatformContentPackCache(),
                trustedClock = TrustedClock(store = createPlatformTrustedClockStore())
            )
        )
    }
    val currentRepository = repository
    if (currentRepository == null) {
        LixoTheme {
            Box(Modifier.fillMaxSize().background(LixoColors.SkyBackgroundBottom))
        }
    } else {
        LixoApp(repository = currentRepository)
    }
}

/**
 * Root Compose Multiplatform application wiring all screens, engines, platform `expect/actual`
 * services, and 3rd-launch Crashlytics consent dialog (`project.md` §4 & §6).
 */
@Composable
fun LixoApp(
    repository: LixoRepository
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
    val content by repository.content.active.collectAsState()
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

    val contentSyncScope = rememberCoroutineScope()
    val trustedClock = repository.content.trustedClock

    // Holiday-special events (025): recomputed on startup sync, on resume, and at local midnight
    // so a running event appears/disappears without an app restart.
    var eventClockTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        launch {
            repository.content.sync()
            eventClockTick++
        }
        launch { repository.ensureCloudIdentity() }
        repository.loadCachedLexicon()
        repository.syncCurriculumFromCloud()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        contentSyncScope.launch { repository.content.syncIfDue() }
        eventClockTick++
    }

    LaunchedEffect(eventClockTick) {
        delay(EventEngine.millisUntilNextLocalMidnight(trustedClock.now(), TimeZone.currentSystemDefault()))
        eventClockTick++
    }

    val activeEvents = remember(content, eventClockTick) {
        EventEngine.activeEvents(content, trustedClock.now(), TimeZone.currentSystemDefault())
    }

    // doc/plans/030 review L2: completing an email-link sign-in replaces the Firebase Anonymous
    // Auth user every friend card is keyed to -- gated behind the same flag as the (already
    // hidden) email/account UI in ParentGateAndDashboardScreen, since this https deep link path
    // stayed live and reachable even with that UI's entry point hidden.
    LaunchedEffect(incomingEmailLink) {
        val link = incomingEmailLink
        if (!link.isNullOrBlank()) {
            if (EMAIL_BACKUP_FEATURE_ENABLED) {
                repository.completeEmailLinkSignIn(link)
            }
            EmailLinkAuthDispatcher.consumeIncomingLink()
        }
    }

    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()
    val ttsAvailable by ttsEngine.isAvailable.collectAsState()
    val showSwedishTts = featureFlags.isSwedishTtsSupported && ttsAvailable

    var currentDestination by remember {
        mutableStateOf(LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL)
    }
    var activeGameplayLevel by remember { mutableStateOf<CampaignLevel?>(null) }
    var autoStartTutorialForProfileId by remember { mutableStateOf<String?>(null) }

    // doc/plans/030 §7: the Google code scanner, injected into AvatarMapScreen as a plain
    // constructor parameter (not resolved from a singleton inside the screen) so instrumented
    // tests can pass a `FakeQrScanner` instead.
    val qrScanner = remember { createPlatformQrScanner() }

    // doc/plans/045: the system "save/open a file at a user-picked location" picker, injected
    // into ParentGateAndDashboardScreen as a plain constructor parameter (same pattern as
    // qrScanner above) so instrumented tests can pass an in-memory fake instead.
    val profileFilePicker = remember { createPlatformProfileFilePicker() }

    // doc/plans/030 §7: `lixo://friend/<code>` only routes to the Avatar tab with the code
    // prefilled into "Lägg till kompis" -- nothing is ever redeemed automatically.
    val incomingFriendLink by FriendDeepLinkDispatcher.incomingFriendCode.collectAsState()
    var pendingFriendDeepLinkCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incomingFriendLink) {
        val link = incomingFriendLink
        if (!link.isNullOrBlank()) {
            pendingFriendDeepLinkCode = link.substringAfterLast("/")
            currentDestination = LixoMainDestination.AVATAR_MAP
        }
    }

    // doc/plans/030 §4: pick up friends added while the Avatar tab was closed (new readers of my
    // own card, or a friend whose card got deleted) every time the tab is opened.
    LaunchedEffect(currentDestination, activeProfileId) {
        if (currentDestination == LixoMainDestination.AVATAR_MAP) {
            // doc/plans/030 review H4/H5: belt-and-suspenders -- FriendCloud/FirebaseSyncService
            // itself must never throw out of observeCard/observeMyReaders, but this call site
            // still must not crash the UI if that contract is ever broken by a future change.
            runCatching { repository.refreshFriends() }
        }
    }

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
            currentDestination == LixoMainDestination.CONTENT_PREVIEW ->
                currentDestination = LixoMainDestination.AVATAR_MAP
            else -> currentDestination = LixoMainDestination.MAP_HOME
        }
    }

    // doc/plans/030 §7: provided once at the root (not resolved per-composable) so every
    // celebration stage, including FriendRevealStage, reads the system "remove animations"
    // setting via LocalReducedMotion instead of each wiring its own platform check.
    val reducedMotion = remember { isReducedMotionEnabled() }

    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
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
                // Freeze the content pack for this run (doc/plans/023): a mid-run hot swap (024)
                // must not change this run's outcome or celebration.
                val runContent = remember(playingLevel.id, playingTask.id) { repository.content.active.value }
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
                                clearedLevelsCountBeforeRun = clearedLevelsCountBeforeRun,
                                content = runContent,
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
                                ),
                                events = EventEngine.snapshot(runContent, trustedClock.now(), TimeZone.currentSystemDefault())
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
                        onSelectAvatar = { repository.selectAvatarOnActiveProfile(it) },
                        onCloseToMap = { activeGameplayLevel = null },
                        content = runContent
                    )
                } else {
                    MathActivityScreen(
                        content = runContent,
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
                                clearedLevelsCountBeforeRun = clearedLevelsCountBeforeRun,
                                content = runContent,
                                currentlyUnlockedItemIds = unlockedItemIds,
                                todayActivityBeforeRun = todayRecord,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                achievementDefinitions = achievements,
                                runFacts = RunFacts(
                                    subject = playingLevel.subject,
                                    levelId = playingLevel.id,
                                    isAiMode = true,
                                    mathFirstTryByType = scoreResult.firstTryCorrectByType
                                ),
                                events = EventEngine.snapshot(runContent, trustedClock.now(), TimeZone.currentSystemDefault())
                            )
                            repository.updateProfile(outcome.updatedProfile)
                            repository.saveLevelProgress(outcome.updatedLevelProgress)
                            outcome.newlyUnlockedItems.forEach { repository.unlockWardrobeItem(it.id) }
                            repository.saveDailyActivity(
                                if (playingLevel.subject == Subject.GEOGRAPHY) {
                                    todayRecord.copy(
                                        geographyTasksCompleted = todayRecord.geographyTasksCompleted + 1
                                    )
                                } else {
                                    todayRecord.copy(
                                        mathTasksCompleted = todayRecord.mathTasksCompleted + 1,
                                        mathProblemsCompleted = todayRecord.mathProblemsCompleted + scoreResult.totalProblems
                                    )
                                }
                            )
                            outcome
                        },
                        onEquipItem = { item -> repository.equipItemOnActiveProfile(item.slot, item.id) },
                        onSelectAvatar = { repository.selectAvatarOnActiveProfile(it) },
                        onCloseToMap = { activeGameplayLevel = null }
                    )
                }
            } else if (currentDestination == LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL) {
                ProfileSelectAndTutorialScreen(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    content = content,
                    sttEngine = sttEngine,
                    ttsEngine = ttsEngine,
                    showSwedishTtsButtons = showSwedishTts,
                    onSelectProfile = { autoStartTutorialForProfileId = null; repository.selectProfile(it.id) },
                    onCreateSiblingProfile = { name -> repository.createProfile(name, BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) },
                    startTutorialImmediately = autoStartTutorialForProfileId == activeProfile.id,
                    onTutorialCompleted = { profile ->
                        autoStartTutorialForProfileId = null
                        val tutorialRewardItemId = content.dropRules.tutorialRewardItemId
                        repository.unlockWardrobeItem(tutorialRewardItemId)
                        val updated = profile.copy(
                            tutorialCompleted = true,
                            appearance = profile.appearance.copy(
                                equipped = profile.appearance.equipped +
                                    (EquipmentSlot.HAT to tutorialRewardItemId)
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
                                    icon = LixoIcons.LearnBook,
                                    label = LixoStrings.NAV_LEARN,
                                    selected = currentDestination == LixoMainDestination.MAP_HOME,
                                    onClick = { currentDestination = LixoMainDestination.MAP_HOME }
                                ),
                                LixoNavItem(
                                    icon = LixoIcons.Friends,
                                    label = LixoStrings.NAV_AVATAR,
                                    selected = currentDestination == LixoMainDestination.AVATAR_MAP ||
                                        currentDestination == LixoMainDestination.WARDROBE ||
                                        currentDestination == LixoMainDestination.CONTENT_PREVIEW,
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
                                    content = content,
                                    progressionEngine = progressionEngine,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    selectedSubject = selectedWorld,
                                    onSelectSubject = { selectedWorld = it },
                                    onSelectLevel = { activeGameplayLevel = it },
                                    onSwitchProfileClick = {
                                        currentDestination = LixoMainDestination.PROFILE_SELECT_AND_TUTORIAL
                                    },
                                    activeEvents = activeEvents,
                                    unlockedItemIds = unlockedItemIds
                                )
                            }
                            LixoMainDestination.AVATAR_MAP -> {
                                // doc/plans/030 review M6: `repository.unrevealedFriends(id)` /
                                // `observeFriendCards(id)` build a fresh Flow/lambda every
                                // recomposition; passed straight into `collectAsState`/a
                                // `LaunchedEffect` inside AvatarMapScreen, a changed Flow
                                // IDENTITY restarts that collection on every unrelated
                                // recomposition of LixoApp, which could remount an in-progress
                                // "Ny kompis!" reveal. `remember(activeProfile.id)` keeps the
                                // same Flow/lambda instance across recompositions.
                                val unrevealedFriendsFlow = remember(activeProfile.id) {
                                    repository.unrevealedFriends(activeProfile.id)
                                }
                                val observeFriendCardsForProfile = remember(activeProfile.id) {
                                    { repository.observeFriendCards(activeProfile.id) }
                                }
                                AvatarMapScreen(
                                    profile = activeProfile,
                                    friends = friendsByProfile[activeProfile.id].orEmpty(),
                                    content = content,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    qrScanner = qrScanner,
                                    nowMillis = { repository.nowMillis() },
                                    prefilledFriendCode = pendingFriendDeepLinkCode,
                                    onConsumePrefilledFriendCode = {
                                        pendingFriendDeepLinkCode = null
                                        FriendDeepLinkDispatcher.consumeIncomingFriendCode()
                                    },
                                    onOpenWardrobe = { currentDestination = LixoMainDestination.WARDROBE },
                                    onCreateFriendCode = { repository.createFriendCode() },
                                    onStopFriendCode = { repository.stopFriendCode() },
                                    observeNewFriendsDuring = { repository.observeNewFriendsDuring(it) },
                                    onAddFriendByCode = { raw, fromQr -> repository.addFriendByCode(raw, fromQr) },
                                    onRemoveFriend = { repository.removeFriend(it) },
                                    onSetFriendLabel = { uid, label -> repository.setFriendLabel(uid, label) },
                                    unrevealedFriends = unrevealedFriendsFlow,
                                    onMarkRevealed = { repository.markRevealed(activeProfile.id, it) },
                                    observeFriendCards = observeFriendCardsForProfile,
                                    onOpenContentPreview = if (featureFlags.isDebugBuild) {
                                        { currentDestination = LixoMainDestination.CONTENT_PREVIEW }
                                    } else {
                                        null
                                    }
                                )
                            }
                            LixoMainDestination.WARDROBE -> {
                                WardrobeScreen(
                                    profile = activeProfile,
                                    content = content,
                                    unlockedItemIds = unlockedItemIds,
                                    ttsEngine = ttsEngine,
                                    showSwedishTtsButtons = showSwedishTts,
                                    onSelectAvatar = { repository.selectAvatarOnActiveProfile(it) },
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
                                TrophiesScreen(achievements = AchievementEngine.withProfileProgress(achievements, activeProfile), ttsEngine = ttsEngine)
                            }
                            LixoMainDestination.PARENT_SETTINGS -> {
                                ParentGateAndDashboardScreen(
                                    profiles = profiles,
                                    activeProfile = activeProfile,
                                    content = content,
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
                                    onToggleCrashlyticsConsent = { repository.setCrashlyticsConsent(it) },
                                    filePicker = profileFilePicker,
                                    onBuildBackup = {
                                        repository.buildProfileBackup(
                                            repository.activeProfileId.value ?: activeProfile.id,
                                            featureFlags.appVersionCode.takeIf { it > 0 }
                                        )?.let(::encodeProfileBackup)
                                    },
                                    onPrepareImport = ::decodeProfileBackup,
                                    onImportBackup = { backup, mode -> repository.importProfileBackup(backup, mode) },
                                    backupConflicts = repository::backupConflictsWithExisting
                                )
                            }
                            LixoMainDestination.CONTENT_PREVIEW -> {
                                ContentPreviewRoute(
                                    onBack = { currentDestination = LixoMainDestination.AVATAR_MAP }
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
}
