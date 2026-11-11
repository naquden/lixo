package se.lixo.app.domain.content

import kotlin.random.Random
import se.lixo.app.domain.model.FlagQuestion
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType

/**
 * Builds [MathProblem]s (type [MathProblemType.FLAG]) from geography [FlagQuestion]s, reusing
 * `MathActivityScreen`'s existing runtime-shuffle-by-seed mechanism (see
 * [se.lixo.app.domain.content.EnglishChoices] for the analogous English pattern).
 */
object GeographyChoices {
    const val CHOICE_COUNT = 4

    /**
     * Swedish hint text for a flag's main colours: one colour -> "Flaggan har färgen X.", several
     * -> "Flaggan har färgerna A, B och C." (Oxford-less comma join, final pair joined by " och ").
     */
    fun colourHintSv(colors: List<String>): String {
        if (colors.isEmpty()) return "Flaggan har färger."
        if (colors.size == 1) return "Flaggan har färgen ${colors[0]}."
        val allButLast = colors.dropLast(1).joinToString(", ")
        return "Flaggan har färgerna $allButLast och ${colors.last()}."
    }

    /**
     * A [FlagQuestion] is valid when it has a non-blank [FlagQuestion.id], exactly
     * [CHOICE_COUNT] - 1 distractors, [CHOICE_COUNT] pairwise-distinct codes (case-insensitively)
     * and every code resolves to a known [Country] via [GeographyCountries.find]. This is the
     * single source of truth for what counts as a safe flag question - every flag choice must be
     * a known country with a typed flag resource (plan 038).
     */
    fun isValid(q: FlagQuestion): Boolean {
        if (q.id.isBlank()) return false
        if (q.distractorIso2.size != CHOICE_COUNT - 1) return false
        val codes = (listOf(q.countryIso2) + q.distractorIso2).map { it.lowercase() }
        if (codes.toSet().size != CHOICE_COUNT) return false
        return codes.all { GeographyCountries.find(it) != null }
    }

    /** Keeps only [isValid] questions, in order, with [FlagQuestion] iso codes lowercased. */
    fun sanitize(questions: List<FlagQuestion>): List<FlagQuestion> =
        questions.filter(::isValid).map {
            it.copy(countryIso2 = it.countryIso2.lowercase(), distractorIso2 = it.distractorIso2.map(String::lowercase))
        }

    /**
     * Maps each valid [FlagQuestion] (see [isValid]/[sanitize]) to a [MathProblem], sharing a
     * single [random] across all questions so a given seed always produces the same shuffled
     * choice order (and so no two questions happen to correlate in a predictable way).
     */
    fun buildProblems(questions: List<FlagQuestion>, random: Random): List<MathProblem> =
        sanitize(questions).mapNotNull { q ->
            val country = GeographyCountries.find(q.countryIso2) ?: return@mapNotNull null
            MathProblem(
                id = q.id,
                type = MathProblemType.FLAG,
                promptSv = "Vilken flagga har ${country.nameSv}?",
                correctAnswer = q.countryIso2,
                choices = (q.distractorIso2 + q.countryIso2).shuffled(random),
                hintStep1Sv = "${country.nameSv} ligger i ${country.region.swedishName}.",
                hintStep2Sv = colourHintSv(country.colorsSv)
            )
        }
}
