package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.LIXO_ANDROID_MIN_VERSION
import se.lixo.app.data.remote.LIXO_ANDROID_PACKAGE_NAME
import se.lixo.app.data.remote.LIXO_EMAIL_LINK_CONTINUE_URL
import se.lixo.app.data.remote.LIXO_FIREBASE_HOSTING_DOMAIN
import se.lixo.app.data.remote.LIXO_IOS_BUNDLE_ID
import se.lixo.app.data.remote.SyncMergeEngine
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.PendingEmailLinkStorage
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.ui.components.avatar3d.Avatar3DController

class LixoEnginesTest {

    private val alignmentEngine = ForcedAlignmentEngine()
    private val mathEngine = MathEngine()
    private val progressionEngine = ProgressionAndGiftEngine()

    @Test
    fun forcedAlignment_matchesRealTimeWordsAndSupportsPhoneticVariations() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = true
        )
        assertEquals(5, session.words.size)
        assertTrue(session.isTimedChallenge)

        // Child reads with slight phonetic variation ("katen" instead of "katten", "sjarna" instead of "stjärna")
        val tokens = listOf(
            RecognizedSpeechToken("katen", "katen", 0.9f, 100L),
            RecognizedSpeechToken("Lixo", "lixo", 0.95f, 200L),
            RecognizedSpeechToken("hittar", "hittar", 0.95f, 300L),
            RecognizedSpeechToken("en", "en", 0.95f, 400L),
            RecognizedSpeechToken("stjärna", "stjärna", 0.95f, 500L)
        )
        session = alignmentEngine.processRecognizedTokens(session, tokens)
        assertTrue(session.isCompleted)

        // Fast reading -> 3 stars
        session = session.copy(elapsedMillis = 3_000L)
        val score = alignmentEngine.evaluateSessionScore(
            state = session,
            referenceWpm = 50,
            timeRequirementSeconds = 30
        )
        assertEquals(3, score.starsEarned)
        assertTrue(score.eligibleForGift)
        assertTrue(score.eligibleForPronunciationAchievement)
        assertTrue(score.eligibleForTimedChallengeReward)
    }

    @Test
    fun manualReadingMode_enforcesMinimumDurationAndOneStarCapAndNoGifts() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo läser i lugn takt hemma i soffan.",
            mode = ReadingMode.MANUAL,
            isTimedChallengeRequested = true // Must be disabled in manual mode
        )
        assertFalse(session.isTimedChallenge, "Timed challenges require AI mode")
        assertFalse(session.isManualCompleteButtonEnabled, "Button must be inactive before min read time")

        // Attempting early completion must not complete
        session = alignmentEngine.tryCompleteManualReading(session)
        assertFalse(session.isCompleted)

        // Advance time past minimum required duration
        session = alignmentEngine.onTick(
            state = session,
            deltaMillis = session.manualModeMinRequiredMillis + 500L,
            millisSinceLastWordAdvance = 0L
        )
        assertTrue(session.isManualCompleteButtonEnabled)

        session = alignmentEngine.tryCompleteManualReading(session)
        assertTrue(session.isCompleted)

        val score = alignmentEngine.evaluateSessionScore(
            state = session,
            referenceWpm = 40,
            timeRequirementSeconds = 60
        )
        assertEquals(1, score.starsEarned, "Manual mode is capped at 1 star")
        assertFalse(score.eligibleForGift, "Manual mode does not award gifts")
        assertFalse(score.eligibleForPronunciationAchievement)
        assertFalse(score.eligibleForTimedChallengeReward)
        assertTrue(score.struggledWordsToLog.isEmpty(), "Manual mode does not log struggled words")
    }

    @Test
    fun mathEngine_revealsGrowthMindsetHintsOnWrongAttempt() {
        val problem = MathProblem(
            id = "mp_clock_1",
            type = MathProblemType.CLOCK,
            promptSv = "Vad är klockan?",
            correctAnswer = "03:30",
            choices = listOf("03:00", "03:30"),
            hintStep1Sv = "Minutvisaren pekar på 6.",
            hintStep2Sv = "Det betyder 30 minuter över 3 (03:30)."
        )
        var state = MathProblemAttemptState(problem)

        val (afterWrong1, correct1) = mathEngine.checkAnswer(state, "03:00")
        assertFalse(correct1)
        assertEquals(1, afterWrong1.wrongAttempts)
        assertEquals(1, afterWrong1.hintsRevealed)
        assertEquals(1, afterWrong1.activeHints.size)

        val (afterCorrect, correct2) = mathEngine.checkAnswer(afterWrong1, "03.30")
        assertTrue(correct2)
        assertTrue(afterCorrect.isSolved)
    }

    @Test
    fun progressionEngine_preventsStarFarmingOnMaxedLevelsAndHandlesDuplicateGifts() {
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val profile = ChildProfile(id = "p1", displayName = "Saga", totalStars = 0)
        val level1 = CampaignLevel(
            id = "level_1",
            courseId = "course_sv_1",
            order = 1,
            title = "Bana 1",
            biomeNameSv = "Skogen",
            subject = Subject.SWEDISH,
            difficulty = 1,
            timeRequirementSeconds = 45,
            referenceWpm = 40,
            taskId = "task_1"
        )
        val today = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-11-01")

        // First completion with 3 stars -> +3 net stars
        val run1 = progressionEngine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = true,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = catalog.first().id,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        assertEquals(3, run1.netNewStarsAwarded)
        assertEquals(3, run1.updatedProfile.totalStars)
        assertNull(run1.giftOutcome, "First run is not a repetition replay")

        // Replay already maxed 3-star level -> 0 new stars, but awards a repetition gift roll!
        val run2 = progressionEngine.recordLevelCompletion(
            profile = run1.updatedProfile,
            level = level1,
            previousProgress = run1.updatedLevelProgress,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 19,
            achievedWpm = 65,
            completedTimedChallenge = true,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = catalog.first().id,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = catalog.map { it.id }.toSet(), // All items already unlocked -> forces duplicate
            todayActivityBeforeRun = today,
            nowEpochMillis = 2000L
        )
        assertEquals(0, run2.netNewStarsAwarded, "Replaying maxed level must not award extra stars")
        assertEquals(3, run2.updatedProfile.totalStars)
        assertNotNull(run2.giftOutcome)
        assertTrue(run2.giftOutcome.isDuplicate, "Duplicate gift roll does nothing")
        assertNull(run2.giftOutcome.newlyUnlockedItem)
    }

    @Test
    fun syncMergeEngine_combinesInventoryUnionBestStarsAndLastWriteWinsSettings() {
        val local = SyncMergeEngine.SyncableProfileSnapshot(
            profileId = "p1",
            unlockedItemIds = setOf("item_a", "item_b"),
            starsByLevelId = mapOf("level_1" to 3, "level_2" to 1),
            appearance = AvatarAppearance(avatarType = AvatarType.CAT),
            dailyReadingGoalMinutes = 15,
            dailyMathGoalProblems = 10,
            settingsUpdatedAtEpochMillis = 1000L
        )
        val remote = SyncMergeEngine.SyncableProfileSnapshot(
            profileId = "p1",
            unlockedItemIds = setOf("item_b", "item_c"),
            starsByLevelId = mapOf("level_1" to 2, "level_2" to 3, "level_3" to 2),
            appearance = AvatarAppearance(avatarType = AvatarType.KNIGHT),
            dailyReadingGoalMinutes = 25,
            dailyMathGoalProblems = 15,
            settingsUpdatedAtEpochMillis = 2000L // Newer timestamp wins for settings
        )

        val merged = SyncMergeEngine.mergeSnapshots(local, remote)
        assertEquals(setOf("item_a", "item_b", "item_c"), merged.unlockedItemIds)
        assertEquals(3, merged.starsByLevelId["level_1"])
        assertEquals(3, merged.starsByLevelId["level_2"])
        assertEquals(2, merged.starsByLevelId["level_3"])
        assertEquals(AvatarType.KNIGHT, merged.appearance.avatarType)
        assertEquals(25, merged.dailyReadingGoalMinutes)
    }

    @Test
    fun avatar3DController_computesProceduralIdlePose() {
        val pose0 = Avatar3DController.computeProceduralIdlePose(0.0f)
        val poseQuarter = Avatar3DController.computeProceduralIdlePose(0.3f, hasLegendaryEquipped = true)
        assertTrue(pose0.torsoBreathScaleY in 0.95f..1.05f)
        assertTrue(poseQuarter.tailWagAngleDeg in -20f..20f)
    }

    @Test
    fun emailLinkAuth_configuresActionCodeSettingsForAndroidAndIosAndCompletesSignIn() = runTest {
        PendingEmailLinkStorage.clearSavedEmail()
        EmailLinkAuthDispatcher.consumeIncomingLink()

        val syncService = FirebaseSyncService()
        val settings = syncService.buildEmailLinkActionCodeSettings()
        assertEquals(LIXO_EMAIL_LINK_CONTINUE_URL, settings.url)
        assertTrue(settings.canHandleCodeInApp)
        assertEquals(LIXO_ANDROID_PACKAGE_NAME, settings.androidPackageName?.packageName)
        assertEquals(true, settings.androidPackageName?.installIfNotAvailable)
        assertEquals(LIXO_ANDROID_MIN_VERSION, settings.androidPackageName?.minimumVersion)
        assertEquals(LIXO_IOS_BUNDLE_ID, settings.iOSBundleId)
        assertEquals(LIXO_FIREBASE_HOSTING_DOMAIN, settings.dynamicLinkDomain)

        val repo = LixoRepository(syncService)
        val sendResult = repo.sendEmailSignInLink("foralder@exempel.se")
        assertTrue(sendResult.isSuccess)
        assertEquals("foralder@exempel.se", PendingEmailLinkStorage.getSavedEmail())
        assertTrue(repo.privacySettings.value.emailLinkSent)

        val incomingLink =
            "https://lixo-fd45d.firebaseapp.com/__/auth/links?apiKey=test&mode=signIn&oobCode=123456"
        assertTrue(EmailLinkAuthDispatcher.onIncomingLink(incomingLink))
        assertEquals(incomingLink, EmailLinkAuthDispatcher.incomingLink.value)

        // Same-device sign-in uses locally saved email automatically
        val signInResult = repo.completeEmailLinkSignIn(incomingLink)
        assertTrue(signInResult.isSuccess)
        assertFalse(repo.privacySettings.value.isLocalOnlyMode)
        assertEquals("foralder@exempel.se", repo.privacySettings.value.optionalAccountEmail)
        assertNull(PendingEmailLinkStorage.clearSavedEmail().let { PendingEmailLinkStorage.getSavedEmail() })
        EmailLinkAuthDispatcher.consumeIncomingLink()
    }

    @Test
    fun syncCurriculumFromCloud_downloadsUpdatedStoriesMathCoursesAndDictionary() = runTest {
        val fakeGateway = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = listOf(
                Course(
                    id = "course_sv_1",
                    title = "Svenska Sagor: Viskande Skogen (Moln)",
                    subject = Subject.SWEDISH,
                    minGrade = 1,
                    version = 2,
                    updatedAt = 1793558400000L
                )
            )

            override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> = listOf(
                CampaignLevel(
                    id = "level_1",
                    courseId = "course_sv_1",
                    order = 1,
                    title = "Bana 1: Lixos Första Skogsstig",
                    biomeNameSv = "Viskande Skogen",
                    subject = Subject.SWEDISH,
                    difficulty = 1,
                    timeRequirementSeconds = 55,
                    referenceWpm = 40,
                    taskId = "task_1"
                )
            )

            override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = listOf(
                HomeworkTask(
                    id = "task_1",
                    levelId = "level_1",
                    type = TaskType.READING,
                    subject = Subject.SWEDISH,
                    textContent = "Katten Lixo smyger över den gamla träbron i skogen.",
                    rewardItemId = "item_hat_starter_explorer"
                )
            )

            override suspend fun fetchRemoteDictionary(): List<DictionaryEntry> = listOf(
                DictionaryEntry(
                    wordNormalized = "månfyren",
                    displayWord = "månfyren",
                    syllables = "mån-fy-ren",
                    explanationSv = "Ett fyrtorn som lyser som månsken.",
                    exampleSentenceSv = "Månfyren syntes långt ute över havet."
                )
            )
        }

        val repo = LixoRepository(fakeGateway)
        val synced = repo.syncCurriculumFromCloud()
        assertTrue(synced)
        assertEquals(
            "Svenska Sagor: Viskande Skogen (Moln)",
            repo.courses.value.first { it.id == "course_sv_1" }.title
        )
        assertEquals("mån-fy-ren", repo.lookupDictionaryWord("månfyren").syllables)
    }
}
