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
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.LevelProgress
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
    fun forcedAlignment_lookaheadSkipFlagsWordYellowButKeepsItUnreadAndAdvancesCursor() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna i skogen.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )

        // Child reads "Katten" then skips straight to "hittar" (within the lookahead window),
        // missing "Lixo" entirely.
        val tokens = listOf(
            RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
            RecognizedSpeechToken("hittar", "hittar", 0.95f, 200L)
        )
        session = alignmentEngine.processRecognizedTokens(session, tokens)

        val skippedWord = session.words[1]
        assertEquals("lixo", skippedWord.normalizedText)
        assertTrue(skippedWord.needsPractice, "Skipped word must be flagged yellow")
        assertFalse(skippedWord.isCompleted, "Skipped word must stay unread, not completed")
        assertTrue(session.words[0].isCompleted)
        assertTrue(session.words[2].isCompleted)
        assertFalse(session.isCompleted, "Story cannot complete while a yellow word is unread")
        // Cursor moves on to the next uncompleted word ("en"), never blocking the child.
        assertEquals(3, session.currentWordIndex)
        assertTrue(session.struggledWordsNormalized.contains("lixo"))
    }

    @Test
    fun forcedAlignment_currentCursorWordTakesPriorityOverEarlierYellowRepeatedWord() {
        var session = alignmentEngine.startSession(
            storyText = "en katt och en hund",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        assertEquals(5, session.words.size)

        // Child skips the first "en" (idx 0) straight to "katt" (idx 1), within the lookahead window,
        // which flags "en" (idx 0) yellow/unread. They then continue reading normally through "och"
        // and the second "en" (idx 3).
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katt", "katt", 0.95f, 100L),
                RecognizedSpeechToken("och", "och", 0.95f, 200L),
                RecognizedSpeechToken("en", "en", 0.95f, 300L)
            )
        )

        // "en" (idx 0) was skipped over and flagged yellow, but stays unread.
        assertTrue(session.words[0].needsPractice, "Skipped repeated word must be flagged yellow")
        assertFalse(session.words[0].isCompleted, "Skipped repeated word must stay unread")
        assertTrue(session.words[1].isCompleted) // "katt"
        assertTrue(session.words[2].isCompleted) // "och"

        // The second "en" (idx 3), which is the actual current cursor word, must be the one credited -
        // not the earlier yellow "en" (idx 0) - even though both normalize identically.
        assertTrue(session.words[3].isCompleted, "The current cursor word must be matched first")
        assertFalse(session.words[3].needsPractice, "The cursor word was read correctly, not flagged")
        assertFalse(session.isCompleted, "idx 0 is still unread yellow, so the story isn't done")

        // Going back and reading the earlier yellow "en" (idx 0) now completes it too.
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("en", "en", 0.95f, 400L))
        )
        assertTrue(session.words[0].isCompleted, "Reading the earlier yellow word later completes it")
        assertTrue(session.words[0].needsPractice, "Word stays flagged for review even once read")
        assertTrue(session.struggledWordsNormalized.contains("en"))
    }

    @Test
    fun forcedAlignment_reachingEndWithUnreadYellowWordAwaitsThenCompletesWhenReadLater() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )

        // Skip "Lixo" early, then read everything else through to the end.
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("hittar", "hittar", 0.95f, 200L),
                RecognizedSpeechToken("en", "en", 0.95f, 300L),
                RecognizedSpeechToken("stjärna", "stjärna", 0.95f, 400L)
            )
        )
        assertFalse(session.isCompleted)
        assertTrue(session.isAwaitingUnreadWords, "Reaching the end with a yellow word unread must await it")
        assertEquals(5, session.currentWordIndex)

        // Going back and reading the flagged yellow word later completes the story.
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("lixo", "lixo", 0.95f, 500L))
        )
        assertTrue(session.isCompleted)
        assertFalse(session.isAwaitingUnreadWords)
        assertTrue(session.words[1].isCompleted)
        assertTrue(session.words[1].needsPractice, "Word stays flagged for review even once read")
    }

    @Test
    fun forcedAlignment_hesitationFlagsCurrentWordWithoutBlockingAndKeepsFlagAfterMatch() {
        var session = alignmentEngine.startSession(
            storyText = "Katten springer snabbt.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )

        session = alignmentEngine.onTick(
            state = session,
            deltaMillis = BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS,
            millisSinceLastWordAdvance = BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS
        )
        assertTrue(session.isWordStuckSupportVisible)
        assertTrue(session.words[0].needsPractice, "Hesitation flags the current word yellow")
        assertFalse(session.words[0].isCompleted, "Hesitation never completes/skips the word")

        // The child then reads the word correctly; it completes but keeps the practice flag.
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("katten", "katten", 0.95f, 1000L))
        )
        assertTrue(session.words[0].isCompleted)
        assertTrue(session.words[0].needsPractice)
    }

    @Test
    fun forcedAlignment_pronunciationAchievementFalseWhenAnyWordNeedsPractice() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("hittar", "hittar", 0.95f, 200L),
                RecognizedSpeechToken("en", "en", 0.95f, 300L),
                RecognizedSpeechToken("stjärna", "stjärna", 0.95f, 400L)
            )
        )
        // Finish by reading the flagged word too, so the session actually completes.
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("lixo", "lixo", 0.95f, 500L))
        )
        assertTrue(session.isCompleted)
        session = session.copy(elapsedMillis = 3_000L)

        val score = alignmentEngine.evaluateSessionScore(
            state = session,
            referenceWpm = 50,
            timeRequirementSeconds = 30
        )
        assertFalse(
            score.eligibleForPronunciationAchievement,
            "A word that was ever flagged needsPractice blocks the pronunciation achievement"
        )
    }

    @Test
    fun progressionEngine_timedChallengeRewardAndReplayLoweringBestTimeWithNoExtraStarsAtMax() {
        val level = CampaignLevel(
            id = "level_timed",
            courseId = "course_sv_1",
            order = 1,
            title = "Bana Tid",
            biomeNameSv = "Skogen",
            subject = Subject.SWEDISH,
            difficulty = 1,
            timeRequirementSeconds = 30,
            referenceWpm = 40,
            taskId = "task_timed"
        )
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = true
        )
        assertTrue(session.isTimedChallenge)
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("lixo", "lixo", 0.95f, 200L),
                RecognizedSpeechToken("hittar", "hittar", 0.95f, 300L),
                RecognizedSpeechToken("en", "en", 0.95f, 400L),
                RecognizedSpeechToken("stjärna", "stjärna", 0.95f, 500L)
            )
        )
        session = session.copy(elapsedMillis = 7_000L) // fast enough for 3 stars and under the 30s target
        val score = alignmentEngine.evaluateSessionScore(session, referenceWpm = 40, timeRequirementSeconds = 30)
        assertEquals(3, score.starsEarned)
        assertTrue(score.eligibleForTimedChallengeReward)

        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val profile = ChildProfile(id = "p_timed", displayName = "Saga", totalStars = 0)
        val today = DailyActivityRecord(profileId = "p_timed", calendarDateIso = "2026-11-01")

        val firstRun = progressionEngine.recordLevelCompletion(
            profile = profile,
            level = level,
            previousProgress = null,
            earnedStarsThisRun = score.starsEarned,
            completedInAiMode = true,
            elapsedSeconds = 7,
            achievedWpm = score.achievedWpm,
            completedTimedChallenge = score.eligibleForTimedChallengeReward,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1_000L
        )
        assertEquals(3, firstRun.updatedLevelProgress.starsEarned)
        assertEquals(7, firstRun.updatedLevelProgress.bestTimeSeconds)

        // Replay, already at 3 stars, but faster -> 0 net new stars, lower bestTimeSeconds.
        val replay = progressionEngine.recordLevelCompletion(
            profile = firstRun.updatedProfile,
            level = level,
            previousProgress = firstRun.updatedLevelProgress,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 5,
            achievedWpm = score.achievedWpm,
            completedTimedChallenge = true,
            eligibleForRepetitionGift = true,
            rewardItemIdForTask = null,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 2_000L
        )
        assertEquals(0, replay.netNewStarsAwarded, "Already-maxed level gives no extra stars for a faster replay")
        assertEquals(5, replay.updatedLevelProgress.bestTimeSeconds, "Faster replay still lowers the best time")
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
    fun progressionEngine_perSubjectUnlock_firstMathLevelUnlockedWithoutAnyProgress() {
        // order%5==0 -> ENGLISH, even order -> MATH, else SWEDISH (Repositories.kt level generation).
        // The first MATH level is order 2, with no SWEDISH level (order 1) progress required.
        val levels = listOf(
            level(order = 1, subject = Subject.SWEDISH),
            level(order = 2, subject = Subject.MATH),
            level(order = 3, subject = Subject.SWEDISH),
            level(order = 4, subject = Subject.MATH)
        )
        assertTrue(
            progressionEngine.isLevelUnlocked(levelOrder = 2, progressByLevelId = emptyMap(), levels = levels),
            "First level of the MATH world must be unlocked even with zero progress anywhere"
        )
    }

    @Test
    fun progressionEngine_perSubjectUnlock_secondMathLevelRequiresStarOnFirstMathLevelNotOrderMinusOne() {
        val levels = listOf(
            level(order = 1, subject = Subject.SWEDISH),
            level(order = 2, subject = Subject.MATH),
            level(order = 3, subject = Subject.SWEDISH),
            level(order = 4, subject = Subject.MATH)
        )

        // Order 3 (SWEDISH) is 3-starred, but MATH order 4 must NOT unlock from that -
        // it needs a star on MATH order 2 specifically.
        val progressWithOnlySwedishStarred = mapOf(
            levels[2].id to LevelProgress(
                profileId = "p1",
                levelId = levels[2].id,
                levelOrder = 3,
                starsEarned = 3
            , completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40)
        )
        assertFalse(
            progressionEngine.isLevelUnlocked(
                levelOrder = 4,
                progressByLevelId = progressWithOnlySwedishStarred,
                levels = levels
            ),
            "MATH order 4 must require a star on MATH order 2, not on SWEDISH order 3"
        )

        val progressWithMathStarred = mapOf(
            levels[1].id to LevelProgress(
                profileId = "p1",
                levelId = levels[1].id,
                levelOrder = 2,
                starsEarned = 1
            , completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40)
        )
        assertTrue(
            progressionEngine.isLevelUnlocked(
                levelOrder = 4,
                progressByLevelId = progressWithMathStarred,
                levels = levels
            ),
            "MATH order 4 unlocks once MATH order 2 (the previous level in the same subject) has a star"
        )
    }

    @Test
    fun progressionEngine_currentLevelForSubject_returnsFirstUnstarredUnlockedLevelInThatWorld() {
        val levels = listOf(
            level(order = 1, subject = Subject.SWEDISH),
            level(order = 2, subject = Subject.MATH),
            level(order = 3, subject = Subject.SWEDISH),
            level(order = 4, subject = Subject.MATH),
            level(order = 6, subject = Subject.MATH)
        )

        // No progress yet -> current MATH level is the first one (order 2).
        val noneStarred = progressionEngine.currentLevelForSubject(
            subject = Subject.MATH,
            levels = levels,
            progressByLevelId = emptyMap()
        )
        assertEquals(2, noneStarred?.order)

        // MATH order 2 is starred -> current level moves to MATH order 4.
        val withMathOrder2Starred = mapOf(
            levels[1].id to LevelProgress(
                profileId = "p1",
                levelId = levels[1].id,
                levelOrder = 2,
                starsEarned = 2
            , completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40)
        )
        val afterFirstStar = progressionEngine.currentLevelForSubject(
            subject = Subject.MATH,
            levels = levels,
            progressByLevelId = withMathOrder2Starred
        )
        assertEquals(4, afterFirstStar?.order)

        // All MATH levels starred -> falls back to the last level of the world.
        val allMathStarred = mapOf(
            levels[1].id to LevelProgress(profileId = "p1", levelId = levels[1].id, levelOrder = 2, starsEarned = 3, completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40),
            levels[3].id to LevelProgress(profileId = "p1", levelId = levels[3].id, levelOrder = 4, starsEarned = 3, completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40),
            levels[4].id to LevelProgress(profileId = "p1", levelId = levels[4].id, levelOrder = 6, starsEarned = 3, completedInAiMode = true, bestTimeSeconds = 20, bestWpm = 40)
        )
        val afterAllStarred = progressionEngine.currentLevelForSubject(
            subject = Subject.MATH,
            levels = levels,
            progressByLevelId = allMathStarred
        )
        assertEquals(6, afterAllStarred?.order)
    }

    private fun level(order: Int, subject: Subject): CampaignLevel = CampaignLevel(
        id = "level_$order",
        courseId = "course_$order",
        order = order,
        title = "Bana $order",
        biomeNameSv = "Skogen",
        subject = subject,
        difficulty = 1,
        timeRequirementSeconds = 30,
        referenceWpm = 40,
        taskId = "task_$order"
    )

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
