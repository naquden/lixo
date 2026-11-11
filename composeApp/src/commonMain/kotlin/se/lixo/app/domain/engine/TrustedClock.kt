package se.lixo.app.domain.engine

import kotlin.time.Clock
import se.lixo.app.platform.platformBootId
import se.lixo.app.platform.platformMonotonicMillis

/**
 * A single anchor point relating a server wall-clock reading to a monotonic clock reading at the
 * moment it was captured, plus the boot id in effect at that moment (see [TrustedClock]).
 */
data class TrustedClockAnchor(
    val serverMillis: Long,
    val monotonicMillis: Long,
    val bootId: String?
)

/** Persistence for [TrustedClock]'s anchor and high-water mark, in the style of `PendingEmailLinkStorage`. */
interface TrustedClockStore {
    fun loadAnchor(): TrustedClockAnchor?
    fun saveAnchor(anchor: TrustedClockAnchor)
    fun loadLastTrustedMillis(): Long?
    fun saveLastTrustedMillis(millis: Long)
}

/** In-memory [TrustedClockStore], the default for tests and platforms with no persistence hook. */
class InMemoryTrustedClockStore : TrustedClockStore {
    private var anchor: TrustedClockAnchor? = null
    private var lastTrustedMillis: Long? = null

    override fun loadAnchor(): TrustedClockAnchor? = anchor
    override fun saveAnchor(anchor: TrustedClockAnchor) {
        this.anchor = anchor
    }

    override fun loadLastTrustedMillis(): Long? = lastTrustedMillis
    override fun saveLastTrustedMillis(millis: Long) {
        lastTrustedMillis = millis
    }
}

/**
 * A wall clock that resists the device clock being turned back to revive an ended event (025):
 * once the server has told us a time (via `ContentRepository.sync`'s `meta.serverTimeMillis`),
 * [now] advances from that anchor using the monotonic clock rather than the device clock, both
 * within this process and (via [store]) across restarts in the same boot. [now] also never goes
 * below the highest trusted value ever observed ([TrustedClockStore.loadLastTrustedMillis]), so a
 * device clock turned back after being online can't undo that either.
 *
 * Setting the device clock *forward* while offline can still start an event early: [now] falls
 * back to `max(deviceNow(), lastTrusted)` when there is no usable anchor, and a forward-moved
 * device clock raises that max. This is accepted because events are purely cosmetic.
 *
 * Not thread-safe (same note as `ActiveContent`): only ever read/written from the main thread.
 */
class TrustedClock(
    private val deviceNow: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val monotonicNow: () -> Long = ::platformMonotonicMillis,
    private val bootId: () -> String? = ::platformBootId,
    private val store: TrustedClockStore = InMemoryTrustedClockStore()
) {
    private var sessionAnchor: TrustedClockAnchor? = null
    private var persistedAnchor: TrustedClockAnchor? = null
    private var lastTrusted: Long? = null
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { store.loadAnchor() }.getOrNull()?.let { persistedAnchor = it }
        runCatching { store.loadLastTrustedMillis() }.getOrNull()?.let { lastTrusted = it }
    }

    /** Anchors this clock to a freshly-fetched server time, overwriting the high-water mark. */
    fun onServerTime(serverMillis: Long) {
        ensureLoaded()
        val anchor = TrustedClockAnchor(serverMillis, monotonicNow(), bootId())
        sessionAnchor = anchor
        persistedAnchor = anchor
        lastTrusted = serverMillis
        runCatching { store.saveAnchor(anchor) }
        runCatching { store.saveLastTrustedMillis(serverMillis) }
    }

    /** The best current estimate of wall-clock time; see the class KDoc. */
    fun now(): Long {
        ensureLoaded()
        val mono = monotonicNow()
        val currentBootId = bootId()
        val anchor = sessionAnchor?.takeIf { mono >= it.monotonicMillis }
            ?: persistedAnchor?.takeIf {
                it.bootId != null && it.bootId == currentBootId && mono >= it.monotonicMillis
            }

        val result = if (anchor != null) {
            anchor.serverMillis + (mono - anchor.monotonicMillis)
        } else {
            maxOf(deviceNow(), lastTrusted ?: Long.MIN_VALUE)
        }

        val trusted = lastTrusted
        if (trusted == null || result > trusted) {
            lastTrusted = result
            runCatching { store.saveLastTrustedMillis(result) }
        }
        return result
    }
}
