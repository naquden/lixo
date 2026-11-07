package se.lixo.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.data.repository.GENERATED_WARDROBE_ITEMS
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.engine.AchievementState
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.engine.RunFacts
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject

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

    // --- (a) Speed reader ---

    @Test
    fun speedReader_countsOnlyEligibleAiRuns_distinctLevels_unlocksAt5() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements().single { it.id == "ach_speed_reader_5" }
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
        val def = LixoRepository.buildSeedAchievements().single { it.id == "ach_pronunciation_master_10" }
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
        val def = LixoRepository.buildSeedAchievements().single { it.id == "ach_math_clock_money" }
        var state = AchievementState(progress = mapOf(def.id to 7))

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
        val def = LixoRepository.buildSeedAchievements().single { it.id == "ach_weekly_streak_7" }
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
    fun requiresAiMode_enforced_andAlreadyUnlockedNeverRetriggers() {
        val engine = AchievementEngine()
        val def = LixoRepository.buildSeedAchievements().single { it.id == "ach_speed_reader_5" }
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
        assertTrue(eval2.newlyUnlocked.isEmpty())
        assertEquals(unlocked, eval2.state)
    }

    // --- (f) recordLevelCompletion integration ---

    private fun baseMathProfile(id: String, totalStars: Int): ChildProfile =
        ChildProfile(id = id, displayName = "Saga", totalStars = totalStars)

    @Test
    fun recordLevelCompletion_19Stars_noAchievement_20Stars_unlocksAchStars20() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 51,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = outcome19.newlyUnlockedItems.map { it.id }.toSet(),
            todayActivityBeforeRun = today,
            nowEpochMillis = 2L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "level_other", isAiMode = true)
        )
        assertEquals(20, outcome20.updatedProfile.totalStars)
        val achievementReward = outcome20.newlyUnlockedRewards.single { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_20", outcome20.newlyUnlockedAchievements.single().id)
        assertEquals("ach_stars_20", achievementReward.item.unlockAchievementId)
        assertEquals("Stjärnfrö", achievementReward.labelDetailSv)
    }

    @Test
    fun recordLevelCompletion_profileAt100Stars_unlocksOnePendingPerRun() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 200,
            allWardrobeItems = catalog,
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
        assertEquals("ach_stars_20", awardedNow1.single().item.unlockAchievementId)
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 201,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = unlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 2L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "l100b", isAiMode = true)
        )
        val awardedNow2 = outcome2.newlyUnlockedRewards.filter { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_45", awardedNow2.single().item.unlockAchievementId)
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 202,
            allWardrobeItems = catalog,
            currentlyUnlockedItemIds = unlockedIds,
            todayActivityBeforeRun = today,
            nowEpochMillis = 3L,
            achievementDefinitions = achievements,
            runFacts = RunFacts(Subject.MATH, "l100c", isAiMode = true)
        )
        val awardedNow3 = outcome3.newlyUnlockedRewards.filter { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("ach_stars_75", awardedNow3.single().item.unlockAchievementId)
        assertTrue(outcome3.updatedProfile.pendingAchievementRewardIds.isEmpty())
    }

    @Test
    fun recordLevelCompletion_replayWithNoNetNewStars_addsNothing() {
        val engine = ProgressionAndGiftEngine(random = Random(42))
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
        val mathLevel = level(order = 2, subject = Subject.MATH)
        val today = DailyActivityRecord(profileId = "p_two", calendarDateIso = "2026-11-01")
        val profile = ChildProfile(
            id = "p_two",
            displayName = "Saga",
            totalStars = 19,
            achievementProgress = mapOf("ach_math_clock_money" to 14)
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
        val catalog = LixoRepository.buildSeedWardrobeCatalog()
        val achievements = LixoRepository.buildSeedAchievements()
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
            rewardItemIdForTask = null,
            clearedLevelsCountBeforeRun = 50,
            allWardrobeItems = catalog,
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
        // WEEKLY_STREAK filter (unlockAchievementId == null) skips it in favor of another
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

        val avatarMilestones = AvatarType.entries.map { it.unlockStarsRequired }.toSet()
        for (stars in milestones) {
            assertFalse(stars in avatarMilestones)
            val id = BalanceConfig.starAchievementId(stars)
            val matches = GENERATED_WARDROBE_ITEMS.filter { it.unlockAchievementId == id }
            assertEquals(1, matches.size, "expected exactly 1 item for $id")
            assertEquals(raritiesByStars.getValue(stars), matches.single().rarity)
        }
    }

    // --- (h) Seed ---

    @Test
    fun seedAchievements_allZeroAndLocked_12Total_rewardItemsDistinctFromLevelPool() {
        val achievements = LixoRepository.buildSeedAchievements()
        assertEquals(12, achievements.size)
        for (ach in achievements) {
            assertEquals(0, ach.currentCount)
            assertFalse(ach.isUnlocked)
            val catalog = LixoRepository.buildSeedWardrobeCatalog()
            val item = catalog.single { it.id == ach.rewardItemId }
            assertEquals(ach.id, item.unlockAchievementId)
        }

        val rewardIds = achievements.map { it.rewardItemId }.toSet()
        val levelRewardPoolIds = LixoRepository.buildLevelRewardPool(LixoRepository.buildSeedWardrobeCatalog())
            .map { it.id }
            .toSet()
        assertTrue(rewardIds.none { it in levelRewardPoolIds })
    }

    // --- (i) withProfileProgress overlay ---

    @Test
    fun withProfileProgress_overlaysRealValues() {
        val definitions = LixoRepository.buildSeedAchievements()
        val fresh = ChildProfile(id = "p", displayName = "Saga")
        val freshOverlay = AchievementEngine.withProfileProgress(definitions, fresh)
        assertTrue(freshOverlay.all { it.currentCount == 0 && !it.isUnlocked })

        val partial = fresh.copy(achievementProgress = mapOf("ach_math_clock_money" to 6))
        val partialOverlay = AchievementEngine.withProfileProgress(definitions, partial)
        assertEquals(6, partialOverlay.single { it.id == "ach_math_clock_money" }.currentCount)

        val unlocked = fresh.copy(unlockedAchievementIds = setOf("ach_speed_reader_5"))
        val unlockedOverlay = AchievementEngine.withProfileProgress(definitions, unlocked)
        val speedReader = unlockedOverlay.single { it.id == "ach_speed_reader_5" }
        assertEquals(speedReader.targetCount, speedReader.currentCount)
        assertTrue(speedReader.isUnlocked)
    }
}
