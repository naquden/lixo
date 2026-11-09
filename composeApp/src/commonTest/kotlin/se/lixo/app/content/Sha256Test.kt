package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.content.Sha256

class Sha256Test {

    @Test
    fun hex_knownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc")
        )
    }

    @Test
    fun hex_oneMillionAs() {
        val input = "a".repeat(1_000_000)
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(input)
        )
    }

    @Test
    fun hex_nonAscii_matchesUtf8Bytes() {
        val text = "åäö"
        assertEquals(Sha256.hex(text.encodeToByteArray()), Sha256.hex(text))
    }
}
