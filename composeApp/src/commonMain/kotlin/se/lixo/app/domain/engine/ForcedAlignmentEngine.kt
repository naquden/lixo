package se.lixo.app.domain.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.platform.RecognizedSpeechToken

data class StoryWordToken(
    val index: Int,
    val displayText: String,
    val normalizedText: String,
    val isCompleted: Boolean = false,
    val wasSupportedOrSkipped: Boolean = false
)

data class ReadingAlignmentSessionState(
    val mode: ReadingMode,
    val isTimedChallenge: Boolean,
    val words: List<StoryWordToken>,
    val currentWordIndex: Int = 0,
    val isWordStuckSupportVisible: Boolean = false,
    val struggledWordsNormalized: Set<String> = emptySet(),
    val elapsedMillis: Long = 0L,
    val manualModeMinRequiredMillis: Long = 0L,
    val isCompleted: Boolean = false
) {
    val progressFraction: Float
        get() = if (words.isEmpty()) 1f else (currentWordIndex.toFloat() / words.size.toFloat()).coerceIn(0f, 1f)

    val currentWord: StoryWordToken?
        get() = words.getOrNull(currentWordIndex)

    /**
     * Manual Reading Mode rule (`project.md` §2):
     * "Knappen inaktiv tills rimlig lästid passerat (baserat på textlängd); synlig 'laddning'"
     */
    val manualModeButtonChargeFraction: Float
        get() {
            if (mode != ReadingMode.MANUAL) return 1f
            if (manualModeMinRequiredMillis <= 0L) return 1f
            return (elapsedMillis.toFloat() / manualModeMinRequiredMillis.toFloat()).coerceIn(0f, 1f)
        }

    /** Whole seconds left before the manual "done" button unlocks (0 when ready). */
    val manualModeRemainingSeconds: Int
        get() {
            if (mode != ReadingMode.MANUAL) return 0
            val remaining = (manualModeMinRequiredMillis - elapsedMillis).coerceAtLeast(0L)
            return ((remaining + 999L) / 1000L).toInt()
        }

    val isManualCompleteButtonEnabled: Boolean
        get() = mode == ReadingMode.MANUAL && elapsedMillis >= manualModeMinRequiredMillis
}

data class ReadingScoreResult(
    val starsEarned: Int,
    val achievedWpm: Int,
    val referenceWpm: Int,
    val eligibleForGift: Boolean,
    val eligibleForPronunciationAchievement: Boolean,
    val eligibleForTimedChallengeReward: Boolean,
    val struggledWordsToLog: List<String>
)

/**
 * Forced Alignment Engine (`project.md` §2 "AI-Läsassistent & Realtime Highlighting (Kärn-USP)"):
 * - Matches real-time recognized speech against a known story sequence (not open-ended STT guessing).
 * - Tolerates flaky child STT via phonetic normalization and Levenshtein similarity.
 * - Never harshly fails a child: offers positive support (listen, syllables, dictionary, or skip without penalty)
 *   and logs struggled words locally for the Parent View.
 * - Enforces all Manual Reading Mode rules (mode locked at start, minimum read duration timer, max 1 star,
 *   no gifts, no pronunciation achievements, no timed challenges, no word logs).
 */
class ForcedAlignmentEngine {

    fun startSession(
        storyText: String,
        mode: ReadingMode,
        isTimedChallengeRequested: Boolean
    ): ReadingAlignmentSessionState {
        // Timed challenges in reading require AI mode (project.md §2 "Tidsuppdrag i läsning kräver AI-läge")
        val effectiveTimedChallenge = isTimedChallengeRequested && mode == ReadingMode.AI_LISTENING

        val tokens = tokenizeStory(storyText)
        val minManualMillis = calculateMinimumManualReadingMillis(tokens.size)

        return ReadingAlignmentSessionState(
            mode = mode,
            isTimedChallenge = effectiveTimedChallenge,
            words = tokens,
            currentWordIndex = 0,
            isWordStuckSupportVisible = false,
            struggledWordsNormalized = emptySet(),
            elapsedMillis = 0L,
            manualModeMinRequiredMillis = minManualMillis,
            isCompleted = tokens.isEmpty()
        )
    }

    fun tokenizeStory(storyText: String): List<StoryWordToken> {
        return storyText
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .mapIndexed { idx, raw ->
                StoryWordToken(
                    index = idx,
                    displayText = raw,
                    normalizedText = normalizeForAlignment(raw)
                )
            }
    }

