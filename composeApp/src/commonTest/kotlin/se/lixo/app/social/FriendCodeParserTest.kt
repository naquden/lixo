package se.lixo.app.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.domain.social.parseFriendCode

/** doc/plans/030 §4: round-trip, formatting variants, and junk that must never be accepted. */
class FriendCodeParserTest {

    @Test
    fun roundTripsThroughPayload() {
        val payload = buildFriendPayload("ABCDEFGH")
        assertEquals("lixo://friend/ABCDEFGH", payload)
        assertEquals("ABCDEFGH", parseFriendCode(payload))
    }

    @Test
    fun parsesBareCode() {
        assertEquals("ABCDEFGH", parseFriendCode("ABCDEFGH"))
    }

    @Test
    fun isCaseInsensitiveOnPrefixAndCode() {
        assertEquals("ABCDEFGH", parseFriendCode("LIXO://FRIEND/abcdefgh"))
        assertEquals("ABCDEFGH", parseFriendCode("Lixo://Friend/AbCdEfGh"))
    }

    @Test
    fun stripsDashesAndSpaces() {
        assertEquals("ABCDEFGH", parseFriendCode("ABCD-EFGH"))
        assertEquals("ABCDEFGH", parseFriendCode("ABCD EFGH"))
        assertEquals("ABCDEFGH", parseFriendCode("lixo://friend/ABCD-EFGH"))
    }

    @Test
    fun mapsConfusableCharacters() {
        // O -> 0, I/L -> 1
        assertEquals("A0111111", parseFriendCode("AOIIIIIL"))
        assertEquals("A0111111", parseFriendCode("aoiiiiil"))
    }

    @Test
    fun rejectsWrongLength() {
        assertNull(parseFriendCode("ABCDEFG"))
        assertNull(parseFriendCode("ABCDEFGHI"))
        assertNull(parseFriendCode(""))
    }

    @Test
    fun rejectsCharactersOutsideAlphabet() {
        // U is not in the Crockford alphabet used here.
        assertNull(parseFriendCode("ABCDEFGU"))
        assertNull(parseFriendCode("AB*DEFGH"))
    }

    @Test
    fun rejectsPlainUrlsAndJunk() {
        assertNull(parseFriendCode("https://example.com/foo"))
        assertNull(parseFriendCode("not a code at all"))
    }

    @Test
    fun rejectsOldUserAvatarStarsPayload() {
        assertNull(parseFriendCode("stjarn_riddare_99:avatar_knight:42"))
    }
}
