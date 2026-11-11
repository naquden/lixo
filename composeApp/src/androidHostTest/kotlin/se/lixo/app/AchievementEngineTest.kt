package se.lixo.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.engine.AchievementState
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RunFacts
import se.lixo.app.domain.model.ACHIEVEMENT_LADDERS
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AchievementAction
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.HANDMADE_ACHIEVEMENT_IDS
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.achievementId
import se.lixo.app.domain.content.UnlockType

class AchievementEngineTest {

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

    private fun mathProblem(type: MathProblemType, id: String): MathProblem = MathProblem(
        id = id,
        type = type,
        promptSv = "?",
        correctAnswer = "1",
        hintStep1Sv = "h1",
        hintStep2Sv = "h2"
    )

    /** The achievement id (if any) whose reward item is [itemId], from the bundled pack's dropRules. */
    private fun achievementIdForItem(itemId: String): String? =
        BundledTestContent.content.dropRules.achievementRewards.entries.firstOrNull { it.value == itemId }?.key

    /** Only the 12 legacy achievement ids, so old behavior-focused tests aren't tripped up by the other 36 new tiers. */
    private fun legacyDefs(): List<Achievement> {
        val legacyIds = HANDMADE_ACHIEVEMENT_IDS + BalanceConfig.STAR_ACHIEVEMENT_MILESTONES.map(BalanceConfig::starAchievementId)
        return LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.id in legacyIds }
    }

    // --- (a) Speed reader ---

    @Test
    fun speedReader_countsOnlyEligibleAiRuns_distinctLevels_unlocksAt5() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).single { it.id == "ach_speed_reader_5" }
        var state = AchievementState()

        // AI run at exactly reference WPM counts.
        var eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "level_1", isAiMode = true, achievedWpm = 40, referenceWpm = 40),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(1, state.progress["ach_speed_reader_5"])

        // achievedWpm < referenceWpm doesn't count.
        eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "level_2", isAiMode = true, achievedWpm = 30, referenceWpm = 40),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(1, state.progress["ach_speed_reader_5"])

        // Manual mode doesn't count even if fast.
        eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "level_2", isAiMode = false, achievedWpm = 100, referenceWpm = 40),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(1, state.progress["ach_speed_reader_5"])

        // Same levelId twice counts once.
        eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "level_1", isAiMode = true, achievedWpm = 50, referenceWpm = 40),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(1, state.progress["ach_speed_reader_5"])
        assertTrue(eval.newlyUnlocked.isEmpty())

        // A math run (wpm 0/0) never counts.
        eval = engine.evaluate(
            RunFacts(Subject.MATH, "level_3", isAiMode = true),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(1, state.progress["ach_speed_reader_5"])

        // 4 more distinct levels unlock it (5th distinct level).
        for (i in 4..7) {
            eval = engine.evaluate(
                RunFacts(Subject.SWEDISH, "level_$i", isAiMode = true, achievedWpm = 40, referenceWpm = 40),
                state,
                listOf(def)
            )
            state = eval.state
        }
        assertEquals(5, state.progress["ach_speed_reader_5"])
        assertTrue("ach_speed_reader_5" in state.unlockedIds)
        assertEquals(listOf(def), eval.newlyUnlocked)
    }

    // --- (b) Pronunciation ---

    @Test
    fun pronunciationMaster_requiresAiAndEligible_unlocksAt10() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).single { it.id == "ach_pronunciation_master_10" }
        var state = AchievementState()

        var eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l1", isAiMode = false, pronunciationEligible = true),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(0, state.progress["ach_pronunciation_master_10"] ?: 0)

        eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l1", isAiMode = true, pronunciationEligible = false),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(0, state.progress["ach_pronunciation_master_10"] ?: 0)

        for (i in 1..10) {
            eval = engine.evaluate(
                RunFacts(Subject.SWEDISH, "l$i", isAiMode = true, pronunciationEligible = true),
                state,
                listOf(def)
            )
            state = eval.state
        }
        assertEquals(10, state.progress["ach_pronunciation_master_10"])
        assertTrue("ach_pronunciation_master_10" in state.unlockedIds)
    }

    // --- (c) Clock/money first-try, using the real MathEngine ---

    @Test
    fun clockMoneyFirstTry_mathSessionAggregatesCorrectly_progressPlus5_unlocksAndCapsAt15() {
        val states = listOf(
            MathProblemAttemptState(mathProblem(MathProblemType.CLOCK, "c1"), isSolved = true),
            MathProblemAttemptState(mathProblem(MathProblemType.CLOCK, "c2"), isSolved = true),
            MathProblemAttemptState(mathProblem(MathProblemType.CLOCK, "c3"), isSolved = true),
            MathProblemAttemptState(mathProblem(MathProblemType.MONEY, "m1"), isSolved = true),
            MathProblemAttemptState(mathProblem(MathProblemType.MONEY, "m2"), isSolved = true),
            MathProblemAttemptState(mathProblem(MathProblemType.CLOCK, "c4"), isSolved = true, hintsRevealed = 1),
            MathProblemAttemptState(mathProblem(MathProblemType.ADDITION, "a1"), isSolved = true)
        )
        val score = MathEngine().evaluateMathSession(
            states = states,
            elapsedSeconds = 10,
            timeRequirementSeconds = 30,
            isTimedChallenge = false
        )
        assertEquals(
            mapOf(MathProblemType.CLOCK to 3, MathProblemType.MONEY to 2, MathProblemType.ADDITION to 1),
            score.firstTryCorrectByType
        )

        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).single { it.id == "ach_math_clock_money" }
        var state = AchievementState(progress = mapOf(def.id to 7, def.action!!.let { "chain:${it.name}" } to 7))

        var eval = engine.evaluate(
            RunFacts(Subject.MATH, "l1", isAiMode = true, mathFirstTryByType = score.firstTryCorrectByType),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(12, state.progress[def.id])
        assertTrue(eval.newlyUnlocked.isEmpty())

        eval = engine.evaluate(
            RunFacts(Subject.MATH, "l2", isAiMode = true, mathFirstTryByType = mapOf(MathProblemType.CLOCK to 10)),
            state,
            listOf(def)
        )
        state = eval.state
        assertEquals(15, state.progress[def.id])
        assertTrue(def.id in state.unlockedIds)
    }

    // --- (d) Streak ---

    @Test
    fun weeklyStreak_unlocksAt7_onlyOnce() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).single { it.id == "ach_weekly_streak_7" }
        var state = AchievementState()

        var eval = engine.evaluate(RunFacts(Subject.MATH, "l1", isAiMode = true, streakDaysAfterRun = 6), state, listOf(def))
        state = eval.state
        assertEquals(6, state.progress[def.id])
        assertFalse(def.id in state.unlockedIds)

        eval = engine.evaluate(RunFacts(Subject.MATH, "l2", isAiMode = true, streakDaysAfterRun = 7), state, listOf(def))
        state = eval.state
        assertEquals(7, state.progress[def.id])
        assertTrue(def.id in state.unlockedIds)
        assertEquals(listOf(def), eval.newlyUnlocked)

        eval = engine.evaluate(RunFacts(Subject.MATH, "l3", isAiMode = true, streakDaysAfterRun = 8), state, listOf(def))
        assertTrue(eval.newlyUnlocked.isEmpty())
    }

    // --- (e) requiresAiMode / already-unlocked ---

    @Test
    fun requiresAiMode_enforcedAndAlreadyUnlockedNeverRetriggers() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).single { it.id == "ach_speed_reader_5" }
        assertTrue(def.requiresAiMode)

        val eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l1", isAiMode = false, achievedWpm = 100, referenceWpm = 10),
            AchievementState(),
            listOf(def)
        )
        assertEquals(0, eval.state.progress["ach_speed_reader_5"] ?: 0)

        val unlocked = AchievementState(unlockedIds = setOf(def.id))
        val eval2 = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l99", isAiMode = true, achievedWpm = 100, referenceWpm = 10),
            unlocked,
            listOf(def)
        )
        // Chain counters now always update, even for an already-unlocked tier, so only the
        // unlock set (not the whole state) is guaranteed to stay unchanged.
        assertTrue(eval2.newlyUnlocked.isEmpty())
        assertEquals(unlocked.unlockedIds, eval2.state.unlockedIds)
    }

    // --- (f) recordLevelCompletion integration ---

    private fun baseMathProfile(id: String, totalStars: Int): ChildProfile =
        ChildProfile(id = id, displayName = "Saga", totalStars = totalStars)

    @Test
    fun recordLevelCompletion_19Stars_noAchievement_20Stars_unlocksAchStars20() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p19", calendarDateIso = "2026-11-01")

        val profile19 = baseMathProfile("p19", totalStars = 17)
        val outcome19 = engine.recordLevelCompletion(
            profile = profile19,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 2,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, mathLevel.id, isAiMode = true)
        )
        assertEquals(19, outcome19.updatedProfile.totalStars)
        assertTrue(outcome19.newlyUnlockedRewards.none { it.source == RewardSource.ACHIEVEMENT })

        val profile20 = outcome19.updatedProfile
        val outcome20 = engine.recordLevelCompletion(
            profile = profile20,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 51,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = outcome19.newlyUnlockedItems.map { it.id }.toSet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 2L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "level_other", isAiMode = true)
        )
        assertEquals(20, outcome20.updatedProfile.totalStars)
        val achievementReward = outcome20.newlyUnlockedRewards.single { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_20", outcome20.newlyUnlockedAchievements.single().id)
        assertEquals("ach_stars_20", achievementIdForItem(achievementReward.item.id))
        assertEquals("Stjärnfrö", achievementReward.labelDetailSv)
    }

    @Test
    fun recordLevelCompletion_profileAt100Stars_unlocksOnePendingPerRun() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p100", calendarDateIso = "2026-11-01")

        var profile = baseMathProfile("p100", totalStars = 100)
        var unlockedIds = emptySet<String>()

        // Crossing 100 -> 101 total stars unlocks ach_stars_20/45/75 all at once (all <= 101), but
        // only 1 chest is awarded this run; the rest queue in pendingAchievementRewardIds.
        val outcome1 = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 200,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = unlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "l100a", isAiMode = true)
        )
        assertEquals(
            setOf("ach_stars_20", "ach_stars_45", "ach_stars_75"),
            outcome1.newlyUnlockedAchievements.map { it.id }.toSet()
        )
        val awardedNow1 = outcome1.newlyUnlockedRewards.filter { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_20", achievementIdForItem(awardedNow1.single().item.id))
        assertEquals(setOf("ach_stars_45", "ach_stars_75"), outcome1.updatedProfile.pendingAchievementRewardIds)
        assertEquals(
            setOf("ach_stars_20", "ach_stars_45", "ach_stars_75"),
            outcome1.updatedProfile.unlockedAchievementIds
        )
        profile = outcome1.updatedProfile
        unlockedIds = unlockedIds + outcome1.newlyUnlockedItems.map { it.id }

        val outcome2 = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 201,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = unlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 2L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "l100b", isAiMode = true)
        )
        val awardedNow2 = outcome2.newlyUnlockedRewards.filter { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_45", achievementIdForItem(awardedNow2.single().item.id))
        assertEquals(setOf("ach_stars_75"), outcome2.updatedProfile.pendingAchievementRewardIds)
        profile = outcome2.updatedProfile
        unlockedIds = unlockedIds + outcome2.newlyUnlockedItems.map { it.id }

        val outcome3 = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 202,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = unlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 3L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "l100c", isAiMode = true)
        )
        val awardedNow3 = outcome3.newlyUnlockedRewards.filter { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_75", achievementIdForItem(awardedNow3.single().item.id))
        assertTrue(outcome3.updatedProfile.pendingAchievementRewardIds.isEmpty())
    }

    @Test
    fun recordLevelCompletion_replayWithNoNetNewStars_addsNothing() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p19r", calendarDateIso = "2026-11-01")
        val profile = baseMathProfile("p19r", totalStars = 19).copy(
            // Already at 3 stars for this level, so a replay at 3 stars nets 0 new stars.
        )
        val previousProgress = se.lixo.app.domain.model.LevelProgress(
            profileId = "p19r",
            levelId = mathLevel.id,
            levelOrder = mathLevel.order,
            starsEarned = 3,
            completedInAiMode = true,
            bestTimeSeconds = 10,
            bestWpm = 0
        )

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = mathLevel,
            previousProgress = previousProgress,
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = true,
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, mathLevel.id, isAiMode = true)
        )
        assertEquals(19, outcome.updatedProfile.totalStars)
        assertTrue(outcome.newlyUnlockedRewards.none { it.source == RewardSource.ACHIEVEMENT })
    }

    @Test
    fun recordLevelCompletion_achievementStacksWithFlitkista_inOrder() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_stack2", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(
            id = "p_stack2",
            displayName = "Saga",
            totalStars = 19,
            chestProgressHalfPoints = 18
        )

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
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, mathLevel.id, isAiMode = true)
        )

        val sources = outcome.newlyUnlockedRewards.map { it.source }
        assertTrue(sources.contains(RewardSource.FLITKISTA))
        assertTrue(sources.contains(RewardSource.ACHIEVEMENT))
        assertEquals(RewardSource.FLITKISTA, sources[sources.indexOf(RewardSource.FLITKISTA)])
        assertTrue(sources.indexOf(RewardSource.FLITKISTA) < sources.indexOf(RewardSource.ACHIEVEMENT))
    }

    @Test
    fun recordLevelCompletion_twoUnlocksInOneRun_oneNowOneNextRun() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_two", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(
            id = "p_two",
            displayName = "Saga",
            totalStars = 19,
            achievementProgress = mapOf("ach_math_clock_money" to 14, "chain:CLOCK_MONEY" to 14)
        )

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
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(
                Subject.MATH,
                mathLevel.id,
                isAiMode = true,
                mathFirstTryByType = mapOf(MathProblemType.CLOCK to 1)
            )
        )

        assertEquals(2, outcome.newlyUnlockedAchievements.size)
        assertEquals(1, outcome.newlyUnlockedRewards.count { it.source == RewardSource.ACHIEVEMENT })
        assertEquals(1, outcome.updatedProfile.pendingAchievementRewardIds.size)
    }

    @Test
    fun recordLevelCompletion_alreadyOwnedRewardItem_noChestButUnlocked() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val def = achievements.single { it.id == "ach_stars_20" }
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_owned", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(id = "p_owned", displayName = "Saga", totalStars = 19)

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
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = setOf(def.rewardItemId),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, mathLevel.id, isAiMode = true)
        )

        assertEquals(listOf(def), outcome.newlyUnlockedAchievements)
        assertTrue(outcome.newlyUnlockedRewards.none { it.source == RewardSource.ACHIEVEMENT })
        assertTrue("ach_stars_20" in outcome.updatedProfile.unlockedAchievementIds)
        assertTrue("ach_stars_20" !in outcome.updatedProfile.pendingAchievementRewardIds)
    }

    @Test
    fun recordLevelCompletion_day7Streak_bothWeeklyStreakAndAchievementAwarded() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = legacyDefs()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(
            profileId = "p_day7",
            calendarDateIso = "2026-11-07",
            readingTasksCompleted = 1
        )
        val profile = ChildProfile(
            id = "p_day7",
            displayName = "Saga",
            currentStreakDays = 6,
            lastStreakDateIso = "2026-11-06"
        )

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
            clearedLevelsCountBeforeRun = 50,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, mathLevel.id, isAiMode = true)
        )

        val sources = outcome.newlyUnlockedRewards.map { it.source }
        assertTrue(sources.contains(RewardSource.WEEKLY_STREAK))
        assertTrue(sources.contains(RewardSource.ACHIEVEMENT))
        // item_hat_weekly_hero is the ach_weekly_streak_7 reward item, so the step-6
        // WEEKLY_STREAK filter (not an achievement reward item) skips it in favor of another
        // set piece, leaving it free for the ACHIEVEMENT step to award.
        val achievementReward = outcome.newlyUnlockedRewards.single { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("item_hat_weekly_hero", achievementReward.item.id)
        val weeklyStreakReward = outcome.newlyUnlockedRewards.single { it.source == RewardSource.WEEKLY_STREAK }
        assertTrue(weeklyStreakReward.item.id != "item_hat_weekly_hero")
    }

    // --- (g) Catalog invariants ---

    @Test
    fun starAchievements_catalogInvariantsHold() {
        val raritiesByStars = mapOf(
            20 to Rarity.RARE, 45 to Rarity.RARE, 75 to Rarity.RARE,
            120 to Rarity.EPIC, 175 to Rarity.EPIC, 250 to Rarity.EPIC,
            350 to Rarity.LEGENDARY, 480 to Rarity.LEGENDARY
        )
        val milestones = BalanceConfig.STAR_ACHIEVEMENT_MILESTONES
        assertEquals(milestones.sorted(), milestones)
        assertEquals(milestones.distinct(), milestones)
        assertTrue(milestones.max() <= BalanceConfig.CAMPAIGN_MAX_ORDER * 3)

        val avatarMilestones = BundledTestContent.content.avatarsOrdered.filter { it.unlock.type == UnlockType.STARS }.map { it.unlock.stars }.toSet()
        for (stars in milestones) {
            assertFalse(stars in avatarMilestones)
            val id = BalanceConfig.starAchievementId(stars)
            val matches = BundledTestContent.content.items.filter { achievementIdForItem(it.id) == id }
            assertEquals(1, matches.size, "expected exactly 1 item for $id")
            assertEquals(raritiesByStars.getValue(stars), matches.single().rarity)
        }
    }

    // --- (h) Seed ---

    @Test
    fun seedAchievements_allZeroAndLocked_56Total_rewardItemsDistinctFromLevelPool() {
        val achievements = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        assertEquals(56, achievements.size)
        for (ach in achievements) {
            assertEquals(0, ach.currentCount)
            assertFalse(ach.isUnlocked)
            val catalog = BundledTestContent.content.items
            val item = catalog.single { it.id == ach.rewardItemId }
            assertEquals(ach.id, achievementIdForItem(item.id))
        }

        for ((action, tiers) in achievements.groupBy { it.action }) {
            assertTrue(action != null, "every seed achievement must have an action")
            val tierNumbers = tiers.sortedBy { it.tier }.map { it.tier }
            assertEquals((1..tiers.size).toList(), tierNumbers)
        }

        val rewardIds = achievements.map { it.rewardItemId }.toSet()
        val levelRewardPoolIds = BundledTestContent.content.dropRules.levelRewards.map { it.itemId }.toSet()
        assertTrue(rewardIds.none { it in levelRewardPoolIds })
    }

    @Test
    fun seed_everyNonHandmadeTierHasOneCatalogItemWithLadderRarity() {
        val achievements = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val catalog = BundledTestContent.content.items
        for (ach in achievements) {
            if (ach.id in HANDMADE_ACHIEVEMENT_IDS) continue
            val matches = catalog.filter { achievementIdForItem(it.id) == ach.id }
            assertEquals(1, matches.size, "expected exactly 1 catalog item for ${ach.id}")
            assertEquals(ach.rarity, matches.single().rarity)
        }
    }

    // --- (i) withProfileProgress overlay ---

    @Test
    fun withProfileProgress_overlaysRealValues() {
        val definitions = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val fresh = ChildProfile(id = "p", displayName = "Saga")
        val freshOverlay = AchievementEngine.withProfileProgress(definitions, fresh)
        assertTrue(freshOverlay.all { it.currentCount == 0 && !it.isUnlocked })

        val partial = fresh.copy(achievementProgress = mapOf("ach_math_clock_money" to 6, "chain:CLOCK_MONEY" to 6))
        val partialOverlay = AchievementEngine.withProfileProgress(definitions, partial)
        assertEquals(6, partialOverlay.single { it.id == "ach_math_clock_money" }.currentCount)

        val unlocked = fresh.copy(unlockedAchievementIds = setOf("ach_speed_reader_5"))
        val unlockedOverlay = AchievementEngine.withProfileProgress(definitions, unlocked)
        val speedReader = unlockedOverlay.single { it.id == "ach_speed_reader_5" }
        assertEquals(speedReader.targetCount, speedReader.currentCount)
        assertTrue(speedReader.isUnlocked)
    }

    @Test
    fun withProfileProgress_legacyClockMoney6_showsOnTier15() {
        val definitions = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val profile = ChildProfile(id = "p", displayName = "Saga", achievementProgress = mapOf("ach_math_clock_money" to 6))
        val overlay = AchievementEngine.withProfileProgress(definitions, profile)
        assertEquals(6, overlay.single { it.id == "ach_math_clock_money" }.currentCount)
    }

    // --- (j) Ladder well-formedness ---

    @Test
    fun ladders_wellFormed() {
        assertEquals(AchievementAction.entries, ACHIEVEMENT_LADDERS.keys.toList())

        val allIds = mutableListOf<String>()
        for ((action, tiers) in ACHIEVEMENT_LADDERS) {
            val targets = tiers.map { it.target }
            assertEquals(targets.sorted().distinct(), targets, "targets must be strictly ascending for $action")
            val rarityOrdinals = tiers.map { it.rarity.ordinal }
            for (i in 1 until rarityOrdinals.size) {
                assertTrue(rarityOrdinals[i] >= rarityOrdinals[i - 1], "rarity must be non-decreasing for $action")
            }
            tiers.forEach { allIds += achievementId(action, it) }
        }
        assertEquals(56, allIds.size)
        assertEquals(allIds.distinct(), allIds, "all 56 ids must be unique")

        val legacyExpected = mapOf(
            "ach_speed_reader_5" to (5 to Rarity.EPIC),
            "ach_pronunciation_master_10" to (10 to Rarity.LEGENDARY),
            "ach_math_clock_money" to (15 to Rarity.EPIC),
            "ach_weekly_streak_7" to (7 to Rarity.LEGENDARY)
        ) + BalanceConfig.STAR_ACHIEVEMENT_MILESTONES.associate { stars ->
            val rarity = when (stars) {
                20, 45, 75 -> Rarity.RARE
                120, 175, 250 -> Rarity.EPIC
                else -> Rarity.LEGENDARY
            }
            BalanceConfig.starAchievementId(stars) to (stars to rarity)
        }
        for ((id, expected) in legacyExpected) {
            val occurrences = ACHIEVEMENT_LADDERS.values.flatten().filter { it.legacyId == id }
            assertEquals(1, occurrences.size, "legacy id $id must appear exactly once")
            assertEquals(expected.first, occurrences.single().target)
            assertEquals(expected.second, occurrences.single().rarity)
        }

        val avatarMilestones = BundledTestContent.content.avatarsOrdered.filter { it.unlock.type == UnlockType.STARS }.map { it.unlock.stars }.toSet()
        for (tier in ACHIEVEMENT_LADDERS.getValue(AchievementAction.COLLECT_STARS)) {
            assertFalse(tier.target in avatarMilestones)
        }

        assertEquals(listOf(20, 45, 75, 120, 175, 250, 350, 480), BalanceConfig.STAR_ACHIEVEMENT_MILESTONES)
    }

    // --- (k) Chain counting ---

    @Test
    fun readStory_distinctLevels_sharedCounter_mathIgnored() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.READ_STORY }
        var state = AchievementState()

        var eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l1", isAiMode = false), state, defs)
        state = eval.state
        assertTrue("ach_read_story_1" in state.unlockedIds)

        // A math run never counts toward READ_STORY.
        eval = engine.evaluate(RunFacts(Subject.MATH, "m1", isAiMode = false), state, defs)
        state = eval.state
        assertEquals(1, state.countedLevelIds["chain:READ_STORY"]?.size)

        eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l2", isAiMode = false), state, defs)
        state = eval.state
        eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l3", isAiMode = false), state, defs)
        state = eval.state
        assertTrue("ach_read_story_3" in state.unlockedIds)
    }

    @Test
    fun exploreWorld_countsDistinctGeographyLevels_notReadStory() {
        val engine = AchievementEngine()
        val allDefs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val exploreDefs = allDefs.filter { it.action == AchievementAction.EXPLORE_WORLD }
        val readStoryDefs = allDefs.filter { it.action == AchievementAction.READ_STORY }
        var state = AchievementState()

        var eval = engine.evaluate(RunFacts(Subject.GEOGRAPHY, "level_161", isAiMode = false), state, exploreDefs + readStoryDefs)
        state = eval.state
        assertTrue("ach_explore_world_1" in state.unlockedIds)
        val unlockedSverigeflagga = eval.newlyUnlocked.single { it.id == "ach_explore_world_1" }
        assertEquals("item_hand_right_geo_sverigeflagga", unlockedSverigeflagga.rewardItemId)
        assertFalse("ach_read_story_1" in state.unlockedIds)

        // Replaying the same level doesn't double-count.
        eval = engine.evaluate(RunFacts(Subject.GEOGRAPHY, "level_161", isAiMode = false), state, exploreDefs + readStoryDefs)
        state = eval.state
        assertEquals(1, state.countedLevelIds["chain:EXPLORE_WORLD"]?.size)

        eval = engine.evaluate(RunFacts(Subject.GEOGRAPHY, "level_162", isAiMode = false), state, exploreDefs + readStoryDefs)
        state = eval.state
        assertEquals(2, state.countedLevelIds["chain:EXPLORE_WORLD"]?.size)

        // A Swedish run still advances READ_STORY independently.
        eval = engine.evaluate(RunFacts(Subject.SWEDISH, "level_1", isAiMode = false), state, exploreDefs + readStoryDefs)
        state = eval.state
        assertTrue("ach_read_story_1" in state.unlockedIds)
        assertEquals(2, state.countedLevelIds["chain:EXPLORE_WORLD"]?.size)
    }

    @Test
    fun perfectLevel_onlyThreeStarRuns_replayCountsOnce() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.PERFECT_LEVEL }
        var state = AchievementState()

        var eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l1", isAiMode = false, earnedStarsThisRun = 2), state, defs)
        state = eval.state
        assertTrue(eval.newlyUnlocked.isEmpty())

        eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l1", isAiMode = false, earnedStarsThisRun = 3), state, defs)
        state = eval.state
        assertTrue("ach_perfect_level_1" in state.unlockedIds)

        // Replaying the same level at 3 stars doesn't count again.
        eval = engine.evaluate(RunFacts(Subject.SWEDISH, "l1", isAiMode = false, earnedStarsThisRun = 3), state, defs)
        state = eval.state
        assertEquals(1, state.countedLevelIds["chain:PERFECT_LEVEL"]?.size)
    }

    @Test
    fun firstTry_sumsAllTypes() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.FIRST_TRY }
        val eval = engine.evaluate(
            RunFacts(
                Subject.MATH,
                "l1",
                isAiMode = false,
                mathFirstTryByType = mapOf(
                    MathProblemType.ADDITION to 1,
                    MathProblemType.SUBTRACTION to 1,
                    MathProblemType.CLOCK to 1,
                    MathProblemType.MONEY to 1
                )
            ),
            AchievementState(),
            defs
        )
        assertEquals(4, eval.state.progress["chain:FIRST_TRY"])
    }

    @Test
    fun streak_3UnlocksTargets2And3_neverDrops() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.STREAK_DAY }
        var state = AchievementState()

        var eval = engine.evaluate(RunFacts(Subject.MATH, "l1", isAiMode = false, streakDaysAfterRun = 3), state, defs)
        state = eval.state
        assertTrue("ach_streak_day_2" in state.unlockedIds)
        assertTrue("ach_streak_day_3" in state.unlockedIds)

        // A broken streak (down to 1 day) never drops the stored chain counter.
        eval = engine.evaluate(RunFacts(Subject.MATH, "l2", isAiMode = false, streakDaysAfterRun = 1), state, defs)
        state = eval.state
        assertEquals(3, state.progress["chain:STREAK_DAY"])
    }

    // --- (l) Legacy carry-over ---

    @Test
    fun legacy_speedReaderCountedLevels_carryOver() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.READ_FAST }
        val legacyState = AchievementState(
            countedLevelIds = mapOf("ach_speed_reader_5" to setOf("l1", "l2", "l3", "l4"))
        )

        val eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l5", isAiMode = true, achievedWpm = 40, referenceWpm = 40),
            legacyState,
            defs
        )
        assertTrue("ach_read_fast_1" in eval.newlyUnlocked.map { it.id })
        assertTrue("ach_read_fast_3" in eval.newlyUnlocked.map { it.id })
        assertTrue("ach_speed_reader_5" in eval.newlyUnlocked.map { it.id })
    }

    @Test
    fun legacy_speedReaderCountedLevels_carryOverWith3Legacy_unlocksOnlyLowerTiers() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.READ_FAST }
        val legacyState = AchievementState(
            countedLevelIds = mapOf("ach_speed_reader_5" to setOf("l1", "l2", "l3"))
        )

        val eval = engine.evaluate(
            RunFacts(Subject.SWEDISH, "l4", isAiMode = true, achievedWpm = 40, referenceWpm = 40),
            legacyState,
            defs
        )
        val unlockedNowIds = eval.newlyUnlocked.map { it.id }
        assertTrue("ach_read_fast_1" in unlockedNowIds)
        assertTrue("ach_read_fast_3" in unlockedNowIds)
        assertFalse("ach_speed_reader_5" in unlockedNowIds)
    }

    @Test
    fun legacy_clockMoneyUnlocked_neverRetriggers() {
        val engine = AchievementEngine()
        val defs = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards).filter { it.action == AchievementAction.CLOCK_MONEY }
        val legacyState = AchievementState(
            progress = mapOf("ach_math_clock_money" to 15),
            unlockedIds = setOf("ach_math_clock_money")
        )

        val eval = engine.evaluate(RunFacts(Subject.MATH, "l1", isAiMode = false), legacyState, defs)
        assertTrue("ach_clock_money_3" in eval.newlyUnlocked.map { it.id })
        assertTrue("ach_clock_money_8" in eval.newlyUnlocked.map { it.id })
        assertFalse("ach_math_clock_money" in eval.newlyUnlocked.map { it.id })
    }

    // --- (m) Multi-unlock / next-tier / current-tier ---

    @Test
    fun multiUnlock_freshFirst3StarReading_oneChestOnePending() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val readingLevel = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p_fresh", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(id = "p_fresh", displayName = "Saga")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = readingLevel,
            previousProgress = null,
            earnedStarsThisRun = 3,
            completedInAiMode = false,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.SWEDISH, readingLevel.id, isAiMode = false)
        )

        assertEquals(
            setOf("ach_read_story_1", "ach_perfect_level_1"),
            outcome.newlyUnlockedAchievements.map { it.id }.toSet()
        )
        assertEquals(1, outcome.newlyUnlockedRewards.count { it.source == RewardSource.ACHIEVEMENT })
        assertEquals(setOf("ach_perfect_level_1"), outcome.updatedProfile.pendingAchievementRewardIds)
    }

    @Test
    fun currentTiers_lowestLockedOrTopWhenDone() {
        val definitions = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val profile = ChildProfile(
            id = "p",
            displayName = "Saga",
            unlockedAchievementIds = ACHIEVEMENT_LADDERS.getValue(AchievementAction.READ_STORY)
                .map { achievementId(AchievementAction.READ_STORY, it) }
                .toSet()
        )
        val tiers = AchievementEngine.currentTiers(definitions, profile)
        val readStoryTier = tiers.single { it.action == AchievementAction.READ_STORY }
        assertEquals(5, readStoryTier.tier) // top tier, since the whole chain is unlocked

        val solveMathTier = tiers.single { it.action == AchievementAction.SOLVE_MATH }
        assertEquals(1, solveMathTier.tier) // lowest locked tier
    }

    @Test
    fun nextChallenge_filledForAwardedAchievement() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val readingLevel = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p_next", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(id = "p_next", displayName = "Saga")

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = readingLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = false,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.SWEDISH, readingLevel.id, isAiMode = false)
        )

        val readStoryDef = achievements.single { it.id == "ach_read_story_1" }
        val nextId = outcome.nextChallengeByRewardItemId[readStoryDef.rewardItemId]?.id
        assertEquals("ach_read_story_3", nextId)
    }

    @Test
    fun nextChallenge_shownWhenRewardAlreadyOwned() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = BundledTestContent.content.items
        val achievements = LixoRepository.buildSeedAchievements(BundledTestContent.content.dropRules.achievementRewards)
        val readingLevel = level(order = 1, subject = Subject.SWEDISH)
        val today = DailyActivityRecord(profileId = "p_owned_next", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(id = "p_owned_next", displayName = "Saga")
        val readStoryDef = achievements.single { it.id == "ach_read_story_1" }

        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = readingLevel,
            previousProgress = null,
            earnedStarsThisRun = 1,
            completedInAiMode = false,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 0,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = setOf(readStoryDef.rewardItemId!!),
            todayActivityBeforeRun = today,
            nowEpochMillis = 1L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.SWEDISH, readingLevel.id, isAiMode = false)
        )

        assertTrue(outcome.newlyUnlockedRewards.none { it.item.id == readStoryDef.rewardItemId })
        assertTrue(readStoryDef.rewardItemId !in outcome.nextChallengeByRewardItemId)
        assertTrue("ach_read_story_3" in outcome.ownedRewardNextChallenges.map { it.id })
        assertTrue("ach_read_story_1" !in outcome.updatedProfile.pendingAchievementRewardIds)
        assertTrue("ach_read_story_1" in outcome.updatedProfile.unlockedAchievementIds)
    }
}
