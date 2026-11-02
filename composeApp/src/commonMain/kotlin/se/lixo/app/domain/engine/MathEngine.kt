package se.lixo.app.domain.engine

import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType

data class MathProblemAttemptState(
    val problem: MathProblem,
    val wrongAttempts: Int = 0,
    val hintsRevealed: Int = 0, // 0, 1, or 2
    val isSolved: Boolean = false
) {
    val activeHints: List<String>
        get() = buildList {
            if (hintsRevealed >= 1) add(problem.hintStep1Sv)
            if (hintsRevealed >= 2) add(problem.hintStep2Sv)
        }
}

data class MathSessionScoreResult(
    val starsEarned: Int,
    val totalProblems: Int,
    val totalWrongAttempts: Int,
    val totalHintsUsed: Int,
    val completedTimedChallenge: Boolean
)

/**
 * Math Engine (`project.md` §2 "Matematik-stöttning"):
 * - Covers Addition, Subtraction, Ten-Crossing, Multiplication, Division, Clock, Money, Word Problems.
 * - Growth mindset: never punishes a wrong answer harshly; instead reveals progressive visual hints
 *   (Step 1 -> Step 2) and logs error patterns locally for the Parent View.
 * - Also generates randomized multi-step arithmetic challenges for the Parent View gate (`project.md` §4 & §5).
 */
class MathEngine {

    fun checkAnswer(
        state: MathProblemAttemptState,
        submittedAnswer: String
    ): Pair<MathProblemAttemptState, Boolean> {
        val normalizedSubmitted = normalizeMathAnswer(submittedAnswer)
        val normalizedExpected = normalizeMathAnswer(state.problem.correctAnswer)

        if (normalizedSubmitted == normalizedExpected) {
            return state.copy(isSolved = true) to true
        }

        // Growth mindset: on wrong attempt, automatically reveal the next supportive hint
        val nextWrong = state.wrongAttempts + 1
        val nextHints = (state.hintsRevealed + 1).coerceAtMost(2)
        return state.copy(
            wrongAttempts = nextWrong,
            hintsRevealed = nextHints,
            isSolved = false
        ) to false
    }

    fun requestHint(state: MathProblemAttemptState): MathProblemAttemptState {
        if (state.isSolved) return state
        return state.copy(hintsRevealed = (state.hintsRevealed + 1).coerceAtMost(2))
    }

    fun evaluateMathSession(
        states: List<MathProblemAttemptState>,
        elapsedSeconds: Int,
        timeRequirementSeconds: Int,
        isTimedChallenge: Boolean
    ): MathSessionScoreResult {
        val totalHints = states.sumOf { it.hintsRevealed }
        val totalWrong = states.sumOf { it.wrongAttempts }

        val stars = when {
            elapsedSeconds <= timeRequirementSeconds &&
                totalHints <= BalanceConfig.MATH_THREE_STAR_MAX_HINTS &&
                totalWrong <= 1 -> 3

            elapsedSeconds <= (timeRequirementSeconds * BalanceConfig.MATH_TWO_STAR_TIME_MULTIPLIER).toInt() -> 2
            else -> 1
        }

        return MathSessionScoreResult(
            starsEarned = stars,
            totalProblems = states.size,
            totalWrongAttempts = totalWrong,
            totalHintsUsed = totalHints,
            completedTimedChallenge = isTimedChallenge && elapsedSeconds <= timeRequirementSeconds
        )
    }

    /**
     * Generates a hard multi-step arithmetic gate for the Parent View (`project.md` §4 & §5:
     * "Föräldravy — via Settings, skyddad av svårt mattetal").
     */
    fun generateParentGateChallenge(seed: Int): Pair<String, Int> {
        val a = 12 + (seed.mod(7))     // 12..18
        val b = 6 + (seed.mod(4))      // 6..9
        val c = 19 + (seed.mod(15))    // 19..33
        val answer = (a * b) + c
        val prompt = "($a × $b) + $c = ?"
        return prompt to answer
    }

    companion object {
        fun normalizeMathAnswer(raw: String): String {
            return raw
                .trim()
                .lowercase()
                .replace("kr", "")
                .replace("kronor", "")
                .replace(" ", "")
                .replace(".", ":")
        }
    }
}
