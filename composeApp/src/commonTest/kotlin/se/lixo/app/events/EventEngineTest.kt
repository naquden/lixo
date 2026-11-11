package se.lixo.app.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.lixo.app.domain.content.AfterEnd
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.drops.DropTestContent

class EventEngineTest {

    private val content = DropTestContent.base
    private val tz = TimeZone.of("Europe/Stockholm")
    private val event = content.eventsById.getValue("event_test_jul")
    private val item = content.itemsById.getValue("item_tail_test_bow")

    private fun millisAt(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDateTime(y, m, d, h, min).toInstant(tz).toEpochMilliseconds()

    @Test
    fun activeAtWindowStart() {
        val events = EventEngine.activeEvents(content, millisAt(2026, 12, 1, 0, 0), tz)
        assertTrue(events.any { it.id == "event_test_jul" })
    }

    @Test
    fun inactiveAtWindowEnd() {
        val events = EventEngine.activeEvents(content, millisAt(2026, 12, 27, 0, 0), tz)
        assertFalse(events.any { it.id == "event_test_jul" })
    }

    @Test
    fun inactiveJustBeforeStart() {
        val events = EventEngine.activeEvents(content, millisAt(2026, 11, 30, 23, 59), tz)
        assertFalse(events.any { it.id == "event_test_jul" })
    }

    @Test
    fun timeZoneShiftsTheWindow() {
        // Nov 30 23:30 UTC == Dec 1 00:30 in Stockholm (UTC+1 in winter).
        val millis = Instant.parse("2026-11-30T23:30:00Z").toEpochMilliseconds()
        assertFalse(EventEngine.isActive(event, EventEngine.localDate(millis, TimeZone.UTC)))
        assertTrue(EventEngine.isActive(event, EventEngine.localDate(millis, tz)))
    }

    @Test
    fun isDroppable_duringEvent() {
        assertTrue(EventEngine.isDroppable(item, content, millisAt(2026, 12, 10, 12, 0), tz))
    }

    @Test
    fun isDroppable_afterEnd_retire_isFalse() {
        assertFalse(EventEngine.isDroppable(item, content, millisAt(2026, 12, 27, 0, 0), tz))
    }

    @Test
    fun isDroppable_afterEnd_joinStandard_isTrueAndJoinsSnapshot() {
        val joinedEvent = event.copy(afterEnd = AfterEnd.JOIN_STANDARD)
        val joinedContent = se.lixo.app.domain.content.ActiveContent.from(
            content.pack.copy(events = content.pack.events.map { if (it.id == event.id) joinedEvent else it })
        )
        val now = millisAt(2026, 12, 27, 0, 0)
        assertTrue(EventEngine.isDroppable(item, joinedContent, now, tz))
        val snapshot = EventEngine.snapshot(joinedContent, now, tz)
        assertTrue(item.id in snapshot.joinedStandardItemIds)
    }

    @Test
    fun isDroppable_retiredEventItem_isFalse() {
        val retiredItem = item.copy(retired = true)
        val retiredContent = se.lixo.app.domain.content.ActiveContent.from(
            content.pack.copy(items = content.pack.items.map { if (it.id == item.id) retiredItem else it })
        )
        assertFalse(EventEngine.isDroppable(retiredItem, retiredContent, millisAt(2026, 12, 10, 12, 0), tz))
    }

    @Test
    fun snapshot_chestTheme_duringAndOutsideEvent() {
        val during = EventEngine.snapshot(content, millisAt(2026, 12, 10, 12, 0), tz)
        assertTrue(during.chestTheme != null)

        val outside = EventEngine.snapshot(content, millisAt(2026, 1, 1, 0, 0), tz)
        assertEquals(null, outside.chestTheme)
        assertEquals(emptySet(), outside.eventPoolItemIds)
    }

    @Test
    fun millisUntilNextLocalMidnight_fromOneMinuteBefore() {
        val now = millisAt(2026, 12, 10, 23, 59)
        assertEquals(60_000L, EventEngine.millisUntilNextLocalMidnight(now, tz))
    }
}
