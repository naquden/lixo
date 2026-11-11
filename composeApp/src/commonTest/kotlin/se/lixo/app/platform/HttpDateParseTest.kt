package se.lixo.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class HttpDateParseTest {

    @Test
    fun validRfc1123Date_parsesToExpectedEpochMillis() {
        assertEquals(1445412480000L, parseHttpDateMillis("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun validRfc1123Date_roundTripsAgainstInstantParse() {
        val expected = Instant.parse("2026-11-11T19:54:16Z").toEpochMilliseconds()
        assertEquals(expected, parseHttpDateMillis("Tue, 11 Nov 2026 19:54:16 GMT"))
    }

    @Test
    fun garbage_returnsNull() {
        assertNull(parseHttpDateMillis("garbage"))
    }

    @Test
    fun empty_returnsNull() {
        assertNull(parseHttpDateMillis(""))
    }

    @Test
    fun nonRfc1123Format_returnsNull() {
        assertNull(parseHttpDateMillis("2026-11-11"))
    }
}
