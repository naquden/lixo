package se.lixo.app.drops

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import se.lixo.app.domain.content.LevelReward
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource

/**
 * The level -> curated item map (`dropRules.levelRewards`) is stable as the catalog grows, and
 * the engine never crashes on a missing/unknown id -- it falls back to the onboarding roll
 * (doc/plans/023).
 */
class LevelRewardStabilityTest {

    private val base = DropTestContent.base

    @Test
    fun levelRewardMap_unaffectedByCatalogGrowth() {
        val grown = DropTestContent.withItems(
            (1..50).map { DropTestContent.item("item_extra_$it", rarity = Rarity.entries[it % 5]) }
        )
        for (order in 1..10) {
            assertEquals(base.levelRewardItemId(order), grown.levelRewardItemId(order))
        }

        val engineForBase = ProgressionAndGiftEngine(random = Random(1))
        val onBase = DropTestContent.firstClear(engineForBase, base, order = 1, clearedBefore = 0)
        val levelRewardBase = onBase.newlyUnlockedRewards.single { it.source == RewardSource.LEVEL }
        assertEquals("item_shirt_test_tee", levelRewardBase.item.id)

        val engineForGrown = ProgressionAndGiftEngine(random = Random(1))
        val onGrown = DropTestContent.firstClear(engineForGrown, grown, order = 1, clearedBefore = 0)
        val levelRewardGrown = onGrown.newlyUnlockedRewards.single { it.source == RewardSource.LEVEL }
        assertEquals("item_shirt_test_tee", levelRewardGrown.item.id)

        val order2Base = DropTestContent.firstClear(ProgressionAndGiftEngine(random = Random(1)), base, order = 2, clearedBefore = 1)
        assertEquals("item_pants_test_shorts", order2Base.newlyUnlockedRewards.single { it.source == RewardSource.LEVEL }.item.id)

        val order2Grown = DropTestContent.firstClear(ProgressionAndGiftEngine(random = Random(1)), grown, order = 2, clearedBefore = 1)
        assertEquals("item_pants_test_shorts", order2Grown.newlyUnlockedRewards.single { it.source == RewardSource.LEVEL }.item.id)
    }

    @Test
    fun levelWithNoEntry_fallsBackToOnboardingRoll() {
        assertNull(base.levelRewardItemId(3))

        val engine = ProgressionAndGiftEngine(random = Random(1))
        val outcome = DropTestContent.firstClear(engine, base, order = 3, clearedBefore = 2)
        val levelRewards = outcome.newlyUnlockedRewards.filter { it.source == RewardSource.LEVEL }
        assertEquals(1, levelRewards.size)
        assertNotEquals(base.levelRewardItemId(3), levelRewards.single().item.id)
    }

    @Test
    fun unknownCuratedId_fallsBackWithoutCrashing() {
        val content = DropTestContent.withRules { it.copy(levelRewards = listOf(LevelReward(1, "item_missing"))) }
        val engine = ProgressionAndGiftEngine(random = Random(1))
        val outcome = DropTestContent.firstClear(engine, content, order = 1, clearedBefore = 0)
        val levelRewards = outcome.newlyUnlockedRewards.filter { it.source == RewardSource.LEVEL }
        assertEquals(1, levelRewards.size)
        assertNotEquals("item_missing", levelRewards.single().item.id)
    }
}
