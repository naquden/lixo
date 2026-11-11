package se.lixo.app

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.local.LexiconCache
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.LIXO_ANDROID_MIN_VERSION
import se.lixo.app.data.remote.LIXO_ANDROID_PACKAGE_NAME
import se.lixo.app.data.remote.LIXO_EMAIL_LINK_CONTINUE_URL
import se.lixo.app.data.remote.LIXO_FIREBASE_HOSTING_DOMAIN
import se.lixo.app.data.remote.LIXO_IOS_BUNDLE_ID
import se.lixo.app.data.remote.LexiconMeta
import se.lixo.app.data.remote.SyncMergeEngine
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState
import se.lixo.app.domain.engine.ReadingAlignmentSessionState
import se.lixo.app.domain.engine.StoryWordToken
import se.lixo.app.domain.engine.spellingDiff
import se.lixo.app.domain.content.GeographyChoices
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.content.MathCurriculum
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.LevelReward
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.FlagQuestion
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.PendingEmailLinkStorage
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.ui.components.avatar.computeProceduralIdlePose
import se.lixo.app.ui.components.celebration.CelebrationStep
import se.lixo.app.ui.components.celebration.buildCelebrationSteps

class LixoEnginesTest {

    private val alignmentEngine = ForcedAlignmentEngine()
    private val mathEngine = MathEngine()
    private val progressionEngine = ProgressionAndGiftEngine()
    private val rules = BundledTestContent.content.dropRules

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

