package se.lixo.app.geography

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.content.GeographyChoices
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.model.MathProblemType

class GeographyChoicesTest {

    private val level1Questions = GeographyCurriculum.buildQuestions(1)

    @Test
    fun buildProblems_fourChoicesCorrectIncludedExactlyOnceAllDistinct() {
        for (seed in 0L..50L) {
            val problems = GeographyChoices.buildProblems(level1Questions, Random(seed))
            problems.forEach { p ->
                assertEquals(4, p.choices.size, "seed=$seed")
                assertEquals(4, p.choices.toSet().size, "seed=$seed")
                assertEquals(1, p.choices.count { it == p.correctAnswer }, "seed=$seed")
            }
        }
    }

    @Test
    fun sameSeed_givesEqualResult() {
        val first = GeographyChoices.buildProblems(level1Questions, Random(42))
        val second = GeographyChoices.buildProblems(level1Questions, Random(42))
        assertEquals(first, second)
    }

    @Test
    fun correctIndex_variesAcrossSeeds() {
        val seQuestion = level1Questions.first { it.countryIso2 == "se" }
        val indices = (0L..50L).map { seed ->
            val problems = GeographyChoices.buildProblems(listOf(seQuestion), Random(seed))
            problems.first().choices.indexOf(seQuestion.countryIso2)
        }
        assertTrue(indices.toSet().size > 1, "correct index should vary across seeds, got $indices")
    }

    @Test
    fun promptAndHints_forSverige() {
        val seQuestion = level1Questions.first { it.countryIso2 == "se" }
        val problem = GeographyChoices.buildProblems(listOf(seQuestion), Random(0)).first()
        assertEquals("Vilken flagga har Sverige?", problem.promptSv)
        assertEquals("Sverige ligger i Norden.", problem.hintStep1Sv)
        assertEquals("Flaggan har färgerna blå och gul.", problem.hintStep2Sv)
        assertEquals(MathProblemType.FLAG, problem.type)
    }

    @Test
    fun colourHintSv_oneAndThreeColors() {
        assertEquals("Flaggan har färgen röd.", GeographyChoices.colourHintSv(listOf("röd")))
        assertEquals(
            "Flaggan har färgerna röd, vit och blå.",
            GeographyChoices.colourHintSv(listOf("röd", "vit", "blå"))
        )
    }
}
