package se.lixo.app.domain.social

import kotlinx.serialization.Serializable
import se.lixo.app.domain.content.NicknameWords
import kotlin.random.Random

/**
 * A friend's generated display name (doc/plans/030 §3): `adj` + `animal` + `num` indexes into
 * [NicknameWords], e.g. `Nickname(adj = 0, animal = 0, num = 42).render(words)` == `"ModigRäv42"`.
 * No free text is ever stored for a friend card — only these three small ints, which also fit the
 * Firestore `nick: [adjIdx, animalIdx, num]` encoding ([toList]/[fromList]).
 */
@Serializable
data class Nickname(val adj: Int, val animal: Int, val num: Int) {

    /**
     * Renders this nickname against [words]. If either index is out of range for the currently
     * active content pack (e.g. the pack shrank, which the pipeline's append-only stability rule
     * should prevent, or this nickname was generated against a newer pack than the one this
     * device has), falls back to `"Kompis<num>"` rather than crashing or showing a blank name.
     */
    fun render(words: NicknameWords): String {
        val adjWord = words.adjectives.getOrNull(adj)
        val animalWord = words.animals.getOrNull(animal)
        return if (adjWord != null && animalWord != null) "$adjWord$animalWord$num" else "Kompis$num"
    }

    /** The Firestore `nick` encoding: `[adjIdx, animalIdx, num]`. */
    fun toList(): List<Int> = listOf(adj, animal, num)
}

/**
 * Picks a random [Nickname] from [words] using [rng]. `adj`/`animal` are uniform over the word
 * list sizes (falling back to index 0 of an empty list, which simply renders as `"Kompis<num>"`
 * until the pack is populated); `num` is uniform in `0..99`.
 */
fun randomNickname(words: NicknameWords, rng: Random): Nickname {
    val adjCount = words.adjectives.size.coerceAtLeast(1)
    val animalCount = words.animals.size.coerceAtLeast(1)
    return Nickname(
        adj = rng.nextInt(adjCount),
        animal = rng.nextInt(animalCount),
        num = rng.nextInt(100)
    )
}

/**
 * Decodes a Firestore `nick: [adjIdx, animalIdx, num]` array into a [Nickname], validating the
 * ranges the Firestore rules also enforce (`adj`/`animal` in `0..255`, `num` in `0..99`). Returns
 * null for anything malformed: wrong length, or any value out of range.
 */
fun fromList(list: List<Int>): Nickname? {
    if (list.size != 3) return null
    val (adj, animal, num) = list
    if (adj !in 0..255 || animal !in 0..255 || num !in 0..99) return null
    return Nickname(adj, animal, num)
}
