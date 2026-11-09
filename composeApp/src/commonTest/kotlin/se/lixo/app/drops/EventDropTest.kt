package se.lixo.app.drops

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.DropBoost
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/** `events[].dropBoost` boosted to certainty, used by seeds below to always try the event pool. */
private val boostedContent: ActiveContent = ActiveContent.from(
    DropTestContent.base.pack.copy(
        events = DropTestContent.base.pack.events.map { it.copy(dropBoost = DropBoost(1f, 1f)) }
    )
)

private val snapMillis = LocalDateTime(2026, 12, 10, 12, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
private val snap = EventEngine.snapshot(boostedContent, snapMillis, TimeZone.UTC)

class EventDropTest {

    @Test
    fun rollChest_withActiveEvent_alwaysGivesEventItem() {
        for (seed in 0 until 50) {
            val engine = ProgressionAndGiftEngine(Random(seed))
            val outcome = engine.rollChest(boostedContent.items, emptySet(), boostedContent.dropRules, events = snap)
            assertTrue(outcome?.rolledItem?.pool == ItemPool.EVENT, "seed=$seed rolled ${outcome?.rolledItem?.id}")
        }
    }

    @Test
    fun rollPresentGift_withActiveEvent_givesEventItem() {
        for (seed in 0 until 50) {
            val engine = ProgressionAndGiftEngine(Random(seed))
            val outcome = engine.rollPresentGift(Subject.MATH, boostedContent.items, emptySet(), boostedContent.dropRules, events = snap)
            assertTrue(outcome.rolledItem.pool == ItemPool.EVENT, "seed=$seed rolled ${outcome.rolledItem.id}")
        }
    }

    @Test
    fun rollChest_withoutActiveEvent_neverGivesEventItem() {
        for (seed in 0 until 50) {
            val engine = ProgressionAndGiftEngine(Random(seed))
            val outcome = engine.rollChest(boostedContent.items, emptySet(), boostedContent.dropRules)
            assertFalse(outcome?.rolledItem?.pool == ItemPool.EVENT, "seed=$seed rolled ${outcome?.rolledItem?.id}")
        }
    }

    @Test
    fun rollChest_pityFloorHonouredWithinEventPool() {
        val legendaryEventItem = DropTestContent.item(
            "item_hat_test_event_legend",
            Rarity.LEGENDARY,
            pool = ItemPool.EVENT,
            eventId = "event_test_jul"
        )
        val event = boostedContent.pack.events.first { it.id == "event_test_jul" }
        val withLegendary = ActiveContent.from(
            boostedContent.pack.copy(
                items = boostedContent.pack.items + legendaryEventItem,
                events = boostedContent.pack.events.map {
                    if (it.id == event.id) it.copy(itemIds = it.itemIds + legendaryEventItem.id) else it
                }
            )
        )
        val snapshot = EventEngine.snapshot(withLegendary, snapMillis, TimeZone.UTC)
        val pity = withLegendary.dropRules.pity.getValue(Rarity.LEGENDARY)

        val engine = ProgressionAndGiftEngine(Random(1))
        val outcome = engine.rollChest(
            withLegendary.items,
            emptySet(),
            withLegendary.dropRules,
            chestsSinceLegendary = pity - 1,
            events = snapshot
        )
        assertEquals(legendaryEventItem.id, outcome?.rolledItem?.id)
    }

    @Test
    fun rollChest_allEventItemsOwned_fallsBackToStandardItem() {
        val owned = EventEngine.eventPool(boostedContent, snap.activeEvents).map { it.id }.toSet()
        val engine = ProgressionAndGiftEngine(Random(3))
        val outcome = engine.rollChest(boostedContent.items, owned, boostedContent.dropRules, events = snap)
        assertFalse(outcome?.rolledItem?.pool == ItemPool.EVENT)
    }

    @Test
    fun recordLevelCompletion_withActiveEvent_outcomeHasChestTheme() {
        val engine = ProgressionAndGiftEngine(Random(0))
        val outcome = DropTestContent.firstClear(
            engine,
            boostedContent,
            order = 1,
            clearedBefore = 0,
            events = snap
        )
        assertTrue(outcome.chestTheme != null)
    }
}
