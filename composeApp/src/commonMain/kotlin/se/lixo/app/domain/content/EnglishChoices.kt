package se.lixo.app.domain.content

import kotlin.random.Random
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.EnglishGlosaItem

/**
 * Builds multiple-choice answer lists for the English vocabulary ("Engelska Glosresan") task.
 *
 * Bug 016: `MathActivityScreen` used to build choices as a hard-coded
 * `listOf(item.swedishTranslation, "sköld", "drake", "stjärna").distinct()`. That put the correct
 * answer at index 0 on every question (kids could tap the top card without reading), and for the
 * "stjärna" item `distinct()` silently dropped the duplicate, leaving only 3 options. This object
 * replaces that with a seedable, shuffled set of 4 unique options per question, preferring other
 * words from the same task as distractors before falling back to a fixed Swedish word pool.
 */
object EnglishChoices {
    const val CHOICE_COUNT = 4

    val FALLBACK_DISTRACTORS_SV = listOf(
        "sköld", "drake", "katt", "hund", "sol", "boll", "bok", "fisk", "träd", "hus"
    )

    /**
     * Builds a shuffled list of [CHOICE_COUNT] unique choices for [item], preferring the
     * translations of other items in [taskItems] as distractors before falling back to
     * [FALLBACK_DISTRACTORS_SV]. The correct answer is inserted unmodified (exact equality with
     * [EnglishGlosaItem.swedishTranslation] is how the screen determines the solved state), while
     * candidate distractors are deduplicated and excluded using
     * [MathEngine.Companion.normalizeMathAnswer] so no distractor is ever scored as correct.
     */
    fun build(item: EnglishGlosaItem, taskItems: List<EnglishGlosaItem>, random: Random): List<String> {
        val correct = item.swedishTranslation
        val correctKey = MathEngine.normalizeMathAnswer(correct)

        val taskCandidates = taskItems
            .filter { it.id != item.id }
            .map { it.swedishTranslation.trim() }
            .shuffled(random)
        val fallbackCandidates = FALLBACK_DISTRACTORS_SV.shuffled(random)

        val distractors = (taskCandidates + fallbackCandidates)
            .filter { it.isNotBlank() && MathEngine.normalizeMathAnswer(it) != correctKey }
            .distinctBy { MathEngine.normalizeMathAnswer(it) }
            .take(CHOICE_COUNT - 1)

        return (distractors + correct).shuffled(random)
    }

    /**
     * Builds the choice lists for every item in [items], in order, using a single [random]
     * instance shared across all items so a given seed always produces the same result.
     */
    fun buildForTask(items: List<EnglishGlosaItem>, random: Random): List<List<String>> =
        items.map { build(it, items, random) }
}