        // Child mumbles something unintelligible for the first "en" (idx 0) -- a misread, not a
        // swallowed short word (no match, something heard) -- then skips straight to "katt"
        // (idx 1), within the lookahead window, which flags "en" (idx 0) yellow/unread. They then
        // continue reading normally through "och" and the second "en" (idx 3).
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("xyz", "xyz", 0.95f, 50L),
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
    fun forcedAlignment_lookaheadSkipStoresHeardTextOnSkippedWord() {
        var session = alignmentEngine.startSession(
            storyText = "katten sitter på mattan",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("sitta", "sitta", 0.95f, 200L),
                RecognizedSpeechToken("på", "på", 0.95f, 300L)
            )
        )
        val skippedWord = session.words[1]
        assertTrue(skippedWord.needsPractice)
        assertEquals("sitta", skippedWord.heardText)
        assertFalse(skippedWord.isCompleted)
        assertTrue(session.unmatchedSinceAdvance.isEmpty())
    }

    @Test
    fun forcedAlignment_skipWithoutUnmatchedSpeechLeavesHeardTextNull() {
        var session = alignmentEngine.startSession(
            storyText = "katten sitter på mattan",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("på", "på", 0.95f, 200L)
            )
        )
        val skippedWord = session.words[1]
        assertTrue(skippedWord.needsPractice)
        assertNull(skippedWord.heardText)
    }

    @Test
    fun forcedAlignment_hesitationThenWrongAttemptUpdatesHeardText() {
        var session = alignmentEngine.startSession(
            storyText = "katten sitter på mattan",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.onTick(
            state = session,
            deltaMillis = 250L,
            millisSinceLastWordAdvance = BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS
        )
        assertTrue(session.words[0].needsPractice)
        assertNull(session.words[0].heardText)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("mata", "mata", 0.95f, 100L))
        )
        assertEquals("mata", session.words[0].heardText)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("katten", "katten", 0.95f, 200L))
        )
        assertTrue(session.words[0].isCompleted)
        assertEquals("mata", session.words[0].heardText)
    }

    @Test
    fun forcedAlignment_unmatchedBufferClearedAfterMatch() {
        var session = alignmentEngine.startSession(
            storyText = "katten sitter på mattan",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.onTick(
            state = session,
            deltaMillis = 250L,
            millisSinceLastWordAdvance = BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("bla", "bla", 0.95f, 100L),
                RecognizedSpeechToken("bli", "bli", 0.95f, 200L),
                RecognizedSpeechToken("blu", "blu", 0.95f, 300L),
                RecognizedSpeechToken("bly", "bly", 0.95f, 400L),
                RecognizedSpeechToken("blo", "blo", 0.95f, 500L)
            )
        )
        assertEquals(listOf("bli", "blu", "bly", "blo"), session.unmatchedSinceAdvance)
        assertEquals("bli blu bly blo", session.words[0].heardText)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("katten", "katten", 0.95f, 600L))
        )
        assertTrue(session.unmatchedSinceAdvance.isEmpty())
    }

    @Test
    fun spellingDiff_marksMissingLetters() {
        assertEquals(
            listOf(true, true, true, true, false),
            spellingDiff("huset", "huse").map { it.matched }
        )
        assertTrue(spellingDiff("Hus", "").none { it.matched })
        assertTrue(spellingDiff("Hus", "hus").all { it.matched })
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

        val catalog = BundledTestContent.content.items
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
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
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
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
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
    fun manualReadingMode_unlocksAfterExactly15Seconds() {
        val shortText = "Katten Lixo läser."
        val longText = List(200) { "ord" }.joinToString(" ")

        for (storyText in listOf(shortText, longText)) {
            var session = alignmentEngine.startSession(
                storyText = storyText,
                mode = ReadingMode.MANUAL,
                isTimedChallengeRequested = false
            )
            assertEquals(
                15_000L,
                session.manualModeMinRequiredMillis,
                "Unlock delay must be a fixed 15s regardless of story length"
            )

            session = alignmentEngine.onTick(
                state = session,
                deltaMillis = 14_999L,
                millisSinceLastWordAdvance = 0L
            )
            assertFalse(session.isManualCompleteButtonEnabled)
            assertEquals(1, session.manualModeRemainingSeconds)
            assertFalse(alignmentEngine.tryCompleteManualReading(session).isCompleted)

            session = alignmentEngine.onTick(
                state = session,
                deltaMillis = 1L,
                millisSinceLastWordAdvance = 0L
            )
            assertTrue(session.isManualCompleteButtonEnabled)
            assertEquals(0, session.manualModeRemainingSeconds)
            assertTrue(alignmentEngine.tryCompleteManualReading(session).isCompleted)
        }
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
    fun mathSession_starsAreCompletionPlusSpeedPlusNoErrors() {
        val problem = MathProblem(
            id = "mp_clock_1",
            type = MathProblemType.CLOCK,
            promptSv = "Vad är klockan?",
            correctAnswer = "03:30",
            choices = listOf("03:00", "03:30"),
            hintStep1Sv = "Minutvisaren pekar på 6.",
            hintStep2Sv = "Det betyder 30 minuter över 3 (03:30)."
        )
        val timeRequirementSeconds = 60
        val fastElapsedSeconds = 30
        val slowElapsedSeconds = 90

        val cleanState = MathProblemAttemptState(problem)
        val (wrongState, _) = mathEngine.checkAnswer(MathProblemAttemptState(problem), "03:00")

        val fastNoErrors = mathEngine.evaluateMathSession(
            states = listOf(cleanState),
            elapsedSeconds = fastElapsedSeconds,
            timeRequirementSeconds = timeRequirementSeconds,
            isTimedChallenge = false
        )
        assertEquals(3, fastNoErrors.starsEarned)

        val fastWithErrors = mathEngine.evaluateMathSession(
            states = listOf(wrongState),
            elapsedSeconds = fastElapsedSeconds,
            timeRequirementSeconds = timeRequirementSeconds,
            isTimedChallenge = false
        )
        assertEquals(2, fastWithErrors.starsEarned)

        val slowNoErrors = mathEngine.evaluateMathSession(
            states = listOf(cleanState),
            elapsedSeconds = slowElapsedSeconds,
            timeRequirementSeconds = timeRequirementSeconds,
            isTimedChallenge = false
        )
        assertEquals(2, slowNoErrors.starsEarned)

        val slowWithErrors = mathEngine.evaluateMathSession(
            states = listOf(wrongState),
            elapsedSeconds = slowElapsedSeconds,
            timeRequirementSeconds = timeRequirementSeconds,
            isTimedChallenge = false
        )
        assertEquals(1, slowWithErrors.starsEarned)
    }

    @Test
    fun progressionEngine_preventsStarFarmingOnMaxedLevelsAndHandlesDuplicateGifts() {
        val catalog = BundledTestContent.content.items
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
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
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
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
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

    private fun chestFixtureItem(
        id: String,
        pool: ItemPool = ItemPool.STANDARD,
        rarity: Rarity = Rarity.COMMON
    ): ContentItem = BundledTestContent.item(id, rarity = rarity, pool = pool)

    /**
     * Wraps [items] in an [ActiveContent] for [ProgressionAndGiftEngine.recordLevelCompletion]:
     * the real bundled pack when [items] is the real catalog (so dropRules still apply), or an
     * empty-dropRules synthetic pack for a local fixture list.
     */
    private fun testContentFor(items: List<ContentItem>): ActiveContent =
        if (items === BundledTestContent.content.items) BundledTestContent.content
        else BundledTestContent.contentOf(items = items)

    @Test
    fun chestPoints_progressRunGivesOnePointReplayGivesHalfManualModeZero() {
        val engine = ProgressionAndGiftEngine(random = Random(42))

        // A progress run (new level or new stars) always pays 1 point (2 half-points).
        assertEquals(2, engine.chestPointsForRun(netNewStars = 1, isReplay = false, eligible = true, dropRules = rules))
        assertEquals(2, engine.chestPointsForRun(netNewStars = 1, isReplay = true, eligible = true, dropRules = rules))

        // A replay with no new stars pays 0.5 point (1 half-point).
        assertEquals(1, engine.chestPointsForRun(netNewStars = 0, isReplay = true, eligible = true, dropRules = rules))

        // Manual mode / not eligible for gifts pays nothing, regardless of stars.
        assertEquals(0, engine.chestPointsForRun(netNewStars = 1, isReplay = false, eligible = false, dropRules = rules))
        assertEquals(0, engine.chestPointsForRun(netNewStars = 0, isReplay = true, eligible = false, dropRules = rules))
    }

    @Test
    fun chest_awardedAtTenPointsAndCarriesOverflow() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = listOf(chestFixtureItem("item_x"), chestFixtureItem("item_y"))
        val profile = ChildProfile(id = "p1", displayName = "Saga", chestProgressHalfPoints = 19)
        val level1 = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-11-01")

        val run = engine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertNotNull(run.chestOutcome, "19 + 2 half-points crosses the 20 half-point threshold")
        assertEquals(1, run.updatedProfile.chestProgressHalfPoints, "Overflow of 1 half-point carries over")
    }

    @Test
    fun chest_notAwardedBelowThreshold() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = listOf(chestFixtureItem("item_x"), chestFixtureItem("item_y"))
        val profile = ChildProfile(id = "p1", displayName = "Saga", chestProgressHalfPoints = 0)
        val level1 = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-11-01")

        val run = engine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertNull(run.chestOutcome)
        assertEquals(2, run.updatedProfile.chestProgressHalfPoints)
    }

    @Test
    fun chest_poolExcludesStreakAndAchievementItems() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val streakItem = chestFixtureItem("item_streak", pool = ItemPool.SETPIECE)
        val achievementItem = chestFixtureItem("item_achievement", pool = ItemPool.ACHIEVEMENT)
        val catalog = listOf(
            chestFixtureItem("item_a"),
            chestFixtureItem("item_b"),
            streakItem,
            achievementItem
        )

        repeat(200) {
            val outcome = engine.rollChest(allItems = catalog, unlockedItemIds = emptySet(), dropRules = rules)
            assertNotNull(outcome)
            assertTrue(
                outcome.rolledItem.id != streakItem.id && outcome.rolledItem.id != achievementItem.id,
                "Chest pool must exclude weekly-streak set pieces and achievement-linked items"
            )
        }
    }

    @Test
    fun chest_duplicateUnlocksNothing() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = listOf(chestFixtureItem("item_a"), chestFixtureItem("item_b"))

        val outcome = engine.rollChest(
            allItems = catalog,
            unlockedItemIds = catalog.map { it.id }.toSet(),
            dropRules = rules
        )

        assertNotNull(outcome)
        assertTrue(outcome.isDuplicate)
        assertNull(outcome.newlyUnlockedItem)
    }

    @Test
    fun rarityRoll_matchesTargetOdds() {
        val catalog = BundledTestContent.content.items
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val rolls = 20_000
        val counts = mutableMapOf<Rarity, Int>()
        repeat(rolls) {
            val outcome = engine.rollChest(catalog, emptySet(), rules)!!
            counts[outcome.rolledItem.rarity] = (counts[outcome.rolledItem.rarity] ?: 0) + 1
        }

        val targets = mapOf(
            Rarity.COMMON to 0.40,
            Rarity.UNCOMMON to 0.28,
            Rarity.RARE to 0.18,
            Rarity.EPIC to 0.10,
            Rarity.LEGENDARY to 0.04
        )
        for ((rarity, target) in targets) {
            val share = (counts[rarity] ?: 0) / rolls.toDouble()
            assertTrue(abs(share - target) <= 0.015, "$rarity share $share")
        }
    }

    @Test
    fun rollChest_prefersUnowned() {
        val catalog = BundledTestContent.content.items
        val unlocked = catalog
            .filter { ProgressionAndGiftEngine.isChestEligible(it) && it.rarity == Rarity.COMMON }
            .map { it.id }
            .toSet()
        val engine = ProgressionAndGiftEngine(random = Random(42))
        repeat(2000) {
            val outcome = engine.rollChest(catalog, unlocked, rules)!!
            assertFalse(outcome.isDuplicate)
            assertNotNull(outcome.newlyUnlockedItem)
            assertTrue(outcome.rolledItem.rarity != Rarity.COMMON)
        }

        // Nearest fallback: all COMMON owned, UNCOMMON unowned beats LEGENDARY.
        val zeroEngine = ProgressionAndGiftEngine(random = ZeroRandom())
        val c1 = chestFixtureItem("c1", rarity = Rarity.COMMON)
        val c2 = chestFixtureItem("c2", rarity = Rarity.COMMON)
        val u1 = chestFixtureItem("u1", rarity = Rarity.UNCOMMON)
        val l1 = chestFixtureItem("l1", rarity = Rarity.LEGENDARY)
        val nearestOutcome = zeroEngine.rollChest(listOf(c1, c2, u1, l1), setOf(c1.id, c2.id), rules)
        assertEquals("u1", nearestOutcome?.rolledItem?.id)
        assertFalse(nearestOutcome!!.isDuplicate)

        // Lower before higher at equal distance: RARE owned, UNCOMMON and EPIC unowned -> UNCOMMON wins.
        val r1 = chestFixtureItem("r1", rarity = Rarity.RARE)
        val u2 = chestFixtureItem("u1", rarity = Rarity.UNCOMMON)
        val e1 = chestFixtureItem("e1", rarity = Rarity.EPIC)
        val lowerOutcome = zeroEngine.rollChest(listOf(r1, u2, e1), setOf(r1.id), rules)
        assertEquals("u1", lowerOutcome?.rolledItem?.id)
    }

    @Test
    fun rollPresentGift_keepsDuplicateRule() {
        val zeroEngine = ProgressionAndGiftEngine(random = ZeroRandom())
        val c1 = chestFixtureItem("c1", rarity = Rarity.COMMON)
        val c2 = chestFixtureItem("c2", rarity = Rarity.COMMON)
        val items = listOf(c1, c2)
        val unlocked = setOf(c1.id)

        val giftOutcome = zeroEngine.rollPresentGift(Subject.SWEDISH, items, unlocked, rules)
        assertEquals("c1", giftOutcome.rolledItem.id)
        assertTrue(giftOutcome.isDuplicate)
        assertNull(giftOutcome.newlyUnlockedItem)

        val chestOutcome = zeroEngine.rollChest(items, unlocked, rules)
        assertEquals("c2", chestOutcome?.newlyUnlockedItem?.id)

        val allOwnedCatalog = listOf(chestFixtureItem("owned_a"), chestFixtureItem("owned_b"))
        val allOwnedIds = allOwnedCatalog.map { it.id }.toSet()
        val engine = ProgressionAndGiftEngine(random = Random(42))
        repeat(100) {
            val outcome = engine.rollPresentGift(Subject.SWEDISH, allOwnedCatalog, allOwnedIds, rules)
            assertTrue(outcome.isDuplicate)
            assertNull(outcome.newlyUnlockedItem)
        }
    }

    @Test
    fun rarityFallbackOrder_nearestLowerFirst() {
        assertEquals(
            listOf(Rarity.RARE, Rarity.UNCOMMON, Rarity.EPIC, Rarity.COMMON, Rarity.LEGENDARY),
            ProgressionAndGiftEngine.rarityFallbackOrder(Rarity.RARE)
        )
        assertEquals(
            listOf(Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY),
            ProgressionAndGiftEngine.rarityFallbackOrder(Rarity.COMMON)
        )
    }

    @Test
    fun chestPity_minRarityFloor() {
        assertEquals(Rarity.COMMON, ProgressionAndGiftEngine.chestMinRarity(0, 0, 0, rules.pity))
        assertEquals(Rarity.COMMON, ProgressionAndGiftEngine.chestMinRarity(2, 8, 18, rules.pity))
        assertEquals(Rarity.RARE, ProgressionAndGiftEngine.chestMinRarity(3, 0, 0, rules.pity))
        assertEquals(Rarity.EPIC, ProgressionAndGiftEngine.chestMinRarity(3, 9, 0, rules.pity))
        assertEquals(Rarity.EPIC, ProgressionAndGiftEngine.chestMinRarity(0, 9, 0, rules.pity))
        assertEquals(Rarity.LEGENDARY, ProgressionAndGiftEngine.chestMinRarity(0, 0, 19, rules.pity))
        assertEquals(Rarity.LEGENDARY, ProgressionAndGiftEngine.chestMinRarity(3, 9, 19, rules.pity))
    }

    @Test
    fun chestPity_guaranteesRareWithin4() {
        val engine = ProgressionAndGiftEngine(random = ZeroRandom())
        val catalog = BundledTestContent.content.items
        var profile = ChildProfile(id = "p", displayName = "S")
        val unlocked = mutableSetOf<String>()

        val rolledRarities = mutableListOf<Rarity>()
        repeat(4) {
            val outcome = engine.rollChest(
                catalog,
                unlocked,
                rules,
                profile.chestsSinceRarePlus,
                profile.chestsSinceEpicPlus,
                profile.chestsSinceLegendary
            )!!
            rolledRarities += outcome.rolledItem.rarity
            outcome.newlyUnlockedItem?.let { unlocked += it.id }
            profile = ProgressionAndGiftEngine.advanceChestPity(profile, outcome.rolledItem.rarity)
        }

        assertEquals(Rarity.COMMON, rolledRarities[0])
        assertEquals(Rarity.COMMON, rolledRarities[1])
        assertEquals(Rarity.COMMON, rolledRarities[2])
        assertTrue(rolledRarities[3] >= Rarity.RARE, "4th chest must be at least RARE")
    }

    @Test
    fun chestPity_guaranteesLegendaryWithin20() {
        // (a) ZeroRandom always picks the lowest present rarity, so only the pity floor forces it up.
        val engine = ProgressionAndGiftEngine(random = ZeroRandom())
        val catalog = BundledTestContent.content.items
        var profile = ChildProfile(id = "p", displayName = "S")
        val unlocked = mutableSetOf<String>()
        val rolled = mutableListOf<Rarity>()
        repeat(20) {
            val outcome = engine.rollChest(
                catalog,
                unlocked,
                rules,
                profile.chestsSinceRarePlus,
                profile.chestsSinceEpicPlus,
                profile.chestsSinceLegendary
            )!!
            rolled += outcome.rolledItem.rarity
            outcome.newlyUnlockedItem?.let { unlocked += it.id }
            profile = ProgressionAndGiftEngine.advanceChestPity(profile, outcome.rolledItem.rarity)
        }
        assertTrue(rolled.any { it == Rarity.LEGENDARY }, "Some chest in the first 20 must be legendary")
        assertEquals(Rarity.LEGENDARY, rolled[19], "The 20th chest must be the guaranteed legendary")

        // (b) Random(42) over many chests: gaps between pity hits must never exceed the floor.
        val seededEngine = ProgressionAndGiftEngine(random = Random(42))
        var seededProfile = ChildProfile(id = "p2", displayName = "S2")
        var sinceLegendary = 0
        var sinceEpicPlus = 0
        var sinceRarePlus = 0
        repeat(500) {
            val outcome = seededEngine.rollChest(
                catalog,
                emptySet(),
                rules,
                seededProfile.chestsSinceRarePlus,
                seededProfile.chestsSinceEpicPlus,
                seededProfile.chestsSinceLegendary
            )!!
            val r = outcome.rolledItem.rarity
            assertTrue(sinceLegendary < rules.pity.getValue(Rarity.LEGENDARY), "Legendary gap must stay below ${rules.pity.getValue(Rarity.LEGENDARY)}")
            assertTrue(sinceEpicPlus < rules.pity.getValue(Rarity.EPIC), "Epic+ gap must stay below ${rules.pity.getValue(Rarity.EPIC)}")
            assertTrue(sinceRarePlus < rules.pity.getValue(Rarity.RARE), "Rare+ gap must stay below ${rules.pity.getValue(Rarity.RARE)}")
            sinceLegendary = if (r == Rarity.LEGENDARY) 0 else sinceLegendary + 1
            sinceEpicPlus = if (r >= Rarity.EPIC) 0 else sinceEpicPlus + 1
            sinceRarePlus = if (r >= Rarity.RARE) 0 else sinceRarePlus + 1
            seededProfile = ProgressionAndGiftEngine.advanceChestPity(seededProfile, r)
        }
    }

    @Test
    fun chestPity_countersResetCorrectly() {
        val base = ChildProfile(
            id = "p",
            displayName = "S",
            chestsSinceRarePlus = 2,
            chestsSinceEpicPlus = 5,
            chestsSinceLegendary = 7
        )
        val afterEpic = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.EPIC)
        assertEquals(0, afterEpic.chestsSinceRarePlus)
        assertEquals(0, afterEpic.chestsSinceEpicPlus)
        assertEquals(8, afterEpic.chestsSinceLegendary)

        val afterCommon = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.COMMON)
        assertEquals(3, afterCommon.chestsSinceRarePlus)
        assertEquals(6, afterCommon.chestsSinceEpicPlus)
        assertEquals(8, afterCommon.chestsSinceLegendary)

        val afterRare = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.RARE)
        assertEquals(0, afterRare.chestsSinceRarePlus)
        assertEquals(6, afterRare.chestsSinceEpicPlus)
        assertEquals(8, afterRare.chestsSinceLegendary)

        val afterLegendary = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.LEGENDARY)
        assertEquals(0, afterLegendary.chestsSinceRarePlus)
        assertEquals(0, afterLegendary.chestsSinceEpicPlus)
        assertEquals(0, afterLegendary.chestsSinceLegendary)

        // Through recordLevelCompletion: a Flitkista chest advances the stored counters.
        val engine = ProgressionAndGiftEngine(random = ZeroRandom())
        val catalog = listOf(
            chestFixtureItem("e1", rarity = Rarity.EPIC),
            chestFixtureItem("e2", rarity = Rarity.EPIC),
            // An unowned LEGENDARY so that tier is not an exhausted (empty) tier; ZeroRandom still
            // picks EPIC (pickRarity's nextInt(total) -> 0 -> the first present tier).
            chestFixtureItem("l1", rarity = Rarity.LEGENDARY)
        )
        val profile = ChildProfile(
            id = "p1",
            displayName = "Saga",
            chestProgressHalfPoints = 19,
            chestsSinceRarePlus = 2,
            chestsSinceEpicPlus = 5,
            chestsSinceLegendary = 7
        )
        val level1 = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-11-01")

        val run = engine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertNotNull(run.chestOutcome)
        assertEquals(Rarity.EPIC, run.chestOutcome.rolledItem.rarity)
        assertEquals(0, run.updatedProfile.chestsSinceRarePlus)
        assertEquals(0, run.updatedProfile.chestsSinceEpicPlus)
        assertEquals(8, run.updatedProfile.chestsSinceLegendary)
    }

    @Test
    fun advanceChestPity_resetsExhaustedTierCounters() {
        val base = ChildProfile(
            id = "p",
            displayName = "S",
            chestsSinceRarePlus = 2,
            chestsSinceEpicPlus = 5,
            chestsSinceLegendary = 7
        )

        val a = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.EPIC, setOf(Rarity.LEGENDARY))
        assertEquals(0, a.chestsSinceLegendary)
        assertEquals(0, a.chestsSinceEpicPlus)
        assertEquals(0, a.chestsSinceRarePlus)

        val b = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.COMMON, setOf(Rarity.LEGENDARY))
        assertEquals(0, b.chestsSinceLegendary)
        assertEquals(6, b.chestsSinceEpicPlus)
        assertEquals(3, b.chestsSinceRarePlus)

        val c = ProgressionAndGiftEngine.advanceChestPity(base, Rarity.COMMON, setOf(Rarity.EPIC, Rarity.LEGENDARY))
        assertEquals(0, c.chestsSinceEpicPlus)
        assertEquals(0, c.chestsSinceLegendary)
        assertEquals(3, c.chestsSinceRarePlus)

        val d = ProgressionAndGiftEngine.advanceChestPity(
            base,
            Rarity.COMMON,
            setOf(Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY)
        )
        assertEquals(0, d.chestsSinceRarePlus)
        assertEquals(0, d.chestsSinceEpicPlus)
        assertEquals(0, d.chestsSinceLegendary)
    }

    @Test
    fun chestPity_floorCappedWhenTierExhausted() {
        val catalog = BundledTestContent.content.items
        val eligible = catalog.filter(ProgressionAndGiftEngine::isChestEligible)
        val unlocked = eligible.filter { it.rarity == Rarity.LEGENDARY }.map { it.id }.toMutableSet()
        var profile = ChildProfile(id = "p", displayName = "S", chestsSinceLegendary = 19)
        val engine = ProgressionAndGiftEngine(random = Random(42))

        val rolled = mutableListOf<Rarity>()
        repeat(50) {
            val outcome = engine.rollChest(
                catalog,
                unlocked,
                dropRules = rules,
                chestsSinceRarePlus = profile.chestsSinceRarePlus,
                chestsSinceEpicPlus = profile.chestsSinceEpicPlus,
                chestsSinceLegendary = profile.chestsSinceLegendary
            )!!
            rolled += outcome.rolledItem.rarity
            outcome.newlyUnlockedItem?.let { unlocked += it.id }
            profile = ProgressionAndGiftEngine.advanceChestPity(
                profile,
                outcome.rolledItem.rarity,
                ProgressionAndGiftEngine.exhaustedChestTiers(catalog, unlocked)
            )
            assertEquals(0, profile.chestsSinceLegendary, "chestsSinceLegendary must stay 0 once LEGENDARY is exhausted")
        }

        assertEquals(Rarity.EPIC, rolled.first(), "Floor is capped LEGENDARY -> EPIC once every LEGENDARY is owned")
        assertFalse(rolled.any { it == Rarity.LEGENDARY }, "No LEGENDARY can be rolled once the tier is exhausted")
        assertTrue(
            rolled.take(10).any { it == Rarity.COMMON || it == Rarity.UNCOMMON },
            "A stuck-floor bug would never let COMMON/UNCOMMON through"
        )
    }

    @Test
    fun chestPity_onlyFlitkistaCounts() {
        val profile = ChildProfile(
            id = "p1",
            displayName = "Saga",
            chestProgressHalfPoints = 0,
            chestsSinceRarePlus = 2,
            chestsSinceEpicPlus = 5,
            chestsSinceLegendary = 7
        )
        val catalog = BundledTestContent.content.items
        val level1 = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-11-01")
        val engine = ProgressionAndGiftEngine(random = Random(42))

        // (a) A LEVEL reward (first clear) does not touch the pity counters.
        val firstClear = engine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        assertTrue(firstClear.newlyUnlockedRewards.any { it.source == RewardSource.LEVEL })
        assertNull(firstClear.chestOutcome)
        assertEquals(2, firstClear.updatedProfile.chestsSinceRarePlus)
        assertEquals(5, firstClear.updatedProfile.chestsSinceEpicPlus)
        assertEquals(7, firstClear.updatedProfile.chestsSinceLegendary)

        // (b) A replay gift does not touch the pity counters either.
        val replayOutcome = engine.recordLevelCompletion(
            profile = profile,
            level = level1,
            previousProgress = LevelProgress(
                profileId = "p1",
                levelId = level1.id,
                levelOrder = level1.order,
                starsEarned = 3,
                completedInAiMode = true,
                bestTimeSeconds = 20,
                bestWpm = 60
            ),
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 10,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        assertNotNull(replayOutcome.giftOutcome)
        assertNull(replayOutcome.chestOutcome)
        assertEquals(2, replayOutcome.updatedProfile.chestsSinceRarePlus)
        assertEquals(5, replayOutcome.updatedProfile.chestsSinceEpicPlus)
        assertEquals(7, replayOutcome.updatedProfile.chestsSinceLegendary)
    }

    @Test
    fun chestPity_floorPrefersUnownedAtOrAboveFloor_thenBelow() {
        val zeroEngine = ProgressionAndGiftEngine(random = ZeroRandom())
        val c1 = chestFixtureItem("c1", rarity = Rarity.COMMON)
        val u1 = chestFixtureItem("u1", rarity = Rarity.UNCOMMON)
        val r1 = chestFixtureItem("r1", rarity = Rarity.RARE)
        val e1 = chestFixtureItem("e1", rarity = Rarity.EPIC)

        // Floor is RARE (3+1>=4). r1 is owned. e1 (unowned, >= floor) beats u1 (unowned, < floor).
        val above = zeroEngine.rollChest(
            listOf(c1, u1, r1, e1),
            setOf(r1.id),
            dropRules = rules,
            chestsSinceRarePlus = 3,
            chestsSinceEpicPlus = 0,
            chestsSinceLegendary = 0
        )
        assertEquals("e1", above?.rolledItem?.id)

        // Without e1 in the pool, the unowned u1 (below the floor) wins over a duplicate.
        val below = zeroEngine.rollChest(
            listOf(c1, u1, r1),
            setOf(r1.id),
            dropRules = rules,
            chestsSinceRarePlus = 3,
            chestsSinceEpicPlus = 0,
            chestsSinceLegendary = 0
        )
        assertEquals("u1", below?.rolledItem?.id)
        assertFalse(below!!.isDuplicate)

        // All-COMMON pool with a floor nothing in the pool reaches: must not crash, falls back to COMMON.
        val c2 = chestFixtureItem("c2", rarity = Rarity.COMMON)
        val allCommon = zeroEngine.rollChest(
            listOf(c1, c2),
            emptySet(),
            dropRules = rules,
            chestsSinceRarePlus = 3,
            chestsSinceEpicPlus = 9,
            chestsSinceLegendary = 19
        )
        assertNotNull(allCommon)
        assertEquals(Rarity.COMMON, allCommon.rolledItem.rarity)
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
    fun seededCampaign_has190LevelsIncl100MathAnd30Geography() {
        val (levels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)

        val orders = levels.map { it.order }
        assertEquals(orders.size, orders.distinct().size, "Level orders must be unique")
        assertEquals((1..190).toList(), orders.sorted())

        val mathLevels = levels.filter { it.subject == Subject.MATH }
        val swedishLevels = levels.filter { it.subject == Subject.SWEDISH }
        val englishLevels = levels.filter { it.subject == Subject.ENGLISH }
        val geographyLevels = levels.filter { it.subject == Subject.GEOGRAPHY }
        // Subject assignment is unchanged from before this refactor: order % 5 == 0 -> ENGLISH,
        // remaining even orders -> MATH (now built separately via MathCurriculum), remaining odd -> SWEDISH.
        // Across orders 1..100 that is 20 ENGLISH, 40 SWEDISH, 40 MATH (n1..40); n41..100 add 60 more MATH
        // levels at orders 101..160, for 100 MATH levels total. Orders 161..190 add 30 geography levels
        // (plan 037), for 190 levels overall.
        assertEquals(100, mathLevels.size)
        assertEquals(40, swedishLevels.size)
        assertEquals(20, englishLevels.size)
        assertEquals(30, geographyLevels.size)
        assertEquals((161..190).toList(), geographyLevels.map { it.order }.sorted())

        val mathIndices = mathLevels.mapNotNull { MathCurriculum.mathIndexForOrder(it.order) }.sorted()
        assertEquals((1..100).toList(), mathIndices)
    }

    @Test
    fun seededMath_n1IsSixEasyAdditions() {
        val (levels, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val level = levels.first { it.order == 2 }
        val task = tasks.getValue(level.taskId)
        val problems = task.mathProblems

        assertEquals(6, problems.size)
        assertTrue(problems.all { it.type == MathProblemType.ADDITION })
        assertTrue(problems.all { (it.correctAnswer.toIntOrNull() ?: Int.MAX_VALUE) <= 6 })
        assertEquals(1, level.difficulty)
        assertEquals(60, level.timeRequirementSeconds)
        assertEquals("Bana 1: Plus med små tal (Viskande Skogen)", level.title)
    }

    @Test
    fun seededMath_stageAndClockRules() {
        val (levels, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val mathLevels = levels.filter { it.subject == Subject.MATH }

        val allTypesSeen = mutableSetOf<MathProblemType>()
        for (level in mathLevels) {
            val n = MathCurriculum.mathIndexForOrder(level.order) ?: error("No math index for order ${level.order}")
            val problems = tasks.getValue(level.taskId).mathProblems

            assertTrue(problems.size <= 10, "n=$n has more than 10 questions")
            if (n <= 5) {
                assertEquals(6, problems.size, "n=$n must have exactly 6 questions")
            }

            if (n <= 20) {
                assertTrue(problems.all { it.type == MathProblemType.ADDITION }, "n=$n must be all ADDITION")
            }
            if (n < 28) {
                assertTrue(problems.none { it.type == MathProblemType.SUBTRACTION }, "SUBTRACTION before n28 at n=$n")
            }
            if (n < 31) {
                assertTrue(problems.none { it.type == MathProblemType.CLOCK }, "CLOCK before n31 at n=$n")
            }
            if (n < 80) {
                problems.filter { it.type == MathProblemType.CLOCK }.forEach {
                    assertTrue(
                        it.visualClockMinute == 0 || it.visualClockMinute == 30,
                        "n=$n clock minute must be 0 or 30, got ${it.visualClockMinute}"
                    )
                }
            }
            assertTrue(
                problems.none { it.type == MathProblemType.MULTIPLICATION || it.type == MathProblemType.DIVISION },
                "No multiplication/division allowed at n=$n"
            )
            allTypesSeen.addAll(problems.map { it.type })

            for (p in problems) {
                assertTrue(p.correctAnswer in p.choices, "n=$n ${p.id}: correctAnswer not in choices")
                assertEquals(4, p.choices.size, "n=$n ${p.id}: expected 4 choices")
                assertEquals(4, p.choices.distinct().size, "n=$n ${p.id}: choices must be distinct")
                if (p.type != MathProblemType.CLOCK) {
                    p.choices.forEach { choice ->
                        val value = choice.toIntOrNull()
                        assertNotNull(value, "n=$n ${p.id}: non-integer choice '$choice'")
                        assertTrue(value >= 0, "n=$n ${p.id}: negative choice '$choice'")
                    }
                }
            }
        }

        assertEquals(
            setOf(
                MathProblemType.ADDITION,
                MathProblemType.SUBTRACTION,
                MathProblemType.TEN_CROSSING,
                MathProblemType.CLOCK,
                MathProblemType.MONEY,
                MathProblemType.WORD_PROBLEM
            ),
            allTypesSeen
        )
    }

    @Test
    fun seededMath_correctAnswerPositionVaries() {
        val (levels, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val mathLevels = levels.filter { it.subject == Subject.MATH }

        for (level in mathLevels) {
            val problems = tasks.getValue(level.taskId).mathProblems
            val positions = problems.map { it.choices.indexOf(it.correctAnswer) }.toSet()
            assertTrue(positions.size > 1, "Level order ${level.order} never varies correct answer position")
        }
    }

    @Test
    fun progressionEngine_perSubjectUnlock_mathN41RequiresStarOnN40AcrossOrderGap() {
        val levels = listOf(
            level(order = 98, subject = Subject.MATH),
            level(order = 99, subject = Subject.SWEDISH),
            level(order = 100, subject = Subject.ENGLISH),
            level(order = 101, subject = Subject.MATH)
        )

        assertFalse(progressionEngine.isLevelUnlocked(101, emptyMap(), levels))

        val progressWithOnlySwedishAndEnglishStarred = mapOf(
            levels[1].id to LevelProgress(
                profileId = "p1",
                levelId = levels[1].id,
                levelOrder = 99,
                starsEarned = 3,
                completedInAiMode = true,
                bestTimeSeconds = 20,
                bestWpm = 40
            ),
            levels[2].id to LevelProgress(
                profileId = "p1",
                levelId = levels[2].id,
                levelOrder = 100,
                starsEarned = 3,
                completedInAiMode = true,
                bestTimeSeconds = 20,
                bestWpm = 40
            )
        )
        assertFalse(
            progressionEngine.isLevelUnlocked(101, progressWithOnlySwedishAndEnglishStarred, levels),
            "MATH order 101 must require a star on MATH order 98, not on Swedish/English levels"
        )

        val progressWithMathStarred = mapOf(
            levels[0].id to LevelProgress(
                profileId = "p1",
                levelId = levels[0].id,
                levelOrder = 98,
                starsEarned = 1,
                completedInAiMode = true,
                bestTimeSeconds = 20,
                bestWpm = 40
            )
        )
        assertTrue(
            progressionEngine.isLevelUnlocked(101, progressWithMathStarred, levels),
            "MATH order 101 unlocks once MATH order 98 (the previous MATH level, despite the order gap) has a star"
        )

        // Repeat on the real seeded levels.
        val (seededLevels, _) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val seededProgress = mapOf(
            "level_98" to LevelProgress(
                profileId = "p1",
                levelId = "level_98",
                levelOrder = 98,
                starsEarned = 1,
                completedInAiMode = true,
                bestTimeSeconds = 20,
                bestWpm = 40
            )
        )
        assertTrue(progressionEngine.isLevelUnlocked(101, seededProgress, seededLevels))
        assertEquals(Subject.MATH, seededLevels.first { it.order == 101 }.subject)
    }

    @Test
    fun mathCurriculum_parityFixture() {
        assertEquals(2, MathCurriculum.orderForMathIndex(1))
        assertEquals(76, MathCurriculum.orderForMathIndex(31))
        assertEquals(98, MathCurriculum.orderForMathIndex(40))
        assertEquals(101, MathCurriculum.orderForMathIndex(41))
        assertEquals(152, MathCurriculum.orderForMathIndex(92))
        assertEquals(160, MathCurriculum.orderForMathIndex(100))

        assertEquals(40, MathCurriculum.mathIndexForOrder(98))
        assertEquals(41, MathCurriculum.mathIndexForOrder(101))
        assertNull(MathCurriculum.mathIndexForOrder(10))
        assertNull(MathCurriculum.mathIndexForOrder(3))

        val n1Prompts = listOf(
            "Vad blir 1 + 1?",
            "Vad blir 1 + 2?",
            "Vad blir 2 + 1?",
            "Vad blir 1 + 3?",
            "Vad blir 3 + 1?",
            "Vad blir 1 + 4?"
        )
        val n1Choices = listOf(
            listOf("1", "3", "2", "4"),
            listOf("2", "4", "5", "3"),
            listOf("3", "2", "4", "5"),
            listOf("3", "4", "5", "6"),
            listOf("3", "5", "4", "6"),
            listOf("4", "6", "7", "5")
        )
        val n1Problems = MathCurriculum.buildMathProblems(1, MathCurriculum.orderForMathIndex(1))
        assertEquals(n1Prompts, n1Problems.map { it.promptSv })
        assertEquals(n1Choices, n1Problems.map { it.choices })

        val n31Prompts = listOf(
            "Vad blir 4 - 2?",
            "Vad blir 5 - 2?",
            "Timvisaren pekar rakt på 3 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 4 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 5 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 6 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 7 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 8 och minutvisaren pekar rakt upp på 12. Vad är klockan?"
        )
        val n31Answers = listOf("2", "3", "03:00", "04:00", "05:00", "06:00", "07:00", "08:00")
        val n31Problems = MathCurriculum.buildMathProblems(31, MathCurriculum.orderForMathIndex(31))
        assertEquals(n31Prompts, n31Problems.map { it.promptSv })
        assertEquals(n31Answers, n31Problems.map { it.correctAnswer })

        val n92Prompts = listOf(
            "Timvisaren är mellan 7 och 8, och minutvisaren pekar på 4. Vad är klockan?",
            "Timvisaren är mellan 10 och 11, och minutvisaren pekar på 7. Vad är klockan?",
            "Timvisaren är mellan 2 och 3, och minutvisaren pekar på 2. Vad är klockan?",
            "Timvisaren närmar sig 6 och minutvisaren pekar på 9. Vad är klockan?",
            "Timvisaren är mellan 8 och 9, och minutvisaren pekar på 1. Vad är klockan?",
            "Timvisaren är mellan 11 och 12, och minutvisaren pekar på 10. Vad är klockan?",
            "Timvisaren är mellan 3 och 4, och minutvisaren pekar på 5. Vad är klockan?",
            "Timvisaren är mellan 6 och 7, och minutvisaren pekar på 8. Vad är klockan?",
            "Timvisaren är strax efter 9 och minutvisaren pekar på 3. Vad är klockan?",
            "Timvisaren är mellan 12 och 1, och minutvisaren pekar på 11. Vad är klockan?"
        )
        val n92Answers = listOf(
            "07:20", "10:35", "02:10", "05:45", "08:05",
            "11:50", "03:25", "06:40", "09:15", "12:55"
        )
        val n92Problems = MathCurriculum.buildMathProblems(92, MathCurriculum.orderForMathIndex(92))
        assertEquals(n92Prompts, n92Problems.map { it.promptSv })
        assertEquals(n92Answers, n92Problems.map { it.correctAnswer })
    }

    @Test
    fun mathCurriculum_clockMinuteRanges() {
        for (n in 1..100) {
            val problems = MathCurriculum.buildMathProblems(n, MathCurriculum.orderForMathIndex(n))
            val clockProblems = problems.filter { it.type == MathProblemType.CLOCK }
            if (n < 31) {
                assertTrue(clockProblems.isEmpty(), "CLOCK before n31 at n=$n")
            }
            if (n < 80) {
                clockProblems.forEach {
                    assertTrue(
                        it.visualClockMinute == 0 || it.visualClockMinute == 30,
                        "n=$n clock minute must be in {0, 30}, got ${it.visualClockMinute}"
                    )
                }
            } else if (n in 80..91) {
                clockProblems.forEach {
                    assertTrue(
                        it.visualClockMinute in setOf(0, 15, 30, 45),
                        "n=$n clock minute must be in {0, 15, 30, 45}, got ${it.visualClockMinute}"
                    )
                }
            }
        }
    }

    @Test
    fun mathCurriculum_noOperandIsOneFromN2Onward() {
        val promptPattern = Regex("""Vad blir (\d+) ([+-]) (\d+)\?""")
        for (n in 2..100) {
            val problems = MathCurriculum.buildMathProblems(n, MathCurriculum.orderForMathIndex(n))
            for (p in problems) {
                if (p.type != MathProblemType.ADDITION && p.type != MathProblemType.SUBTRACTION) continue
                val match = promptPattern.matchEntire(p.promptSv) ?: continue
                val a = match.groupValues[1].toInt()
                val b = match.groupValues[3].toInt()
                assertTrue(a != 1 && b != 1, "n=$n ${p.id}: operand of 1 found in '${p.promptSv}'")
            }
        }
    }

    @Test
    fun progressionEngine_recordLevelCompletion_order150AdvancesTo151AndOrder190StaysCapped() {
        val wardrobe = BundledTestContent.content.items
        val levelAt150 = level(order = 150, subject = Subject.MATH)
        val levelAt190 = level(order = 190, subject = Subject.GEOGRAPHY)
        val baseProfile = ChildProfile(
            id = "p1",
            displayName = "Test",
            currentLevelOrder = 150,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L
        )

        val outcome150 = progressionEngine.recordLevelCompletion(
            profile = baseProfile,
            level = levelAt150,
            previousProgress = null,
            earnedStarsThisRun = 2,
            completedInAiMode = true,
            elapsedSeconds = 30,
            achievedWpm = 50,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(wardrobe),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-01-01"),
            nowEpochMillis = 1000L
        )
        assertEquals(151, outcome150.updatedProfile.currentLevelOrder)

        val profileAt190 = baseProfile.copy(currentLevelOrder = 190)
        val outcome190 = progressionEngine.recordLevelCompletion(
            profile = profileAt190,
            level = levelAt190,
            previousProgress = null,
            earnedStarsThisRun = 2,
            completedInAiMode = true,
            elapsedSeconds = 30,
            achievedWpm = 50,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(wardrobe),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-01-01"),
            nowEpochMillis = 1000L
        )
        assertEquals(190, outcome190.updatedProfile.currentLevelOrder)
    }

    @Test
    fun progressionEngine_recordLevelCompletion_geographyLevel_incrementsGeographyNotMathOrReading() {
        val wardrobe = BundledTestContent.content.items
        val levelAt161 = level(order = 161, subject = Subject.GEOGRAPHY)
        val profile = ChildProfile(
            id = "p1",
            displayName = "Test",
            currentLevelOrder = 161,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L
        )
        val todayBeforeRun = DailyActivityRecord(profileId = "p1", calendarDateIso = "2026-01-01")

        // recordLevelCompletion() does not return the updated DailyActivityRecord (callers compute
        // it themselves, mirroring the engine's internal `when (level.subject)` branch - see
        // LixoApp.kt's onFinishMathSession). Exercise that same branch here directly.
        val outcome = progressionEngine.recordLevelCompletion(
            profile = profile,
            level = levelAt161,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 30,
            achievedWpm = 0,
            completedTimedChallenge = true,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(wardrobe),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = todayBeforeRun,
            nowEpochMillis = 1000L
        )
        assertEquals(162, outcome.updatedProfile.currentLevelOrder)

        val updatedDaily = when (levelAt161.subject) {
            Subject.GEOGRAPHY -> todayBeforeRun.copy(
                geographyTasksCompleted = todayBeforeRun.geographyTasksCompleted + 1
            )
            else -> todayBeforeRun
        }

        assertEquals(1, updatedDaily.geographyTasksCompleted)
        assertEquals(0, updatedDaily.mathTasksCompleted)
        assertEquals(0, updatedDaily.readingTasksCompleted)
    }

    @Test
    fun syncMergeEngine_combinesInventoryUnionBestStarsAndLastWriteWinsSettings() {
        val local = SyncMergeEngine.SyncableProfileSnapshot(
            profileId = "p1",
            unlockedItemIds = setOf("item_a", "item_b"),
            starsByLevelId = mapOf("level_1" to 3, "level_2" to 1),
            appearance = AvatarAppearance(avatarId = "avatar_cat"),
            dailyReadingGoalMinutes = 15,
            dailyMathGoalProblems = 10,
            settingsUpdatedAtEpochMillis = 1000L
        )
        val remote = SyncMergeEngine.SyncableProfileSnapshot(
            profileId = "p1",
            unlockedItemIds = setOf("item_b", "item_c"),
            starsByLevelId = mapOf("level_1" to 2, "level_2" to 3, "level_3" to 2),
            appearance = AvatarAppearance(avatarId = "avatar_knight"),
            dailyReadingGoalMinutes = 25,
            dailyMathGoalProblems = 15,
            settingsUpdatedAtEpochMillis = 2000L // Newer timestamp wins for settings
        )

        val merged = SyncMergeEngine.mergeSnapshots(local, remote)
        assertEquals(setOf("item_a", "item_b", "item_c"), merged.unlockedItemIds)
        assertEquals(3, merged.starsByLevelId["level_1"])
        assertEquals(3, merged.starsByLevelId["level_2"])
        assertEquals(2, merged.starsByLevelId["level_3"])
        assertEquals("avatar_knight", merged.appearance.avatarId)
        assertEquals(25, merged.dailyReadingGoalMinutes)
    }

    @Test
    fun avatar3DController_computesProceduralIdlePose() {
        val pose0 = computeProceduralIdlePose(0.0f)
        val poseQuarter = computeProceduralIdlePose(0.3f, hasLegendaryEquipped = true)
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

        val repo = LixoRepository(syncService, content = BundledTestContent.repository())
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

            override suspend fun fetchLexiconMeta(): LexiconMeta = LexiconMeta(version = 1, chunkCount = 1)

            override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> = listOf(
                DictionaryEntry(
                    wordNormalized = "månfyren",
                    displayWord = "månfyren",
                    syllables = "mån-fy-ren",
                    explanationSv = "Ett fyrtorn som lyser som månsken.",
                    exampleSentenceSv = "Månfyren syntes långt ute över havet."
                )
            )
        }

        val repo = LixoRepository(fakeGateway, lexiconCache = null, content = BundledTestContent.repository())
        val synced = repo.syncCurriculumFromCloud()
        assertTrue(synced)
        assertEquals(
            "Svenska Sagor: Viskande Skogen (Moln)",
            repo.courses.value.first { it.id == "course_sv_1" }.title
        )
        assertEquals("mån-fy-ren", repo.lookupDictionaryWord("månfyren").syllables)
    }

    // doc/plans/030: addFriendByQuery/lookupFriendByUsernameOrQr are gone (replaced by invite
    // codes); the detailed flow matrix lives in commonTest's FriendFlowTest against
    // FakeFriendCloud. These keep basic repository-level coverage on the JVM host test too.

    private fun fakeCloudGatewayForUid(uid: String): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
        override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
    }

    @Test
    fun addFriendByCode_redeemsValidCode_addsFriendBothSides() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = LixoRepository(
            fakeCloudGatewayForUid("uidA"),
            friendCloud = FakeFriendCloud("uidA", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )
        val repoB = LixoRepository(
            fakeCloudGatewayForUid("uidB"),
            friendCloud = FakeFriendCloud("uidB", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )

        val invite = repoA.createFriendCode().getOrThrow()
        val result = repoB.addFriendByCode(invite.code, fromQr = false)
        assertTrue(result.isSuccess)
        assertEquals(1, repoB.friendsOfActiveProfile().size)

        repoA.refreshFriends()
        assertEquals(1, repoA.friendsOfActiveProfile().size)
    }

    @Test
    fun addFriendByCode_invalidCode_failsWithInvalidQr() = runTest {
        val repo = LixoRepository(
            fakeCloudGatewayForUid("uidX"),
            friendCloud = FakeFriendCloud("uidX"),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )
        val result = repo.addFriendByCode("not-a-code!!", fromQr = false)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.INVALID_QR, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun addFriendByCode_sameCodeTwice_secondCallFailsWithAlreadyFriend() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = LixoRepository(
            fakeCloudGatewayForUid("uidA2"),
            friendCloud = FakeFriendCloud("uidA2", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )
        val repoB = LixoRepository(
            fakeCloudGatewayForUid("uidB2"),
            friendCloud = FakeFriendCloud("uidB2", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )

        val invite = repoA.createFriendCode().getOrThrow()
        val first = repoB.addFriendByCode(invite.code, fromQr = false)
        assertTrue(first.isSuccess)
        val countAfterFirst = repoB.friendsOfActiveProfile().size

        val second = repoB.addFriendByCode(invite.code, fromQr = false)
        assertTrue(second.isFailure)
        assertEquals(AddFriendError.ALREADY_FRIEND, (second.exceptionOrNull() as? AddFriendException)?.reason)
        assertEquals(countAfterFirst, repoB.friendsOfActiveProfile().size)
    }

    @Test
    fun friends_freshRepoIsEmptyAndFriendsAreScopedPerProfile() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = LixoRepository(
            fakeCloudGatewayForUid("uidA3"),
            friendCloud = FakeFriendCloud("uidA3", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )
        val repoB = LixoRepository(
            fakeCloudGatewayForUid("uidB3"),
            friendCloud = FakeFriendCloud("uidB3", store),
            lexiconCache = null,
            isDebugBuild = true,
            content = BundledTestContent.repository()
        )
        assertTrue(repoB.friendsOfActiveProfile().isEmpty())

        val invite = repoA.createFriendCode().getOrThrow()
        val firstProfileId = repoB.activeProfileId.value!!
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        assertEquals(1, repoB.friendsOfActiveProfile().size)

        val sibling = repoB.createProfile("Syskon", 7)
        repoB.selectProfile(sibling.id)
        assertTrue(repoB.friendsOfActiveProfile().isEmpty())

        repoB.selectProfile(firstProfileId)
        assertEquals(1, repoB.friendsOfActiveProfile().size)
    }

    @Test
    fun seed_releaseBuild_hasNoProfileButSeedsCurriculum() = runTest {
        val repo = LixoRepository(FirebaseSyncService(), lexiconCache = null, isDebugBuild = false, content = BundledTestContent.repository())

        assertTrue(repo.profiles.value.isEmpty())
        assertNull(repo.activeProfileId.value)
        assertTrue(repo.unlockedItemIds.value.isEmpty())
        assertTrue(repo.friendsOfActiveProfile().isEmpty())
        assertTrue(repo.levels.value.isNotEmpty())
        assertTrue(repo.tasksById.value.isNotEmpty())
        assertTrue(repo.content.active.value.items.isNotEmpty())
        assertTrue(repo.courses.value.isNotEmpty())
        assertTrue(repo.achievements.value.isNotEmpty())
    }

    @Test
    fun seed_debugBuild_seedsSagaWithTutorialDoneAndStarterHat() = runTest {
        val repo = LixoRepository(FirebaseSyncService(), lexiconCache = null, isDebugBuild = true, content = BundledTestContent.repository())

        val saga = repo.profiles.value.single()
        assertEquals("Saga", saga.displayName)
        assertEquals("profile_child_1", saga.id)
        assertTrue(saga.tutorialCompleted)
        assertEquals(saga.id, repo.activeProfileId.value)
        assertEquals(
            repo.content.active.value.dropRules.tutorialRewardItemId,
            saga.appearance.equipped[EquipmentSlot.HAT]
        )
        assertEquals(setOf(repo.content.active.value.dropRules.tutorialRewardItemId), repo.unlockedItemIds.value)
    }

    @Test
    fun createProfile_onEmptyReleaseRepo_becomesActiveWithTutorialPending() = runTest {
        val repo = LixoRepository(FirebaseSyncService(), lexiconCache = null, isDebugBuild = false, content = BundledTestContent.repository())

        val created = repo.createProfile("Ella", 7)

        assertEquals("Ella", created.displayName)
        assertEquals(7, created.ageYears)
        assertFalse(created.tutorialCompleted)
        assertEquals(created.id, repo.activeProfileId.value)
        assertEquals(1, repo.profiles.value.size)
    }

    @Test
    fun createProfile_clampsAgeToSixThroughTwelve() = runTest {
        val repo = LixoRepository(FirebaseSyncService(), lexiconCache = null, isDebugBuild = false, content = BundledTestContent.repository())

        assertEquals(6, repo.createProfile("A", 3).ageYears)
        assertEquals(12, repo.createProfile("B", 20).ageYears)
    }

    @Test
    fun forcedAlignment_finishWithUnreadWordsCompletesAndKeepsYellowUnread() {
        var session = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        // Skip "Lixo" (idx 1) by reading "katten" then jumping to "hittar".
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("hittar", "hittar", 0.95f, 200L),
                RecognizedSpeechToken("en", "en", 0.95f, 300L),
                RecognizedSpeechToken("stjärna", "stjärna", 0.95f, 400L)
            )
        )
        assertTrue(session.isAwaitingUnreadWords, "Reaching the end with an unread yellow word must await")
        assertFalse(session.isCompleted)

        val result = alignmentEngine.finishWithUnreadWords(session)
        assertTrue(result.isCompleted)
        assertFalse(result.isAwaitingUnreadWords)
        assertTrue(result.words[1].needsPractice)
        assertFalse(result.words[1].isCompleted)

        val score = alignmentEngine.evaluateSessionScore(result, referenceWpm = 40, timeRequirementSeconds = 60)
        assertTrue(score.struggledWordsToLog.contains("lixo"))
    }

    @Test
    fun forcedAlignment_tutorialStorySkippedWordsAwaitThenFinish() {
        var session = alignmentEngine.startSession(BalanceConfig.TUTORIAL_STORY_SV, ReadingMode.AI_LISTENING, false)
        val words = session.words
        assertEquals("lixo", words[1].normalizedText)
        assertEquals("hatten", words[8].normalizedText)

        session = alignmentEngine.processRecognizedTokens(
            session,
            words.filter { it.index != 1 && it.index != 8 }.mapIndexed { i, w ->
                RecognizedSpeechToken(w.displayText, w.normalizedText, 0.95f, (i + 1) * 100L)
            }
        )

        assertTrue(session.isAwaitingUnreadWords)
        assertFalse(session.isCompleted)
        assertTrue(session.words[1].needsPractice && !session.words[1].isCompleted)
        assertTrue(session.words[8].needsPractice && !session.words[8].isCompleted)
        assertTrue(session.words[14].isCompleted && !session.words[14].needsPractice)
        assertEquals(session.words.size, session.currentWordIndex)

        val done = alignmentEngine.finishWithUnreadWords(session)
        assertTrue(done.isCompleted)
        assertFalse(done.isAwaitingUnreadWords)
    }

    @Test
    fun forcedAlignment_tutorialReadingYellowWordsAfterEndCompletes() {
        var session = alignmentEngine.startSession(BalanceConfig.TUTORIAL_STORY_SV, ReadingMode.AI_LISTENING, false)
        val words = session.words
        session = alignmentEngine.processRecognizedTokens(
            session,
            words.filter { it.index != 1 && it.index != 8 }.mapIndexed { i, w ->
                RecognizedSpeechToken(w.displayText, w.normalizedText, 0.95f, (i + 1) * 100L)
            }
        )
        assertTrue(session.isAwaitingUnreadWords)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("Lixo", "lixo", 0.95f, 0L),
                RecognizedSpeechToken("Hatten", "hatten", 0.95f, 0L)
            )
        )

        assertTrue(session.isCompleted)
        assertFalse(session.isAwaitingUnreadWords)
        assertTrue(session.words[1].isCompleted && session.words[1].needsPractice)
        assertTrue(session.words[8].isCompleted && session.words[8].needsPractice)
    }

    @Test
    fun finishWithUnreadWords_isNoOpInManualModeAndBeforeEnd() {
        val manualSession = alignmentEngine.startSession(
            storyText = "Katten läser i lugn takt.",
            mode = ReadingMode.MANUAL,
            isTimedChallengeRequested = false
        )
        assertEquals(manualSession, alignmentEngine.finishWithUnreadWords(manualSession))

        var aiSession = alignmentEngine.startSession(
            storyText = "Katten Lixo hittar en stjärna i skogen.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        // Skip "Lixo" mid-story; the story is not yet at the end, so finishWithUnreadWords is a no-op.
        aiSession = alignmentEngine.processRecognizedTokens(
            aiSession,
            listOf(
                RecognizedSpeechToken("katten", "katten", 0.95f, 100L),
                RecognizedSpeechToken("hittar", "hittar", 0.95f, 200L)
            )
        )
        assertFalse(aiSession.isAwaitingUnreadWords)
        assertEquals(aiSession, alignmentEngine.finishWithUnreadWords(aiSession))
    }

    @Test
    fun evaluateSessionScore_yellowPenaltyTiersAndOneStarFloor() {
        fun buildState(elapsedMillis: Long, readYellow: Int, unreadYellow: Int): ReadingAlignmentSessionState {
            val words = (0 until 20).map { idx ->
                StoryWordToken(
                    index = idx,
                    displayText = "ord$idx",
                    normalizedText = "ord$idx",
                    isCompleted = idx >= unreadYellow,
                    needsPractice = idx < readYellow || (idx >= readYellow && idx < readYellow + unreadYellow)
                )
            }
            return ReadingAlignmentSessionState(
                mode = ReadingMode.AI_LISTENING,
                isTimedChallenge = false,
                words = words,
                currentWordIndex = words.size,
                elapsedMillis = elapsedMillis,
                isCompleted = true
            )
        }

        // 120 WPM at referenceWpm=40 -> wpmStars = 3.
        val noYellow = buildState(elapsedMillis = 10_000L, readYellow = 0, unreadYellow = 0)
        assertEquals(3, alignmentEngine.evaluateSessionScore(noYellow, referenceWpm = 40, timeRequirementSeconds = 60).starsEarned)

        // 1 read yellow out of 20 -> ratio 0.05, below the 0.10 one-star-penalty threshold.
        val oneReadYellow = buildState(elapsedMillis = 10_000L, readYellow = 1, unreadYellow = 0)
        assertEquals(3, alignmentEngine.evaluateSessionScore(oneReadYellow, referenceWpm = 40, timeRequirementSeconds = 60).starsEarned)

        // 3 read yellow out of 20 -> ratio 0.15, in the [0.10, 0.25) one-star-penalty band.
        val threeReadYellow = buildState(elapsedMillis = 10_000L, readYellow = 3, unreadYellow = 0)
        assertEquals(2, alignmentEngine.evaluateSessionScore(threeReadYellow, referenceWpm = 40, timeRequirementSeconds = 60).starsEarned)

        // 3 unread yellow out of 20 -> weight 6, ratio 0.30, in the two-star-penalty band.
        val threeUnreadYellow = buildState(elapsedMillis = 10_000L, readYellow = 0, unreadYellow = 3)
        assertEquals(1, alignmentEngine.evaluateSessionScore(threeUnreadYellow, referenceWpm = 40, timeRequirementSeconds = 60).starsEarned)

        // Slow reading (12 WPM, wpmStars = 1) with 3 unread yellow must still floor at 1 star.
        val slowWithUnreadYellow = buildState(elapsedMillis = 100_000L, readYellow = 0, unreadYellow = 3)
        assertEquals(1, alignmentEngine.evaluateSessionScore(slowWithUnreadYellow, referenceWpm = 40, timeRequirementSeconds = 60).starsEarned)
    }

    private class FakeLexiconCache(
        var version: Int?,
        var entries: List<DictionaryEntry>
    ) : LexiconCache {
        var replaceCalls = 0

        override suspend fun loadVersion(): Int? = version
        override suspend fun loadEntries(): List<DictionaryEntry> = entries
        override suspend fun replace(version: Int, entries: List<DictionaryEntry>) {
            replaceCalls++
            this.version = version
            this.entries = entries
        }
    }

    @Test
    fun lexiconSync_sameVersionSkipsChunkFetch() = runTest {
        val cache = FakeLexiconCache(
            version = 3,
            entries = listOf(
                DictionaryEntry(
                    wordNormalized = "på",
                    displayWord = "på",
                    syllables = "på",
                    explanationSv = "Cachad förklaring för 'på'.",
                    exampleSentenceSv = "Boken ligger på bordet."
                )
            )
        )
        var entriesFetchCount = 0
        val fakeGateway = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
            override suspend fun fetchLexiconMeta(): LexiconMeta = LexiconMeta(version = 3, chunkCount = 1)
            override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> {
                entriesFetchCount++
                return emptyList()
            }
        }

        val repo = LixoRepository(fakeGateway, lexiconCache = cache, content = BundledTestContent.repository())
        repo.loadCachedLexicon()
        repo.syncCurriculumFromCloud()

        assertEquals(0, entriesFetchCount)
        assertEquals("Cachad förklaring för 'på'.", repo.lookupDictionaryWord("På,").explanationSv)
    }

    @Test
    fun lexiconSync_newVersionReplacesCacheAndMemory() = runTest {
        val cache = FakeLexiconCache(
            version = 1,
            entries = listOf(
                DictionaryEntry(
                    wordNormalized = "bor",
                    displayWord = "bor",
                    syllables = "bor",
                    explanationSv = "Gammal förklaring.",
                    exampleSentenceSv = "Gammal mening."
                )
            )
        )
        val fakeGateway = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
            override suspend fun fetchLexiconMeta(): LexiconMeta = LexiconMeta(version = 2, chunkCount = 1)
            override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> = listOf(
                DictionaryEntry(
                    wordNormalized = "bor",
                    displayWord = "bor",
                    syllables = "bor",
                    explanationSv = "Ny förklaring.",
                    exampleSentenceSv = "Ny mening."
                ),
                DictionaryEntry(
                    wordNormalized = "skog",
                    displayWord = "skog",
                    syllables = "skog",
                    explanationSv = "En stor plats med många träd.",
                    exampleSentenceSv = "Vi gick in i skogen."
                )
            )
        }

        val repo = LixoRepository(fakeGateway, lexiconCache = cache, content = BundledTestContent.repository())
        repo.loadCachedLexicon()
        val synced = repo.syncCurriculumFromCloud()

        assertTrue(synced)
        assertEquals(1, cache.replaceCalls)
        assertEquals(2, cache.version)
        assertEquals("Ny förklaring.", repo.lookupDictionaryWord("bor").explanationSv)
        assertEquals("En stor plats med många träd.", repo.lookupDictionaryWord("skog.").explanationSv)
        assertTrue(repo.lookupDictionaryWord("okänt_ord").explanationSv.startsWith("Lyssna på ordet"))
    }

    // --- Reward pacing (doc/plans/001-reward-pacing-early-chests.md) ---

    @Test
    fun rewardPacing_firstLevelFreshProfile_exactlyOneItemAndOneChest() {
        val engine = ProgressionAndGiftEngine(random = Random(1))
        val catalog = BundledTestContent.content.items
        val profile = ChildProfile(id = "p_fresh", displayName = "Saga", totalStars = 0)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_fresh", calendarDateIso = "2026-11-01")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(1, outcome.newlyUnlockedRewards.size)
        assertEquals(RewardSource.LEVEL, outcome.newlyUnlockedRewards.single().source)
        val steps = buildCelebrationSteps(outcome)
        assertEquals(1, steps.count { it is CelebrationStep.Chest })

        // Repeat with a valid, unowned task reward item: still exactly 1 LEVEL reward.
        // catalog.first() is the tutorial hat (pool = level), which isChestEligible excludes;
        // pick a chest-eligible standard-pool item instead.
        val taskRewardId = catalog.first { it.pool == ItemPool.STANDARD && !it.retired }.id
        val contentWithTaskReward = BundledTestContent.withDropRules {
            it.copy(levelRewards = listOf(LevelReward(mathLevel.order, taskRewardId)))
        }
        val outcomeWithTaskReward = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = contentWithTaskReward,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        assertEquals(1, outcomeWithTaskReward.newlyUnlockedRewards.size)
        assertEquals(RewardSource.LEVEL, outcomeWithTaskReward.newlyUnlockedRewards.single().source)
        assertEquals(taskRewardId, outcomeWithTaskReward.newlyUnlockedRewards.single().item.id)
    }

    @Test
    fun rewardPacing_eightMathLevelsAt3Stars_oneItemPerOnboardingLevel() {
        val engine = ProgressionAndGiftEngine(random = Random(7))
        val catalog = BundledTestContent.content.items
        var profile = ChildProfile(id = "p_math3", displayName = "Saga", totalStars = 0)
        var unlockedIds = emptySet<String>()
        val itemsPerLevel = mutableListOf<Int>()
        val today = DailyActivityRecord(profileId = "p_math3", calendarDateIso = "2026-11-01")

        for (i in 0..7) {
            val mathLevel = level(order = 2 * (i + 1), subject = Subject.MATH)
            val outcome = engine.recordLevelCompletion(
                profile = profile,
                level = mathLevel,
                previousProgress = null,
                earnedStarsThisRun = 3,
                completedInAiMode = true,
                elapsedSeconds = 20,
                achievedWpm = 0,
                completedTimedChallenge = false,
                eligibleForRepetitionGift = true,
                clearedLevelsCountBeforeRun = i,
                content = testContentFor(catalog),
                currentlyUnlockedItemIds = unlockedIds,
                todayActivityBeforeRun = today,
                nowEpochMillis = (1000L + i)
            )
            itemsPerLevel += outcome.newlyUnlockedRewards.size
            val progressionCount = outcome.newlyUnlockedRewards.count {
                it.source == RewardSource.LEVEL || it.source == RewardSource.STARS
            }
            assertTrue(progressionCount <= 1, "Run $i must not award more than 1 progression item")
            profile = outcome.updatedProfile
            unlockedIds = unlockedIds + outcome.newlyUnlockedItems.map { it.id }
        }

        for (levelIndex in 0..5) {
            assertTrue(itemsPerLevel[levelIndex] >= 1, "Level ${levelIndex + 1} must award >= 1 item")
        }
        assertTrue(itemsPerLevel.sum() >= 7, "At least 7 items total across 8 levels, got ${itemsPerLevel.sum()}")
    }

    @Test
    fun rewardPacing_eightMathLevelsAt1Star_oneItemPerOnboardingLevel() {
        val engine = ProgressionAndGiftEngine(random = Random(7))
        val catalog = BundledTestContent.content.items
        var profile = ChildProfile(id = "p_math1", displayName = "Saga", totalStars = 0)
        var unlockedIds = emptySet<String>()
        val rewardsPerLevel = mutableListOf<List<RewardSource>>()
        val today = DailyActivityRecord(profileId = "p_math1", calendarDateIso = "2026-11-01")

        for (i in 0..7) {
            val mathLevel = level(order = 2 * (i + 1), subject = Subject.MATH)
            val outcome = engine.recordLevelCompletion(
                profile = profile,
                level = mathLevel,
                previousProgress = null,
                earnedStarsThisRun = 1,
                completedInAiMode = true,
                elapsedSeconds = 20,
                achievedWpm = 0,
                completedTimedChallenge = false,
                eligibleForRepetitionGift = true,
                clearedLevelsCountBeforeRun = i,
                content = testContentFor(catalog),
                currentlyUnlockedItemIds = unlockedIds,
                todayActivityBeforeRun = today,
                nowEpochMillis = (1000L + i)
            )
            rewardsPerLevel += outcome.newlyUnlockedRewards.map { it.source }
            profile = outcome.updatedProfile
            unlockedIds = unlockedIds + outcome.newlyUnlockedItems.map { it.id }
        }

        for (levelIndex in 0..5) {
            assertTrue(
                rewardsPerLevel[levelIndex].contains(RewardSource.LEVEL),
                "Level ${levelIndex + 1} at 1 star must award a LEVEL reward"
            )
        }
    }

    @Test
    fun rewardPacing_fortyLevelsAt2Stars_noSourceTwiceAndNoLongDroughts() {
        val engine = ProgressionAndGiftEngine(random = Random(11))
        val catalog = BundledTestContent.content.items
        var profile = ChildProfile(id = "p_math2", displayName = "Saga", totalStars = 0)
        var unlockedIds = emptySet<String>()
        val itemsPerLevel = mutableListOf<Int>()
        val today = DailyActivityRecord(profileId = "p_math2", calendarDateIso = "2026-11-01")

        for (i in 0 until 40) {
            val mathLevel = level(order = 2 * (i + 1), subject = Subject.MATH)
            val outcome = engine.recordLevelCompletion(
                profile = profile,
                level = mathLevel,
                previousProgress = null,
                earnedStarsThisRun = 2,
                completedInAiMode = true,
                elapsedSeconds = 20,
                achievedWpm = 0,
                completedTimedChallenge = false,
                eligibleForRepetitionGift = true,
                clearedLevelsCountBeforeRun = i,
                content = testContentFor(catalog),
                currentlyUnlockedItemIds = unlockedIds,
                todayActivityBeforeRun = today,
                nowEpochMillis = (1000L + i)
            )
            val sourceCounts = outcome.newlyUnlockedRewards.groupingBy { it.source }.eachCount()
            for ((source, count) in sourceCounts) {
                if (source == RewardSource.LEVEL || source == RewardSource.STARS) continue
                assertTrue(count <= 1, "Run $i must not award a source more than once: $source")
            }
            val progressionCount = sourceCounts.filterKeys {
                it == RewardSource.LEVEL || it == RewardSource.STARS
            }.values.sum()
            assertTrue(progressionCount <= 1, "Run $i must not award more than 1 progression item")
            itemsPerLevel += outcome.newlyUnlockedRewards.size
            profile = outcome.updatedProfile
            unlockedIds = unlockedIds + outcome.newlyUnlockedItems.map { it.id }
        }

        fun longestDroughtWithin(upTo: Int): Int {
            var longest = 0
            var current = 0
            for (i in 0 until upTo) {
                if (itemsPerLevel[i] == 0) {
                    current += 1
                    longest = maxOf(longest, current)
                } else {
                    current = 0
                }
            }
            return longest
        }

        assertTrue(longestDroughtWithin(20) <= 3, "Longest drought within levels 1-20 must be <= 3")
        assertTrue(longestDroughtWithin(40) <= 5, "Longest drought within levels 1-40 must be <= 5")
    }

    @Test
    fun rewardPacing_replayNeverTriggersOnboardingItem() {
        val engine = ProgressionAndGiftEngine(random = Random(3))
        val catalog = BundledTestContent.content.items
        val profile = ChildProfile(id = "p_replay", displayName = "Saga", totalStars = 0)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_replay", calendarDateIso = "2026-11-01")
        val previousProgress = LevelProgress(
            profileId = "p_replay",
            levelId = mathLevel.id,
            levelOrder = mathLevel.order,
            starsEarned = 2,
            completedInAiMode = true,
            bestTimeSeconds = 20,
            bestWpm = 0
        )

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = previousProgress,
            earnedStarsThisRun = 2,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertTrue(outcome.newlyUnlockedRewards.none { it.source == RewardSource.LEVEL })
    }

    @Test
    fun rewardPacing_jumpTo100Stars_onlyOneStarItemPerRun() {
        val engine = ProgressionAndGiftEngine(random = Random(5))
        val catalog = BundledTestContent.content.items
        val profile = ChildProfile(id = "p_100", displayName = "Saga", totalStars = 100)
        val mathLevel = level(order = 14, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_100", calendarDateIso = "2026-11-01")

        val eligibleSorted = BundledTestContent.content.dropRules.starUnlocks
            .filter { it.stars in 1..100 && it.itemId !in BundledTestContent.content.achievementRewardItemIds }
            .mapNotNull { unlock -> BundledTestContent.content.itemsById[unlock.itemId]?.let { unlock.stars to it } }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 0,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        val starRewards = outcome.newlyUnlockedRewards.filter { it.source == RewardSource.STARS }
        assertEquals(1, starRewards.size)
        assertEquals(eligibleSorted.first().id, starRewards.single().item.id)

        val nextProfile = outcome.updatedProfile
        val nextUnlockedIds = outcome.newlyUnlockedItems.map { it.id }.toSet()
        val nextLevel = level(order = 16, subject = Subject.MATH)
        val nextOutcome = engine.recordLevelCompletion(
            profile = nextProfile,
            level = nextLevel,
            previousProgress = null,
            earnedStarsThisRun = 0,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 8,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = nextUnlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 1001L
        )
        val nextStarRewards = nextOutcome.newlyUnlockedRewards.filter { it.source == RewardSource.STARS }
        assertEquals(1, nextStarRewards.size)
        assertEquals(eligibleSorted[1].id, nextStarRewards.single().item.id)
    }

    @Test
    fun rewardPacing_levelFlitkistaAndStreakStack_inOrder() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val profile = ChildProfile(
            id = "p_stack",
            displayName = "Saga",
            totalStars = 0,
            chestProgressHalfPoints = 18,
            currentStreakDays = 6,
            lastStreakDateIso = "2026-10-31"
        )
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(
            profileId = "p_stack",
            calendarDateIso = "2026-11-01",
            readingTasksCompleted = 1
        )

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        val sources = outcome.newlyUnlockedRewards.map { it.source }
        assertEquals(listOf(RewardSource.LEVEL, RewardSource.FLITKISTA, RewardSource.WEEKLY_STREAK), sources)

        val chestSources = buildCelebrationSteps(outcome)
            .filterIsInstance<CelebrationStep.Chest>()
            .map { it.source }
        assertEquals(sources, chestSources)
    }

    @Test
    fun rewardPacing_onboardingAndStarThresholdSameRun_starDeferred() {
        val engine = ProgressionAndGiftEngine(random = Random(13))
        val content = BundledTestContent.content
        val preOwned = setOf("item_hand_right_math_sword_1")
        val first = content.dropRules.starUnlocks
            .filter { it.itemId !in preOwned && it.itemId !in content.achievementRewardItemIds && it.stars > 0 }
            .minOf { it.stars }
        assertTrue(first >= 25, "First curve threshold must be >= 25, was $first")

        val profile = ChildProfile(id = "p_defer", displayName = "Saga", totalStars = first - 1)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_defer", calendarDateIso = "2026-11-01")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = content,
            currentlyUnlockedItemIds = preOwned,
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(listOf(RewardSource.LEVEL), outcome.newlyUnlockedRewards.map { it.source })
        assertTrue(outcome.newlyUnlockedRewards.none { content.starRequirementByItemId[it.item.id] == first }, "Star item must be deferred on an onboarding run")

        val nextProfile = outcome.updatedProfile
        val nextUnlockedIds = preOwned + outcome.newlyUnlockedItems.map { it.id }
        val nextLevel = level(order = 16, subject = Subject.MATH)
        val nextOutcome = engine.recordLevelCompletion(
            profile = nextProfile,
            level = nextLevel,
            previousProgress = null,
            earnedStarsThisRun = 0,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 6,
            content = content,
            currentlyUnlockedItemIds = nextUnlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 1001L
        )
        val starRewards = nextOutcome.newlyUnlockedRewards.filter { it.source == RewardSource.STARS }
        assertEquals(1, starRewards.size)
        assertEquals(first, content.starRequirementByItemId[starRewards.single().item.id])
    }

    // --- Level reward item ids (doc/plans/002-level-reward-item-ids.md) ---

    @Test
    fun levelRewardIds_seedTasksResolveToUniqueChestEligibleCatalogItems() {
        val content = BundledTestContent.content
        val (levels, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(content.dropRules.levelRewards)
        val levelsById = levels.associateBy { it.id }

        val ids = mutableListOf<String>()
        // Geography (orders 161..190, plan 037) has no curated levelRewards: onboarding falls
        // back to rollOnboardingItem, so those tasks have no rewardItemId.
        for (task in tasks.values.filter { it.subject != Subject.GEOGRAPHY }) {
            val rewardItemId = task.rewardItemId
            assertNotNull(rewardItemId, "${task.id} must have a rewardItemId")
            val item = content.itemsById[rewardItemId]
            assertNotNull(item, "${task.id} rewardItemId=$rewardItemId must exist in the catalog")
            assertTrue(ProgressionAndGiftEngine.isChestEligible(item), "${task.id} rewardItemId=$rewardItemId must be chest-eligible")
            assertTrue(rewardItemId != content.dropRules.tutorialRewardItemId, "${task.id} must not reward the tutorial starter hat")
            assertTrue(ProgressionAndGiftEngine.isOnboardingPreferred(item, content), "${task.id} rewardItemId=$rewardItemId must be onboarding-preferred")
            ids += rewardItemId

            val order = levelsById[task.levelId]?.order
            assertNotNull(order, "${task.id} must reference a known level")
            assertEquals(rewardItemId, content.levelRewardItemId(order))
        }
        assertEquals(ids.size, ids.distinct().size, "All 160 level reward item ids must be distinct")
    }

    @Test
    fun levelRewardIds_achievementRewardItemsExistAndAreLinked() {
        val content = BundledTestContent.content

        for ((achievementId, rewardItemId) in content.dropRules.achievementRewards) {
            val item = content.itemsById[rewardItemId]
            assertNotNull(item, "Achievement $achievementId rewardItemId=$rewardItemId must exist in the catalog")
            assertTrue(
                item.pool == ItemPool.ACHIEVEMENT || item.pool == ItemPool.SETPIECE,
                "Achievement $achievementId reward $rewardItemId must be pool ACHIEVEMENT or SETPIECE, was ${item.pool}"
            )
        }
    }

    @Test
    fun syncCurriculumFromCloud_ignoresRemoteRewardItemIdAndComputesForRemoteOnlyTask() = runTest {
        val gw = object : CloudSyncGateway by FirebaseSyncService() {
            override suspend fun fetchRemoteCourses(): List<Course> = listOf(
                Course(
                    id = "course_sv_1",
                    title = "Svenska Sagor: Viskande Skogen",
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
                    title = "Bana 1",
                    biomeNameSv = "Viskande Skogen",
                    subject = Subject.SWEDISH,
                    difficulty = 1,
                    timeRequirementSeconds = 55,
                    referenceWpm = 40,
                    taskId = "task_1"
                ),
                CampaignLevel(
                    id = "level_161",
                    courseId = "course_sv_1",
                    order = 161,
                    title = "Bana 161",
                    biomeNameSv = "Viskande Skogen",
                    subject = Subject.SWEDISH,
                    difficulty = 1,
                    timeRequirementSeconds = 55,
                    referenceWpm = 40,
                    taskId = "task_161"
                )
            )

            override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = listOf(
                HomeworkTask(
                    id = "task_1",
                    levelId = "level_1",
                    type = TaskType.READING,
                    subject = Subject.SWEDISH,
                    textContent = "Katten Lixo smyger över den gamla träbron i skogen.",
                    rewardItemId = "item_hat_2"
                ),
                HomeworkTask(
                    id = "task_161",
                    levelId = "level_161",
                    type = TaskType.READING,
                    subject = Subject.SWEDISH,
                    textContent = "En ny saga om Lixo.",
                    rewardItemId = "item_hat_2"
                )
            )

            override suspend fun fetchLexiconMeta(): LexiconMeta? = null
        }

        val repo = LixoRepository(gw, lexiconCache = null, profileStore = null, content = BundledTestContent.repository())
        val seededTask1RewardItemId = repo.tasksById.value.getValue("task_1").rewardItemId
        assertNotNull(seededTask1RewardItemId)
        assertTrue(seededTask1RewardItemId != "item_hat_2")

        repo.syncCurriculumFromCloud()

        val task1 = repo.tasksById.value.getValue("task_1")
        assertEquals(seededTask1RewardItemId, task1.rewardItemId, "Local task_1 must keep its seeded rewardItemId")
        assertTrue(task1.rewardItemId != "item_hat_2", "Remote rewardItemId must be ignored")

        val task161 = repo.tasksById.value.getValue("task_161")
        // Level 161 has no levelRewards entry in the pack, so the curated reward is null; the
        // onboarding roll covers it at play time instead (022 behaviour change).
        assertNull(task161.rewardItemId)
    }

    private fun geoSyncGateway(remoteTasks: List<HomeworkTask>) = object : CloudSyncGateway by FirebaseSyncService() {
        override suspend fun fetchRemoteCourses(): List<Course> = listOf(
            Course(
                id = "course_geo_1",
                title = "Världsresan",
                subject = Subject.GEOGRAPHY,
                minGrade = 1,
                version = 2,
                updatedAt = 1793558400000L
            )
        )

        override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> =
            listOf(GeographyCurriculum.buildLevel(1))

        override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = remoteTasks

        override suspend fun fetchLexiconMeta(): LexiconMeta? = null
    }

    @Test
    fun syncCurriculumFromCloud_excludesInvalidFlagQuestions() = runTest {
        val validQuestions = listOf(
            FlagQuestion("r1", "no", listOf("se", "dk", "fi")),
            FlagQuestion("r2", "dk", listOf("se", "no", "fi")),
            FlagQuestion("r3", "fi", listOf("se", "no", "dk")),
            FlagQuestion("r4", "is", listOf("se", "no", "dk")),
            FlagQuestion("r5", "US", listOf("GB", "DE", "FR"))
        )
        val invalidQuestions = listOf(
            FlagQuestion("bad1", "xx", listOf("se", "no", "dk")),
            FlagQuestion("bad2", "se", listOf("no", "no", "dk")),
            FlagQuestion("bad3", "se", listOf("no", "dk"))
        )
        val remoteTask = HomeworkTask(
            id = "task_161",
            levelId = "level_161",
            type = TaskType.GEOGRAPHY_FLAGS,
            subject = Subject.GEOGRAPHY,
            flagQuestions = validQuestions + invalidQuestions
        )

        val repo = LixoRepository(geoSyncGateway(listOf(remoteTask)), lexiconCache = null, profileStore = null, content = BundledTestContent.repository())
        repo.syncCurriculumFromCloud()

        val task161 = repo.tasksById.value.getValue("task_161")
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), task161.flagQuestions.map { it.id })
        assertTrue(task161.flagQuestions.all(GeographyChoices::isValid))
        assertEquals("us", task161.flagQuestions.last().countryIso2)
    }

    @Test
    fun syncCurriculumFromCloud_allInvalidFlagTask_keepsSeededTask() = runTest {
        val remoteTask = HomeworkTask(
            id = "task_161",
            levelId = "level_161",
            type = TaskType.GEOGRAPHY_FLAGS,
            subject = Subject.GEOGRAPHY,
            flagQuestions = listOf(
                FlagQuestion("bad1", "xx", listOf("se", "no", "dk")),
                FlagQuestion("bad2", "se", listOf("no", "no", "dk")),
                FlagQuestion("bad3", "se", listOf("no", "dk"))
            )
        )

        val repo = LixoRepository(geoSyncGateway(listOf(remoteTask)), lexiconCache = null, profileStore = null, content = BundledTestContent.repository())
        val seeded = repo.tasksById.value.getValue("task_161")

        repo.syncCurriculumFromCloud()

        val task161 = repo.tasksById.value.getValue("task_161")
        assertEquals(seeded, task161)
        assertEquals(8, task161.flagQuestions.size)
        assertTrue(task161.flagQuestions.all(GeographyChoices::isValid))
    }

    @Test
    fun levelReward_onboardingSlotFirstClearAwardsCuratedItem() {
        val engine = ProgressionAndGiftEngine(random = Random(3))
        val catalog = BundledTestContent.content.items
        val (_, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val curatedId = tasks.getValue("task_2").rewardItemId
        assertNotNull(curatedId)
        val profile = ChildProfile(id = "p_curated", displayName = "Saga", totalStars = 0)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_curated", calendarDateIso = "2026-11-01")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertEquals(listOf(RewardSource.LEVEL), outcome.newlyUnlockedRewards.map { it.source })
        assertEquals(curatedId, outcome.newlyUnlockedRewards.single().item.id)
    }

    @Test
    fun levelReward_offScheduleFirstClearAwardsNoLevelItem() {
        val engine = ProgressionAndGiftEngine(random = Random(3))
        val catalog = BundledTestContent.content.items
        val (_, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val curatedId = tasks.getValue("task_2").rewardItemId
        assertNotNull(curatedId)
        val profile = ChildProfile(id = "p_offschedule", displayName = "Saga", totalStars = 0)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_offschedule", calendarDateIso = "2026-11-01")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 6,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertTrue(outcome.newlyUnlockedRewards.none { it.source == RewardSource.LEVEL })
        assertTrue(outcome.newlyUnlockedRewards.none { it.item.id == curatedId })
    }

    @Test
    fun levelReward_ownedOrUnknownIdFallsBackToRoll() {
        val catalog = BundledTestContent.content.items
        val (_, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val curatedId = tasks.getValue("task_2").rewardItemId
        assertNotNull(curatedId)
        val today = DailyActivityRecord(profileId = "p_fallback", calendarDateIso = "2026-11-01")

        val ownedEngine = ProgressionAndGiftEngine(random = Random(5))
        val ownedOutcome = ownedEngine.recordLevelCompletion(
            profile = ChildProfile(id = "p_fallback", displayName = "Saga", totalStars = 0),
            level = level(order = 2, subject = Subject.MATH),
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = setOf(curatedId),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        val ownedLevelRewards = ownedOutcome.newlyUnlockedRewards.filter { it.source == RewardSource.LEVEL }
        assertEquals(1, ownedLevelRewards.size)
        assertTrue(ownedLevelRewards.single().item.id != curatedId)

        val unknownEngine = ProgressionAndGiftEngine(random = Random(5))
        val unknownContent = BundledTestContent.withDropRules {
            it.copy(levelRewards = listOf(LevelReward(2, "item_hat_2")))
        }
        val unknownOutcome = unknownEngine.recordLevelCompletion(
            profile = ChildProfile(id = "p_fallback", displayName = "Saga", totalStars = 0),
            level = level(order = 2, subject = Subject.MATH),
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 0,
            content = unknownContent,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )
        val unknownLevelRewards = unknownOutcome.newlyUnlockedRewards.filter { it.source == RewardSource.LEVEL }
        assertEquals(1, unknownLevelRewards.size)
        assertTrue(unknownLevelRewards.single().item.id != "item_hat_2")
    }

    @Test
    fun levelReward_replayNeverAwardsCuratedItem() {
        val engine = ProgressionAndGiftEngine(random = Random(9))
        val catalog = BundledTestContent.content.items
        val (_, tasks) = LixoRepository.buildSeedCampaignLevelsAndTasks(BundledTestContent.content.dropRules.levelRewards)
        val curatedId = tasks.getValue("task_2").rewardItemId
        assertNotNull(curatedId)
        val profile = ChildProfile(id = "p_replay", displayName = "Saga", totalStars = 0)
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_replay", calendarDateIso = "2026-11-01")
        val previousProgress = LevelProgress(
            profileId = profile.id,
            levelId = mathLevel.id,
            levelOrder = mathLevel.order,
            starsEarned = 1,
            completedInAiMode = true,
            bestTimeSeconds = 30,
            bestWpm = 0,
            timesCompleted = 1,
            updatedAtEpochMillis = 500L
        )

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = previousProgress,
            earnedStarsThisRun = 2,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = testContentFor(catalog),
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L
        )

        assertTrue(outcome.newlyUnlockedRewards.none { it.source == RewardSource.LEVEL })
        assertTrue(outcome.newlyUnlockedRewards.none { it.item.id == curatedId })
    }
}

private class ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
