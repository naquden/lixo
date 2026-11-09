package se.lixo.app.drops

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject

/**
 * An achievement's reward item is resolved from the run's `content.dropRules.achievementRewards`
 * at award time (doc/plans/023), not from the `Achievement.rewardItemId` baked in by
 * [LixoRepository.buildSeedAchievements] at repository init.
 */
class AchievementRewardMappingTest {

    private val base = DropTestContent.base

    private fun weeklyStreakProfile() = ChildProfile(
        id = "p",
        displayName = "T",
        currentStreakDays = 6,
        lastStreakDateIso = "2026-10-31"
    )

    private fun todayRecord() = DailyActivityRecord("p", "2026-11-01", readingTasksCompleted = 1)

    @Test
    fun achievementReward_followsTheRunsPack() {
        for (rewardItemId in listOf("item_shoulders_test_cape", "item_gloves_test_mitt")) {
            val rules = base.dropRules.copy(
                weeklyStreakSetPieceItemIds = emptyList(),
                achievementRewards = mapOf("ach_weekly_streak_7" to rewardItemId)
            )
            val content = DropTestContent.withRules { rules }
            val defs = LixoRepository.buildSeedAchievements(rules.achievementRewards)
                .filter { it.id == "ach_weekly_streak_7" }

            val engine = ProgressionAndGiftEngine(random = Random(1))
            val outcome = DropTestContent.firstClear(
                engine = engine,
                content = content,
                order = 50,
                clearedBefore = 50,
                profile = weeklyStreakProfile(),
                stars = 1,
                eligibleForGift = false,
                defs = defs,
                today = todayRecord(),
                subject = Subject.MATH
            )

            val achievementReward = outcome.newlyUnlockedRewards.single { it.source == RewardSource.ACHIEVEMENT }
            assertEquals(rewardItemId, achievementReward.item.id)
            assertTrue(outcome.nextChallengeByRewardItemId.keys.all { it in outcome.newlyUnlockedRewards.map { r -> r.item.id } })
        }
    }

    @Test
    fun achievementReward_ignoresTheDefinitionsOwnRewardItemId_usesRunsPackInstead() {
        // defs are built from the ORIGINAL base rules (cape), but the run's content maps the
        // achievement to the mitt: the run's pack decides, not the baked-in definition.
        val defs = LixoRepository.buildSeedAchievements(base.dropRules.achievementRewards)
            .filter { it.id == "ach_weekly_streak_7" }
        assertEquals("item_shoulders_test_cape", defs.single().rewardItemId)

        val rules = base.dropRules.copy(
            weeklyStreakSetPieceItemIds = emptyList(),
            achievementRewards = mapOf("ach_weekly_streak_7" to "item_gloves_test_mitt")
        )
        val content = DropTestContent.withRules { rules }
        val engine = ProgressionAndGiftEngine(random = Random(1))
        val outcome = DropTestContent.firstClear(
            engine = engine,
            content = content,
            order = 50,
            clearedBefore = 50,
            profile = weeklyStreakProfile(),
            stars = 1,
            eligibleForGift = false,
            defs = defs,
            today = todayRecord(),
            subject = Subject.MATH
        )

        val achievementReward = outcome.newlyUnlockedRewards.single { it.source == RewardSource.ACHIEVEMENT }
        assertEquals("item_gloves_test_mitt", achievementReward.item.id)
        assertTrue(outcome.nextChallengeByRewardItemId.keys.all { it in outcome.newlyUnlockedRewards.map { r -> r.item.id } })
    }
}
