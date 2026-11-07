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
    val needsPractice: Boolean = false,
    val heardText: String? = null
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
    val isCompleted: Boolean = false,
    val isAwaitingUnreadWords: Boolean = false,
    val unmatchedSinceAdvance: List<String> = emptyList()
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

        val workingWords = state.words.toMutableList()
        var cursor = state.currentWordIndex
        var clearedStuck = state.isWordStuckSupportVisible
        var struggled = state.struggledWordsNormalized
        val pending = state.unmatchedSinceAdvance.toMutableList()

        for (spoken in spokenTokens) {
            val spokenNorm = normalizeForAlignment(spoken.normalizedWord.ifBlank { spoken.rawWord })
            if (spokenNorm.isEmpty()) continue

            // 1. The current cursor word takes priority: a child correctly reading the word they're
            // actually on must never be credited to an earlier yellow word just because it repeats
            // elsewhere in the story (e.g. "en", "i", "och").
            val cursorMatched = cursor < workingWords.size &&
                isMatch(spokenNorm, workingWords[cursor].normalizedText, spoken.confidence, confidenceThreshold)
            if (cursorMatched) {
                workingWords[cursor] = workingWords[cursor].copy(isCompleted = true)
                clearedStuck = false
                cursor = (cursor + 1 until workingWords.size).firstOrNull { !workingWords[it].isCompleted }
                    ?: workingWords.size
                pending.clear()
                continue
            }

            // 2. The child can go back and read an earlier yellow (needsPractice, unread) word at any time.
            var yellowMatchedIndex = -1
            for (i in workingWords.indices) {
                val candidate = workingWords[i]
                if (!candidate.needsPractice || candidate.isCompleted) continue
                if (isMatch(spokenNorm, candidate.normalizedText, spoken.confidence, confidenceThreshold)) {
                    yellowMatchedIndex = i
                    break
                }
            }
            if (yellowMatchedIndex != -1) {
                // Keep needsPractice so it still shows up (soft yellow) as "read, but was flagged" in review.
                workingWords[yellowMatchedIndex] = workingWords[yellowMatchedIndex].copy(isCompleted = true)
                pending.clear()
                continue
            }

            // 3. Lookahead: the child may have skipped ahead past the cursor word.
            var matchedIndex = -1
            if (cursor < workingWords.size) {
                val maxLookaheadIndex = min(
                    workingWords.size - 1,
                    cursor + BalanceConfig.STT_LOOKAHEAD_WINDOW_WORDS - 1
                )

                for (candidateIdx in cursor..maxLookaheadIndex) {
                    val targetNorm = workingWords[candidateIdx].normalizedText
                    if (isMatch(spokenNorm, targetNorm, spoken.confidence, confidenceThreshold)) {
                        matchedIndex = candidateIdx
                        break
                    }
                }
            }

            if (matchedIndex != -1) {
                // Words between the cursor and the match were skipped over (misread/not heard): flag them
                // yellow, but they stay unread (not completed) so the child is never blocked.
                if (matchedIndex > cursor) {
                    val skipped = matchedIndex - cursor
                    for (i in cursor until matchedIndex) {
                        val off = i - cursor
                        val heard = if (off == skipped - 1 && pending.size > skipped) {
                            pending.drop(off).joinToString(" ")
                        } else {
                            pending.getOrNull(off)
                        }
                        workingWords[i] = workingWords[i].copy(
                            needsPractice = true,
                            heardText = heard ?: workingWords[i].heardText
                        )
                        struggled = struggled + workingWords[i].normalizedText
                    }
                }
                workingWords[matchedIndex] = workingWords[matchedIndex].copy(isCompleted = true)
                clearedStuck = false
                // Cursor advances to the next word that still needs to be read (skipping already-completed ones).
                cursor = (matchedIndex + 1 until workingWords.size).firstOrNull { !workingWords[it].isCompleted }
                    ?: workingWords.size
                pending.clear()
            } else {
                pending.add(spoken.rawWord.trim().ifBlank { spokenNorm })
                while (pending.size > BalanceConfig.STT_HEARD_TEXT_BUFFER_MAX_WORDS) {
                    pending.removeAt(0)
                }
                if (cursor < workingWords.size && workingWords[cursor].needsPractice) {
                    workingWords[cursor] = workingWords[cursor].copy(heardText = pending.joinToString(" "))
                }
            }
        }

        val allCompleted = workingWords.all { it.isCompleted }
        val reachedEnd = cursor >= workingWords.size
        return state.copy(
            words = workingWords,
            currentWordIndex = cursor,
            isWordStuckSupportVisible = if (allCompleted) false else clearedStuck,
            struggledWordsNormalized = struggled,
            isCompleted = allCompleted,
            isAwaitingUnreadWords = reachedEnd && !allCompleted,
            unmatchedSinceAdvance = pending.toList()
        )
    }

    /**
     * Shared similarity check used by the cursor, yellow-word, and lookahead match passes in
     * [processRecognizedTokens]: true when the spoken token is close enough (raw or confidence-weighted)
     * to the target word.
     */
    private fun isMatch(spokenNorm: String, targetNorm: String, confidence: Float, confidenceThreshold: Float): Boolean {
        val similarity = computeSwedishPhoneticSimilarity(spokenNorm, targetNorm)
        val weightedScore = similarity * confidence.coerceIn(0.6f, 1.0f)
        return similarity >= confidenceThreshold || weightedScore >= confidenceThreshold
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

        // Hesitation only flags the word yellow (still has to be read); it never skips it.
        val nextWords = if (showSupport && state.currentWord != null) {
            state.words.mapIndexed { idx, word ->
                if (idx == state.currentWordIndex) {
                    word.copy(
                        needsPractice = true,
                        heardText = word.heardText
                            ?: state.unmatchedSinceAdvance.joinToString(" ").ifBlank { null }
                    )
                } else {
                    word
                }
            }
        } else {
            state.words
        }

        return state.copy(
            words = nextWords,
            elapsedMillis = nextElapsed,
            isWordStuckSupportVisible = showSupport || state.isWordStuckSupportVisible,
            struggledWordsNormalized = nextStruggled
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
     * - AI mode penalty: yellow (needsPractice) words lower the WPM-based star count. Read yellow
     *   words weigh 1, unread yellow words weigh `BalanceConfig.READING_YELLOW_UNREAD_WEIGHT`. The
     *   weighted share of the story's words determines the penalty (0/1/2 stars), but the result
     *   never drops below 1 star.
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
        val wpmStars = when {
            wpmRatio >= BalanceConfig.READING_THREE_STAR_WPM_RATIO -> 3
            wpmRatio >= BalanceConfig.READING_TWO_STAR_WPM_RATIO -> 2
            else -> 1
        }

        val readYellowCount = state.words.count { it.needsPractice && it.isCompleted }
        val unreadYellow = state.words.filter { it.needsPractice && !it.isCompleted }
        val yellowWeight = readYellowCount.toFloat() + unreadYellow.size * BalanceConfig.READING_YELLOW_UNREAD_WEIGHT
        val yellowRatio = yellowWeight / wordCount.toFloat()
        val yellowPenalty = when {
            yellowRatio < BalanceConfig.READING_YELLOW_ONE_STAR_PENALTY_RATIO -> 0
            yellowRatio < BalanceConfig.READING_YELLOW_TWO_STAR_PENALTY_RATIO -> 1
            else -> 2
        }
        val stars = max(1, wpmStars - yellowPenalty)

        val noStruggledWords = state.words.none { it.needsPractice }
        val beatTimeChallenge = state.isTimedChallenge && safeSeconds <= timeRequirementSeconds
        val struggledWordsToLog = (state.struggledWordsNormalized + unreadYellow.map { it.normalizedText }).toList()

        return ReadingScoreResult(
            starsEarned = stars,
            achievedWpm = achievedWpm,
            referenceWpm = referenceWpm,
            eligibleForGift = true,
            eligibleForPronunciationAchievement = noStruggledWords,
            eligibleForTimedChallengeReward = beatTimeChallenge,
            struggledWordsToLog = struggledWordsToLog
        )
    }

    /**
     * Lets the child end an AI-Listening session without reading the remaining yellow
     * (skipped, needs-practice) words ("Gå vidare"). No-op outside of the "awaiting unread
     * words" state; otherwise marks the session completed while leaving `words` untouched so
     * the unread yellow words are still counted as unread in [evaluateSessionScore].
     */
    fun finishWithUnreadWords(state: ReadingAlignmentSessionState): ReadingAlignmentSessionState {
        if (state.mode != ReadingMode.AI_LISTENING || state.isCompleted || !state.isAwaitingUnreadWords) {
            return state
        }
        return state.copy(
            isCompleted = true,
            isAwaitingUnreadWords = false,
            isWordStuckSupportVisible = false
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
