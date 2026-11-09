package se.lixo.app.events

import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.engine.InMemoryTrustedClockStore
import se.lixo.app.domain.engine.TrustedClock

class TrustedClockTest {

    private class Fakes(
        var device: Long = 0L,
        var mono: Long = 0L,
        var boot: String? = "b0"
    ) {
        val store = InMemoryTrustedClockStore()
        fun clock() = TrustedClock(
            deviceNow = { device },
            monotonicNow = { mono },
            bootId = { boot },
            store = store
        )
    }

    @Test
    fun sameBoot_deviceClockMovedBack_advancesWithMonotonic() {
        val f = Fakes()
        val clock = f.clock()
        f.mono = 1000L
        clock.onServerTime(100_000L)

        f.device -= 24 * 60 * 60 * 1000L
        f.mono = 6000L

        assertEquals(100_000L + 5000L, clock.now())
    }

    @Test
    fun sameBoot_newProcess_usesPersistedAnchor() {
        val f = Fakes()
        f.mono = 1000L
        f.clock().onServerTime(100_000L)

        f.boot = "b0"
        f.mono = 8000L
        val second = f.clock()
        assertEquals(100_000L + 7000L, second.now())
    }

    @Test
    fun newBoot_deviceBehindLastServer_returnsLastServer() {
        val f = Fakes()
        f.mono = 1000L
        f.clock().onServerTime(100_000L)

        f.boot = "b2"
        f.mono = 200L
        f.device = 100_000L - 24 * 60 * 60 * 1000L
        val second = f.clock()
        assertEquals(100_000L, second.now())
    }

    @Test
    fun newBoot_deviceAhead_usesDevice() {
        val f = Fakes()
        f.mono = 1000L
        f.clock().onServerTime(100_000L)

        f.boot = "b2"
        f.mono = 200L
        f.device = 100_000L + 60 * 60 * 1000L
        val second = f.clock()
        assertEquals(f.device, second.now())
    }

    @Test
    fun highWater() {
        val f = Fakes()
        f.mono = 1000L
        val clock = f.clock()
        clock.onServerTime(100_000L)
        f.mono = 6000L
        assertEquals(100_000L + 5000L, clock.now())

        f.boot = "b2"
        f.mono = 200L
        f.device = 0L
        val second = f.clock()
        assertEquals(105_000L, second.now())
    }

    @Test
    fun noServerEver_clockBack_neverGoesBelowHighWater() {
        val f = Fakes()
        f.device = 50_000L
        val clock = f.clock()
        assertEquals(50_000L, clock.now())

        f.device = 10_000L
        assertEquals(50_000L, clock.now())
    }
}
