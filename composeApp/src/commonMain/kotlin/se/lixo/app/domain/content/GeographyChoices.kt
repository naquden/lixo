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
     * Maps each [FlagQuestion] to a [MathProblem], sharing a single [random] across all
     * questions so a given seed always produces the same shuffled choice order (and so no two
     * questions happen to correlate in a predictable way).
     */
    fun buildProblems(questions: List<FlagQuestion>, random: Random): List<MathProblem> =
        questions.map { q ->
            val country = GeographyCountries.byIso(q.countryIso2)
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
