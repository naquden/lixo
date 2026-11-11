package se.lixo.app.ios

import se.lixo.app.platform.platformBootId
import se.lixo.app.platform.platformMonotonicMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosClockTest {

    @Test
    fun bootId_nonBlankAndStableAcrossCalls() {
        val first = platformBootId()
        assertNotNull(first)
        assertTrue(first.isNotBlank())
        assertEquals(first, platformBootId())
    }

    @Test
    fun monotonic_nonDecreasingAndPositive() {
        val t1 = platformMonotonicMillis()
        assertTrue(t1 > 0)
        var busy = 0L
        for (i in 0 until 2_000_000) {
            busy += i
        }
        val t2 = platformMonotonicMillis()
        assertTrue(t2 >= t1)
        assertTrue(busy >= 0L)
    }
}
