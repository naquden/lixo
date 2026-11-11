package se.lixo.app.geography

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.content.GeographyChoices
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState

/**
 * Geography flag levels use `MathEngine.evaluateMathSession` unchanged (plan 037 §5): 1 star for
 * completing, +1 for finishing within `timeRequirementSeconds`, +1 for zero wrong attempts.
 */
class GeographyStarsTest {

    private val level = GeographyCurriculum.buildLevel(1)
    private val problems = GeographyChoices.buildProblems(
        GeographyCurriculum.buildQuestions(1),
        Random(0)
    )
    private val engine = MathEngine()

    @Test
    fun noWrong_underTime_givesThreeStars() {
        val states = problems.map { MathProblemAttemptState(it, isSolved = true) }
        val result = engine.evaluateMathSession(states, level.timeRequirementSeconds, level.timeRequirementSeconds, isTimedChallenge = true)
        assertEquals(3, result.starsEarned)
    }

    @Test
    fun oneWrongAttempt_underTime_givesTwoStars() {
        val states = problems.mapIndexed { idx, p ->
            if (idx == 0) MathProblemAttemptState(p, wrongAttempts = 1, isSolved = true)
            else MathProblemAttemptState(p, isSolved = true)
        }
        val result = engine.evaluateMathSession(states, level.timeRequirementSeconds, level.timeRequirementSeconds, isTimedChallenge = true)
        assertEquals(2, result.starsEarned)
    }

    @Test
    fun noWrong_overTime_givesTwoStars() {
        val states = problems.map { MathProblemAttemptState(it, isSolved = true) }
        val result = engine.evaluateMathSession(states, level.timeRequirementSeconds + 1, level.timeRequirementSeconds, isTimedChallenge = true)
        assertEquals(2, result.starsEarned)
    }

    @Test
    fun wrongAndOverTime_givesOneStar() {
        val states = problems.mapIndexed { idx, p ->
            if (idx == 0) MathProblemAttemptState(p, wrongAttempts = 1, isSolved = true)
            else MathProblemAttemptState(p, isSolved = true)
        }
        val result = engine.evaluateMathSession(states, level.timeRequirementSeconds + 1, level.timeRequirementSeconds, isTimedChallenge = true)
        assertEquals(1, result.starsEarned)
    }

    @Test
    fun hintsAlone_underTime_noWrong_stillGivesThreeStars() {
        val states = problems.mapIndexed { idx, p ->
            if (idx == 0) MathProblemAttemptState(p, hintsRevealed = 2, isSolved = true)
            else MathProblemAttemptState(p, isSolved = true)
        }
        val result = engine.evaluateMathSession(states, level.timeRequirementSeconds, level.timeRequirementSeconds, isTimedChallenge = true)
        assertEquals(3, result.starsEarned)
    }
}
