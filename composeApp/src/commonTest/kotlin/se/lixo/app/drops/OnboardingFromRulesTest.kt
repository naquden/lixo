package se.lixo.app.drops

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.content.OnboardingRules
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.RewardSource

/**
 * The onboarding track's cadence comes entirely from `dropRules.onboarding.levels`
 * (doc/plans/023): a custom schedule changes which clears award an item.
 */
class OnboardingFromRulesTest {

    @Test
    fun customOnboardingLevels_onlyAwardOnScheduledClears() {
        val content = DropTestContent.withRules {
            it.copy(onboarding = OnboardingRules(listOf(1, 4)), levelRewards = emptyList(), starUnlocks = emptyList())
        }
        val engine = ProgressionAndGiftEngine(random = Random(1))
        var profile = ChildProfile(id = "p", displayName = "T")
        var unlocked = emptySet<String>()
        val runsWithLevelReward = mutableListOf<Int>()

        for (i in 0..5) {
            val outcome = DropTestContent.firstClear(
                engine = engine,
                content = content,
                order = 20 + i,
                clearedBefore = i,
                unlocked = unlocked,
                profile = profile,
                stars = 1,
                eligibleForGift = false
            )
            if (outcome.newlyUnlockedRewards.any { it.source == RewardSource.LEVEL }) {
                runsWithLevelReward += i
            }
            profile = outcome.updatedProfile
            unlocked = unlocked + outcome.newlyUnlockedItems.map { it.id }
        }

        assertEquals(listOf(0, 3), runsWithLevelReward)
    }
}
