package se.lixo.app.drops

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/**
 * Drop rules drive chest/gift rolls (doc/plans/023): rarity weights, pity floors and the chest
 * cost/points all come from `DropRules` parameters, never a `BalanceConfig` constant. Built on
 * the mini [DropTestContent] fixture.
 */
class DropRulesChestTest {

    private val base = DropTestContent.base

    @Test
    fun legendaryOnlyWeights_alwaysRollsTheShield() {
        val rules = base.dropRules.copy(
            rarityWeights = mapOf(
                Rarity.COMMON to 0,
                Rarity.UNCOMMON to 0,
                Rarity.RARE to 0,
                Rarity.EPIC to 0,
                Rarity.LEGENDARY to 1
            )
        )
        for (seed in 0 until 50) {
            val engine = ProgressionAndGiftEngine(random = Random(seed))
            val outcome = engine.rollChest(base.items, emptySet(), rules)
            assertNotNull(outcome)
            assertEquals(Rarity.LEGENDARY, outcome.rolledItem.rarity)
        }

        // The legendary item (item_hand_left_test_shield) is MATH-themed, so pick a subject whose
        // filtered pool still contains it.
        val giftEngine = ProgressionAndGiftEngine(random = Random(1))
        val giftOutcome = giftEngine.rollPresentGift(Subject.MATH, base.items, emptySet(), rules)
        assertEquals(Rarity.LEGENDARY, giftOutcome.rolledItem.rarity)
    }

    @Test
    fun pityFloors_matchConfiguredThresholds() {
        val pity = mapOf(Rarity.RARE to 2, Rarity.EPIC to 3, Rarity.LEGENDARY to 4)
        assertEquals(Rarity.RARE, ProgressionAndGiftEngine.chestMinRarity(1, 0, 0, pity))
        assertEquals(Rarity.EPIC, ProgressionAndGiftEngine.chestMinRarity(0, 2, 0, pity))
        assertEquals(Rarity.LEGENDARY, ProgressionAndGiftEngine.chestMinRarity(0, 0, 3, pity))
        assertEquals(Rarity.COMMON, ProgressionAndGiftEngine.chestMinRarity(0, 1, 2, pity))
        assertEquals(Rarity.COMMON, ProgressionAndGiftEngine.chestMinRarity(0, 0, 0, pity))

        val rules = base.dropRules.copy(
            pity = pity,
            rarityWeights = mapOf(
                Rarity.COMMON to 1,
                Rarity.UNCOMMON to 0,
                Rarity.RARE to 0,
                Rarity.EPIC to 0,
                Rarity.LEGENDARY to 0
            )
        )
        val engine = ProgressionAndGiftEngine(random = Random(1))
        val aboveRare = engine.rollChest(base.items, emptySet(), rules, 1, 0, 0)
        assertNotNull(aboveRare)
        assertTrue(aboveRare.rolledItem.rarity >= Rarity.RARE)

        val legendary = engine.rollChest(base.items, emptySet(), rules, 0, 0, 3)
        assertNotNull(legendary)
        assertEquals(Rarity.LEGENDARY, legendary.rolledItem.rarity)
    }

    @Test
    fun chestPoints_matchConfiguredHalfPoints() {
        val rules = base.dropRules.copy(
            chestCostHalfPoints = 6,
            progressRunHalfPoints = 3,
            replayRunHalfPoints = 1
        )
        val engine = ProgressionAndGiftEngine(random = Random(1))
        assertEquals(3, engine.chestPointsForRun(netNewStars = 1, isReplay = false, eligible = true, dropRules = rules))
        assertEquals(1, engine.chestPointsForRun(netNewStars = 0, isReplay = true, eligible = true, dropRules = rules))
        assertEquals(0, engine.chestPointsForRun(netNewStars = 1, isReplay = false, eligible = false, dropRules = rules))

        val content = se.lixo.app.domain.content.ActiveContent.from(base.pack.copy(dropRules = rules))

        val crosses = DropTestContent.firstClear(
            engine = engine,
            content = content,
            order = 50,
            clearedBefore = 50,
            eligibleForGift = true,
            profile = ChildProfile(id = "p", displayName = "T", chestProgressHalfPoints = 3)
        )
        assertNotNull(crosses.chestOutcome)
        assertEquals(0, crosses.updatedProfile.chestProgressHalfPoints)

        val staysBelow = DropTestContent.firstClear(
            engine = engine,
            content = content,
            order = 50,
            clearedBefore = 50,
            eligibleForGift = true,
            profile = ChildProfile(id = "p", displayName = "T", chestProgressHalfPoints = 2)
        )
        assertNull(staysBelow.chestOutcome)
        assertEquals(5, staysBelow.updatedProfile.chestProgressHalfPoints)
    }

    @Test
    fun excludedPools_neverDropFromChestsGiftsOrOnboarding() {
        val forbidden = setOf("item_tail_test_bow", "item_shoulders_test_cape", "item_hat_test_retired")
        for (seed in 0 until 200) {
            val engine = ProgressionAndGiftEngine(random = Random(seed))
            engine.rollChest(base.items, emptySet(), base.dropRules)?.let {
                assertTrue(it.rolledItem.id !in forbidden, "rollChest rolled forbidden id ${it.rolledItem.id}")
            }
            for (subject in Subject.entries) {
                val gift = engine.rollPresentGift(subject, base.items, emptySet(), base.dropRules)
                assertTrue(gift.rolledItem.id !in forbidden, "rollPresentGift rolled forbidden id ${gift.rolledItem.id}")
            }
            engine.rollOnboardingItem(base, emptySet())?.let {
                assertTrue(it.id !in forbidden, "rollOnboardingItem rolled forbidden id ${it.id}")
            }
        }
    }
}
