package se.lixo.app.domain.engine

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AfterEnd
import se.lixo.app.domain.content.ChestTheme
import se.lixo.app.domain.content.ContentEvent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ItemPool

/**
 * A frozen, run-ready view of which events are active right now (025): which pool items are
 * droppable, boosted drop chances and the chest theme to show. Built by [EventEngine.snapshot]
 * and threaded through [ProgressionAndGiftEngine] so a run's outcome never changes mid-roll.
 */
data class EventSnapshot(
    val activeEvents: List<ContentEvent>,
    val eventPoolItemIds: Set<String>,
    val joinedStandardItemIds: Set<String>
) {
    /** The highest `dropBoost.eventItemChance` among [activeEvents], or 0f if none are active. */
    val eventItemChance: Float = activeEvents.maxOfOrNull { it.dropBoost.eventItemChance } ?: 0f

    /** The highest `dropBoost.presentGiftChance` among [activeEvents], or 0f if none are active. */
    val presentGiftChance: Float = activeEvents.maxOfOrNull { it.dropBoost.presentGiftChance } ?: 0f

    /** The first active event's chest theme, in pack order, or null if none has one. */
    val chestTheme: ChestTheme? = activeEvents.firstNotNullOfOrNull { it.chestTheme }

    companion object {
        val NONE = EventSnapshot(emptyList(), emptySet(), emptySet())
    }
}

/**
 * Pure time-window and pool logic for holiday-special content events (025, `doc/content/content-pack.md`).
 * Event windows are local calendar dates: `start <= today < endExclusive`, evaluated against
 * [TrustedClock.now] converted with [timeZone] (`kotlinx.datetime`, never UTC).
 */
object EventEngine {

    /** [nowMillis] converted to a local calendar date in [timeZone]. */
    fun localDate(nowMillis: Long, timeZone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date

    /** True when [today] falls within [event]'s window. A date that fails to parse means inactive. */
    fun isActive(event: ContentEvent, today: LocalDate): Boolean = runCatching {
        val start = LocalDate.parse(event.start)
        val end = LocalDate.parse(event.endExclusive)
        today >= start && today < end
    }.getOrDefault(false)

    /** True when [today] is at or after [event]'s end. A date that fails to parse means not ended. */
    fun hasEnded(event: ContentEvent, today: LocalDate): Boolean = runCatching {
        val end = LocalDate.parse(event.endExclusive)
        today >= end
    }.getOrDefault(false)

    /** Every event in [content] active at [nowMillis], in pack order. */
    fun activeEvents(content: ActiveContent, nowMillis: Long, timeZone: TimeZone): List<ContentEvent> {
        val today = localDate(nowMillis, timeZone)
        return content.pack.events.filter { isActive(it, today) }
    }

    /**
     * [event]'s event-pool items, in `event.itemIds` order: `pool == EVENT` and `eventId == event.id`.
     * Retired items are included (the item sheet shows them too).
     */
    fun eventItems(content: ActiveContent, event: ContentEvent): List<ContentItem> =
        event.itemIds.mapNotNull { id ->
            content.itemsById[id]?.takeIf { it.pool == ItemPool.EVENT && it.eventId == event.id }
        }

    /** The union of [eventItems] for every non-retired item across [events], distinct by id. */
    fun eventPool(content: ActiveContent, events: List<ContentEvent>): List<ContentItem> =
        events.flatMap { eventItems(content, it) }.filterNot { it.retired }.distinctBy { it.id }

    /** Whether [item] can currently drop at all, at [nowMillis]. */
    fun isDroppable(item: ContentItem, content: ActiveContent, nowMillis: Long, timeZone: TimeZone): Boolean {
        if (item.retired) return false
        return when (item.pool) {
            ItemPool.STANDARD -> true
            ItemPool.EVENT -> {
                val event = item.eventId?.let { content.eventsById[it] } ?: return false
                val today = localDate(nowMillis, timeZone)
                isActive(event, today) || (hasEnded(event, today) && event.afterEnd == AfterEnd.JOIN_STANDARD)
            }
            else -> false
        }
    }

    /** A frozen snapshot of [content]'s event state at [nowMillis], for a single run/roll. */
    fun snapshot(content: ActiveContent, nowMillis: Long, timeZone: TimeZone): EventSnapshot {
        val today = localDate(nowMillis, timeZone)
        val active = content.pack.events.filter { isActive(it, today) }
        val ended = content.pack.events.filter { hasEnded(it, today) }

        val eventPoolItemIds = eventPool(content, active).map { it.id }.toSet()
        val joinedStandardItemIds = ended
            .filter { it.afterEnd == AfterEnd.JOIN_STANDARD }
            .flatMap { eventItems(content, it) }
            .filterNot { it.retired }
            .map { it.id }
            .toSet()

        return EventSnapshot(active, eventPoolItemIds, joinedStandardItemIds)
    }

    /** Milliseconds until the next local midnight in [timeZone], at least 1000ms. */
    fun millisUntilNextLocalMidnight(nowMillis: Long, timeZone: TimeZone): Long {
        val today = localDate(nowMillis, timeZone)
        val nextMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
        return (nextMidnight.toEpochMilliseconds() - nowMillis).coerceAtLeast(1000L)
    }
}
