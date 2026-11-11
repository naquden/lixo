package se.lixo.app.geography

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.content.GeographyCountries
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.model.Subject

class GeographyCurriculumTest {

    @Test
    fun thirtyLevels_atOrders161to190_withCorrectSubjectAndCourse() {
        val levelsAndTasks = GeographyCurriculum.buildLevelsAndTasks(emptyMap())
        assertEquals(30, levelsAndTasks.size)
        val orders = levelsAndTasks.map { it.first.order }
        assertEquals((161..190).toList(), orders)
        levelsAndTasks.forEach { (level, task) ->
            assertEquals(Subject.GEOGRAPHY, level.subject)
            assertEquals(Subject.GEOGRAPHY, task.subject)
            assertEquals(GeographyCurriculum.COURSE_ID, level.courseId)
            assertEquals(8, task.flagQuestions.size)
        }
    }

    @Test
    fun difficultyAndTimeRequirement_matchFormula() {
        for (idx in 1..30) {
            val level = GeographyCurriculum.buildLevel(idx)
            val expectedDifficulty = (idx - 1) / 5 + 1
            assertEquals(expectedDifficulty, level.difficulty, "idx=$idx")
            assertEquals(8 * (5 + expectedDifficulty), level.timeRequirementSeconds, "idx=$idx")
        }
    }

    @Test
    fun everyCountry_isACorrectAnswerAtLeastOnce() {
        val allCorrect = (1..30).flatMap { GeographyCurriculum.buildQuestions(it) }.map { it.countryIso2 }.toSet()
        val allRanked = GeographyCountries.RANKED.map { it.iso2 }.toSet()
        assertEquals(allRanked, allCorrect)
    }

    @Test
    fun newCountryMaxRank_strictlyIncreasesPerLevel() {
        val ranked = GeographyCountries.RANKED.map { it.iso2 }
        var previousMax = -1
        for (idx in 1..30) {
            val maxRank = GeographyCurriculum.newCountries(idx).maxOf { ranked.indexOf(it.iso2) }
            assertTrue(maxRank > previousMax, "idx=$idx maxRank=$maxRank previousMax=$previousMax")
            previousMax = maxRank
        }
    }

    @Test
    fun distractors_areThreeUniqueAndNotEqualToCorrect() {
        for (idx in 1..30) {
            GeographyCurriculum.buildQuestions(idx).forEach { q ->
                assertEquals(3, q.distractorIso2.size, "idx=$idx q=${q.id}")
                assertEquals(3, q.distractorIso2.toSet().size, "idx=$idx q=${q.id}")
                assertTrue(q.countryIso2 !in q.distractorIso2, "idx=$idx q=${q.id}")
            }
        }
    }

    @Test
    fun difficulty1to4_distractorsWithinKnownPool() {
        for (idx in 1..20) {
            val d = (idx - 1) / 5 + 1
            if (d > 4) continue
            val known = GeographyCurriculum.knownPool(idx).map { it.iso2 }.toSet()
            GeographyCurriculum.buildQuestions(idx).forEach { q ->
                q.distractorIso2.forEach { dIso ->
                    assertTrue(dIso in known, "idx=$idx d=$d q=${q.id} distractor=$dIso not in known pool")
                }
            }
        }
    }

    @Test
    fun difficulty1to2_distractorsContainNoLookalikeOfCorrect() {
        for (idx in 1..10) {
            GeographyCurriculum.buildQuestions(idx).forEach { q ->
                val lookalikes = GeographyCountries.lookalikesOf(q.countryIso2)
                q.distractorIso2.forEach { dIso ->
                    assertTrue(dIso !in lookalikes, "idx=$idx q=${q.id} distractor=$dIso is a lookalike of ${q.countryIso2}")
                }
            }
        }
    }

    @Test
    fun difficulty5to6_distractorsContainAtLeastOneLookalikeWhenGroupExists() {
        for (idx in 21..30) {
            GeographyCurriculum.buildQuestions(idx).forEach { q ->
                val lookalikes = GeographyCountries.lookalikesOf(q.countryIso2)
                if (lookalikes.isNotEmpty()) {
                    val hasLookalike = q.distractorIso2.any { it in lookalikes }
                    assertTrue(hasLookalike, "idx=$idx q=${q.id} expected >=1 lookalike distractor")
                }
            }
        }
    }

    @Test
    fun deterministic_acrossMultipleBuilds() {
        for (idx in listOf(1, 5, 15, 30)) {
            val first = GeographyCurriculum.buildQuestions(idx)
            val second = GeographyCurriculum.buildQuestions(idx)
            assertEquals(first, second)
        }
    }

    @Test
    fun questionIds_areUniqueAcrossAllLevels() {
        val allIds = (1..30).flatMap { GeographyCurriculum.buildQuestions(it) }.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }
}
