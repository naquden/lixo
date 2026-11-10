package se.lixo.app.social

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.lixo.app.domain.content.NicknameWords
import se.lixo.app.domain.social.Nickname
import se.lixo.app.domain.social.fromList
import se.lixo.app.domain.social.randomNickname

/** doc/plans/030 §3: render, range, unknown-index fallback and the Firestore list round-trip. */
class NicknameTest {

    private val words = NicknameWords(
        adjectives = listOf("Modig", "Glad", "Snabb"),
        animals = listOf("Räv", "Uggla", "Igelkott")
    )

    @Test
    fun rendersAdjAnimalNum() {
        assertEquals("ModigRäv42", Nickname(adj = 0, animal = 0, num = 42).render(words))
        assertEquals("SnabbIgelkott7", Nickname(adj = 2, animal = 2, num = 7).render(words))
    }

    @Test
    fun unknownAdjIndexFallsBackToKompis() {
        assertEquals("Kompis5", Nickname(adj = 99, animal = 0, num = 5).render(words))
    }

    @Test
    fun unknownAnimalIndexFallsBackToKompis() {
        assertEquals("Kompis5", Nickname(adj = 0, animal = 99, num = 5).render(words))
    }

    @Test
    fun emptyWordListsAlwaysFallBackToKompis() {
        val empty = NicknameWords()
        assertEquals("Kompis0", Nickname(adj = 0, animal = 0, num = 0).render(empty))
    }

    @Test
    fun randomNicknameStaysInRange() {
        val rng = Random(1234)
        repeat(500) {
            val nickname = randomNickname(words, rng)
            assertTrue(nickname.adj in words.adjectives.indices)
            assertTrue(nickname.animal in words.animals.indices)
            assertTrue(nickname.num in 0..99)
        }
    }

    @Test
    fun randomNicknameOnEmptyListsStaysAtIndexZero() {
        val rng = Random(42)
        repeat(20) {
            val nickname = randomNickname(NicknameWords(), rng)
            assertEquals(0, nickname.adj)
            assertEquals(0, nickname.animal)
            assertTrue(nickname.num in 0..99)
        }
    }

    @Test
    fun listRoundTrip() {
        val nickname = Nickname(adj = 12, animal = 34, num = 56)
        assertEquals(listOf(12, 34, 56), nickname.toList())
        assertEquals(nickname, fromList(nickname.toList()))
    }

    @Test
    fun fromListRejectsWrongSize() {
        assertNull(fromList(listOf(1, 2)))
        assertNull(fromList(listOf(1, 2, 3, 4)))
    }

    @Test
    fun fromListRejectsOutOfRangeValues() {
        assertNull(fromList(listOf(256, 0, 0)))
        assertNull(fromList(listOf(0, 256, 0)))
        assertNull(fromList(listOf(0, 0, 100)))
        assertNull(fromList(listOf(-1, 0, 0)))
        assertEquals(Nickname(255, 255, 99), fromList(listOf(255, 255, 99)))
    }
}