    fun calculateMinimumManualReadingMillis(wordCount: Int): Long {
        val fromWords = wordCount * BalanceConfig.MANUAL_MODE_MIN_SECONDS_PER_WORD * 1000f
        val minFloor = BalanceConfig.MANUAL_MODE_MIN_TOTAL_SECONDS * 1000f
        return max(fromWords, minFloor).toLong()
    }

    /**
     * Processes a batch of incoming STT tokens against the active story sequence using
     * a sliding lookahead window (`BalanceConfig.STT_LOOKAHEAD_WINDOW_WORDS`).
     */
    fun processRecognizedTokens(
        state: ReadingAlignmentSessionState,
        spokenTokens: List<RecognizedSpeechToken>,
        confidenceThreshold: Float = BalanceConfig.STT_WORD_MATCH_CONFIDENCE_THRESHOLD
    ): ReadingAlignmentSessionState {
        // Mode switch mid-session is disallowed; Manual mode ignores STT tokens
        if (state.mode != ReadingMode.AI_LISTENING || state.isCompleted) return state

        var workingWords = state.words.toMutableList()
        var cursor = state.currentWordIndex
        var clearedStuck = state.isWordStuckSupportVisible

        for (spoken in spokenTokens) {
            if (cursor >= workingWords.size) break
            val spokenNorm = normalizeForAlignment(spoken.normalizedWord.ifBlank { spoken.rawWord })
            if (spokenNorm.isEmpty()) continue

            val maxLookaheadIndex = min(
                workingWords.size - 1,
                cursor + BalanceConfig.STT_LOOKAHEAD_WINDOW_WORDS - 1
            )

            var matchedIndex = -1
            for (candidateIdx in cursor..maxLookaheadIndex) {
                val targetNorm = workingWords[candidateIdx].normalizedText
                val similarity = computeSwedishPhoneticSimilarity(spokenNorm, targetNorm)
                val weightedScore = similarity * spoken.confidence.coerceIn(0.6f, 1.0f)
                if (similarity >= confidenceThreshold || weightedScore >= confidenceThreshold) {
                    matchedIndex = candidateIdx
                    break
                }
            }

            if (matchedIndex != -1) {
                for (i in cursor..matchedIndex) {
                    workingWords[i] = workingWords[i].copy(isCompleted = true)
                }
                cursor = matchedIndex + 1
                clearedStuck = false
            }
        }

        val finished = cursor >= workingWords.size
        return state.copy(
            words = workingWords,
            currentWordIndex = cursor,
            isWordStuckSupportVisible = if (finished) false else clearedStuck,
            isCompleted = finished
        )
    }

    /**
     * Advances timer and checks if the child has hesitated on the current word long enough
     * to surface gentle reading support.
     */
    fun onTick(
        state: ReadingAlignmentSessionState,
        deltaMillis: Long,
        millisSinceLastWordAdvance: Long
    ): ReadingAlignmentSessionState {
        if (state.isCompleted) return state
        val nextElapsed = state.elapsedMillis + max(0L, deltaMillis)
        val showSupport = state.mode == ReadingMode.AI_LISTENING &&
            millisSinceLastWordAdvance >= BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS
        val nextStruggled = if (showSupport && state.currentWord != null) {
            state.struggledWordsNormalized + state.currentWord!!.normalizedText
        } else {
            state.struggledWordsNormalized
        }

        return state.copy(
            elapsedMillis = nextElapsed,
            isWordStuckSupportVisible = showSupport || state.isWordStuckSupportVisible,
            struggledWordsNormalized = nextStruggled
        )
    }

    /**
     * Called when the child taps "Gå vidare" / uses gentle support on a tricky word.
     * No star deduction is applied (`project.md` §2: "Ingen hård underkänning... barnet kan alltid
     * gå vidare utan avdrag. Fall loggas till föräldravyn som 'ord som kan behöva övas'").
     */
    fun advanceCurrentWordWithGentleSupport(
        state: ReadingAlignmentSessionState
    ): ReadingAlignmentSessionState {
        if (state.mode != ReadingMode.AI_LISTENING || state.isCompleted) return state
        val idx = state.currentWordIndex
        val word = state.words.getOrNull(idx) ?: return state

        val updatedWords = state.words.toMutableList()
        updatedWords[idx] = word.copy(isCompleted = true, wasSupportedOrSkipped = true)
        val nextIdx = idx + 1

        return state.copy(
            words = updatedWords,
            currentWordIndex = nextIdx,
            isWordStuckSupportVisible = false,
            struggledWordsNormalized = state.struggledWordsNormalized + word.normalizedText,
            isCompleted = nextIdx >= updatedWords.size
        )
    }

