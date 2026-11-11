package se.lixo.app.geography

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.domain.content.GeographyChoices
import se.lixo.app.domain.content.GeographyCountries
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.model.FlagQuestion
import se.lixo.app.domain.model.MathProblemType

class GeographyChoicesTest {

    private val level1Questions = GeographyCurriculum.buildQuestions(1)

    private fun fq(
        id: String = "q",
        c: String = "se",
        d: List<String> = listOf("no", "dk", "fi")
    ) = FlagQuestion(id, c, d)

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

    @Test
    fun isValid_trueForWellFormed() {
        assertTrue(GeographyChoices.isValid(fq()))
    }

    @Test
    fun isValid_falseForUnknownCountryCode() {
        assertFalse(GeographyChoices.isValid(fq(c = "xx")))
    }

    @Test
    fun isValid_falseForUnknownDistractorCode() {
        assertFalse(GeographyChoices.isValid(fq(d = listOf("no", "dk", "xx"))))
    }

    @Test
    fun isValid_falseForTwoDistractors() {
        assertFalse(GeographyChoices.isValid(fq(d = listOf("no", "dk"))))
    }

    @Test
    fun isValid_falseForFourDistractors() {
        assertFalse(GeographyChoices.isValid(fq(d = listOf("no", "dk", "fi", "is"))))
    }

    @Test
    fun isValid_falseForDuplicateDistractor() {
        assertFalse(GeographyChoices.isValid(fq(d = listOf("no", "no", "dk"))))
    }

    @Test
    fun isValid_falseWhenCountryDuplicatesDistractorCaseInsensitively() {
        assertFalse(GeographyChoices.isValid(fq(c = "SE", d = listOf("se", "no", "dk"))))
    }

    @Test
    fun isValid_falseForBlankId() {
        assertFalse(GeographyChoices.isValid(fq(id = " ")))
    }

    @Test
    fun isValid_trueForUppercaseCodes_andSanitizeLowercases() {
        val q = fq(c = "SE", d = listOf("NO", "DK", "FI"))
        assertTrue(GeographyChoices.isValid(q))
        val sanitized = GeographyChoices.sanitize(listOf(q)).single()
        assertEquals("se", sanitized.countryIso2)
        assertEquals(listOf("no", "dk", "fi"), sanitized.distractorIso2)
    }

    @Test
    fun sanitize_keepsValidInOrderDropsBad() {
        val questions = listOf(
            fq(id = "v1", c = "se", d = listOf("no", "dk", "fi")),
            fq(id = "bad1", c = "xx", d = listOf("no", "dk", "fi")),
            fq(id = "v2", c = "no", d = listOf("se", "dk", "fi")),
            fq(id = "bad2", c = "dk", d = listOf("se", "no")),
            fq(id = "v3", c = "fi", d = listOf("se", "no", "dk"))
        )
        val kept = GeographyChoices.sanitize(questions).map { it.id }
        assertEquals(listOf("v1", "v2", "v3"), kept)
    }

    @Test
    fun buildProblems_mixedList_onlyValidNoThrow() {
        val questions = listOf(
            fq(id = "v1", c = "se", d = listOf("no", "dk", "fi")),
            fq(id = "bad1", c = "xx", d = listOf("no", "dk", "fi")),
            fq(id = "v2", c = "no", d = listOf("se", "dk", "fi"))
        )
        val problems = GeographyChoices.buildProblems(questions, Random(1))
        assertEquals(listOf("v1", "v2"), problems.map { it.id })
        problems.forEach { p -> p.choices.forEach { assertTrue(GeographyCountries.find(it) != null) } }
    }

    @Test
    fun everyCurriculumQuestion_isValid() {
        val allQuestions = (1..GeographyCurriculum.LEVEL_COUNT).flatMap { GeographyCurriculum.buildQuestions(it) }
        assertTrue(allQuestions.all(GeographyChoices::isValid))
    }
}
