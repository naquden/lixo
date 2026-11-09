package se.lixo.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.content.EnglishChoices
import se.lixo.app.domain.model.EnglishGlosaItem

class EnglishChoicesTest {

    private fun item(id: String, en: String, sv: String): EnglishGlosaItem = EnglishGlosaItem(
        id = id,
        englishWord = en,
        swedishTranslation = sv,
        simpleSentenceEn = "This is a $en.",
        simpleSentenceSv = "Det här är en $sv."
    )

    private val hat = item("en_1_0", "hat", "hatt")
    private val star = item("en_1_1", "star", "stjärna")
    private val castle = item("en_1_2", "castle", "slott")
    private val taskItems = listOf(hat, star, castle)

    @Test
    fun build_returnsFourDistinctOptionsContainingCorrect() {
        for (item in taskItems) {
            for (seed in 0L..50L) {
                val choices = EnglishChoices.build(item, taskItems, Random(seed))
                assertEquals(4, choices.size, "seed=$seed item=${item.id}")
                assertEquals(4, choices.distinctBy { it.lowercase() }.size, "seed=$seed item=${item.id}")
                assertTrue(choices.contains(item.swedishTranslation), "seed=$seed item=${item.id} choices=$choices")
            }
        }
    }

    @Test
    fun starItem_hasFourOptions() {
        for (seed in 0L..50L) {
            val choices = EnglishChoices.build(star, taskItems, Random(seed))
            assertEquals(4, choices.size, "seed=$seed")
        }
    }

    @Test
    fun singleItemTask_fallsBackToPool() {
        val soloTask = listOf(star)
        val choices = EnglishChoices.build(star, soloTask, Random(1))
        assertEquals(4, choices.size)
        val distractors = choices.filter { it != star.swedishTranslation }
        assertEquals(3, distractors.size)
        distractors.forEach { distractor ->
            assertTrue(
                EnglishChoices.FALLBACK_DISTRACTORS_SV.contains(distractor),
                "distractor '$distractor' should come from FALLBACK_DISTRACTORS_SV"
            )
        }
    }

    @Test
    fun distractorsNeverEqualCorrect_caseAndWhitespaceVariants() {
        val collidingCorrect = item("en_x_0", "shield", "Sköld")
        val otherItems = listOf(
            collidingCorrect,
            item("en_x_1", "hat", " Hatt "),
            item("en_x_2", "cap", "HATT")
        )
        for (seed in 0L..50L) {
            val choices = EnglishChoices.build(collidingCorrect, otherItems, Random(seed))
            val correct = collidingCorrect.swedishTranslation
            choices.forEach { choice ->
                if (choice != correct) {
                    assertTrue(
                        choice.trim().lowercase() != correct.trim().lowercase(),
                        "distractor '$choice' should not normalize to correct answer '$correct' (seed=$seed)"
                    )
                }
            }
        }
    }

    @Test
    fun prefersOtherTaskItems() {
        val threeItemTask = listOf(hat, star, castle)
        for (seed in 0L..30L) {
            val choices = EnglishChoices.build(hat, threeItemTask, Random(seed))
            assertTrue(choices.contains(star.swedishTranslation), "seed=$seed choices=$choices")
            assertTrue(choices.contains(castle.swedishTranslation), "seed=$seed choices=$choices")
        }

        val dog = item("en_1_3", "dog", "hund")
        val cat = item("en_1_4", "cat", "katt")
        val fiveItemTask = listOf(hat, star, castle, dog, cat)
        for (seed in 0L..30L) {
            val choices = EnglishChoices.build(hat, fiveItemTask, Random(seed))
            val distractors = choices.filter { it != hat.swedishTranslation }
            val taskTranslations = fiveItemTask.filter { it.id != hat.id }.map { it.swedishTranslation }
            distractors.forEach { distractor ->
                assertTrue(
                    taskTranslations.contains(distractor),
                    "distractor '$distractor' should come from other task items (seed=$seed)"
                )
            }
        }
    }

    @Test
    fun positionDistribution() {
        val counts = IntArray(4)
        for (seed in 0L until 400L) {
            val choices = EnglishChoices.build(hat, taskItems, Random(seed))
            val idx = choices.indexOf(hat.swedishTranslation)
            counts[idx] = counts[idx] + 1
        }
        counts.forEachIndexed { idx, count ->
            assertTrue(count in 60..140, "index $idx count=$count should be in [60, 140]")
        }
    }

    @Test
    fun deterministicForFixedSeed() {
        val first = EnglishChoices.buildForTask(taskItems, Random(42))
        val second = EnglishChoices.buildForTask(taskItems, Random(42))
        assertEquals(first, second)
    }
}