    /**
     * Completes reading in Manual Mode (`project.md` §2: "Barnet läser själv och trycker 'Jag har läst klart'").
     * Only succeeds once the minimum reading time has elapsed.
     */
    fun tryCompleteManualReading(state: ReadingAlignmentSessionState): ReadingAlignmentSessionState {
        if (state.mode != ReadingMode.MANUAL) return state
        if (!state.isManualCompleteButtonEnabled) return state

        val completedWords = state.words.map { it.copy(isCompleted = true) }
        return state.copy(
            words = completedWords,
            currentWordIndex = completedWords.size,
            isCompleted = true
        )
    }

    /**
     * Evaluates stars and rewards at the end of a completed reading session (`project.md` §2):
     * - 3 stars: AI mode at >= 1.0x reference WPM
     * - 2 stars: AI mode at >= 0.5x reference WPM
     * - 1 star: Completed reading regardless of time
     * - Manual mode: capped at 1 star, no gift, no pronunciation achievement, no word log, no timed challenge
     */
    fun evaluateSessionScore(
        state: ReadingAlignmentSessionState,
        referenceWpm: Int,
        timeRequirementSeconds: Int
    ): ReadingScoreResult {
        val safeSeconds = max(1f, state.elapsedMillis / 1000f)
        val wordCount = max(1, state.words.size)
        val achievedWpm = ((wordCount / safeSeconds) * 60f).roundToInt()

        if (state.mode == ReadingMode.MANUAL) {
            return ReadingScoreResult(
                starsEarned = BalanceConfig.MANUAL_MODE_MAX_STARS,
                achievedWpm = achievedWpm,
                referenceWpm = referenceWpm,
                eligibleForGift = false,
                eligibleForPronunciationAchievement = false,
                eligibleForTimedChallengeReward = false,
                struggledWordsToLog = emptyList()
            )
        }

        val wpmRatio = achievedWpm.toFloat() / max(1, referenceWpm).toFloat()
        val stars = when {
            wpmRatio >= BalanceConfig.READING_THREE_STAR_WPM_RATIO -> 3
            wpmRatio >= BalanceConfig.READING_TWO_STAR_WPM_RATIO -> 2
            else -> 1
        }

        val noSkippedWords = state.words.none { it.wasSupportedOrSkipped }
        val beatTimeChallenge = state.isTimedChallenge && safeSeconds <= timeRequirementSeconds

        return ReadingScoreResult(
            starsEarned = stars,
            achievedWpm = achievedWpm,
            referenceWpm = referenceWpm,
            eligibleForGift = true,
            eligibleForPronunciationAchievement = noSkippedWords,
            eligibleForTimedChallengeReward = beatTimeChallenge,
            struggledWordsToLog = state.struggledWordsNormalized.toList()
        )
    }

    companion object {
        fun normalizeForAlignment(raw: String): String {
            return raw
                .lowercase()
                .trim { !it.isLetterOrDigit() }
        }

        /**
         * Computes phonetic-aware similarity in [0.0..1.0] for Swedish & English child speech,
         * folding common Swedish homophones (`sj`/`skj`/`stj`, `tj`/`kj`, double consonants)
         * before computing normalized Levenshtein similarity.
         */
        fun computeSwedishPhoneticSimilarity(spoken: String, target: String): Float {
            if (spoken.isEmpty() || target.isEmpty()) return 0f
            if (spoken == target) return 1.0f

            val pSpoken = foldSwedishPhonetics(spoken)
            val pTarget = foldSwedishPhonetics(target)
            if (pSpoken == pTarget) return 0.96f

            val dist = levenshteinDistance(pSpoken, pTarget)
            val maxLen = max(pSpoken.length, pTarget.length)
            return (1f - (dist.toFloat() / maxLen.toFloat())).coerceIn(0f, 1f)
        }

        private fun foldSwedishPhonetics(input: String): String {
            var s = input.lowercase()
            s = s.replace("skj", "sj")
                .replace("stj", "sj")
                .replace("sch", "sj")
                .replace("kj", "tj")
                .replace("ck", "k")
            // Collapse repeated consecutive consonants (e.g. "katten" vs "katen")
            val collapsed = StringBuilder()
            for (c in s) {
                if (collapsed.isEmpty() || collapsed.last() != c) {
                    collapsed.append(c)
                }
            }
            return collapsed.toString()
        }

        private fun levenshteinDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val temp = dp[j]
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[j] = minOf(
                        dp[j] + 1,
                        dp[j - 1] + 1,
                        prev + cost
                    )
                    prev = temp
                }
            }
            return dp[b.length]
        }
    }
}
