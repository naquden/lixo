package se.lixo.app.domain.model

/**
 * Centralized tunable production & balancing parameters corresponding to
 * project.md §2 and §8 ("Öppna detaljer (produktion / balans — ej kravblockerare)").
 */
object BalanceConfig {

    // --- Tutorial Defaults (§2 & §8) ---
    const val TUTORIAL_LEVEL_ID = "level_tutorial_0"
    const val TUTORIAL_FIRST_REWARD_ITEM_ID = "item_hat_starter_explorer"
    const val TUTORIAL_STORY_SV =
        "Katten Lixo hittar en glittrande hatt i skogen. " +
        "Hatten lyser med varmt guld när Lixo läser högt. " +
        "Nu börjar ett magiskt äventyr bland sagor och stjärnor!"

    // --- Reading Star Thresholds (§2 "Poängsättning läsning") ---
    /**
     * 3 stars: AI mode at >= 1.0x reference WPM (approx. TTS reading tempo).
     * 2 stars: AI mode at >= 0.5x reference WPM.
     * 1 star: Completed reading regardless of time, OR Manual mode (capped at 1 star).
     */
    const val READING_THREE_STAR_WPM_RATIO = 1.0f
    const val READING_TWO_STAR_WPM_RATIO = 0.5f
    const val MANUAL_MODE_MAX_STARS = 1

    /**
     * AI-Listening yellow-word penalty (`project.md` §2 "Poängsättning läsning"): read yellow
     * words weigh 1 and unread yellow words weigh [READING_YELLOW_UNREAD_WEIGHT] when computing
     * the weighted share of the story's words that were flagged. That share crossing
     * [READING_YELLOW_ONE_STAR_PENALTY_RATIO] costs 1 star, crossing
     * [READING_YELLOW_TWO_STAR_PENALTY_RATIO] costs 2 stars (never below 1 star total).
     */
    const val READING_YELLOW_UNREAD_WEIGHT = 2f
    const val READING_YELLOW_ONE_STAR_PENALTY_RATIO = 0.10f
    const val READING_YELLOW_TWO_STAR_PENALTY_RATIO = 0.25f

    /**
     * Minimum time per word in Manual Reading Mode before "Jag har läst klart" button unlocks.
     * Prevents instant button mashing while remaining fair for fast child readers.
     */
    const val MANUAL_MODE_MIN_SECONDS_PER_WORD = 0.35f
    const val MANUAL_MODE_MIN_TOTAL_SECONDS = 4.0f

    /**
     * Reference Words Per Minute (WPM) by difficulty tier (1..6, roughly ages 6..12).
     */
    fun referenceWpmForDifficulty(difficulty: Int): Int = when (difficulty.coerceIn(1, 6)) {
        1 -> 40
        2 -> 55
        3 -> 70
        4 -> 85
        5 -> 100
        else -> 115
    }

    // --- Forced Alignment & STT Thresholds (§2 & §8) ---
    /**
     * Minimum normalized similarity score [0.0..1.0] required to align a spoken token
     * with the expected target word in the story sequence.
     */
    const val STT_WORD_MATCH_CONFIDENCE_THRESHOLD = 0.65f

    /**
     * Lookahead window size (in words) so if STT merges or drops a short function word,
     * the child isn't stuck when reading the next word naturally.
     */
    const val STT_LOOKAHEAD_WINDOW_WORDS = 3

    /**
     * Milliseconds without word progress before showing gentle support options
     * (listen via TTS, see syllables, see explanation, or move on without penalty).
     */
    const val STUCK_WORD_PROMPT_DELAY_MS = 4_500L

    // --- Math Star Thresholds (§8) ---
    const val MATH_THREE_STAR_MAX_HINTS = 1
    const val MATH_TWO_STAR_TIME_MULTIPLIER = 1.5f

    // --- Streak Rules (§2 "Streak") ---
    /**
     * A calendar day (00:00–24:00 local time) counts as a streak day when the child
     * completes at least 1 reading task AND 1 math task (one per core category).
     */
    const val STREAK_REQUIRED_READING_TASKS_PER_DAY = 1
    const val STREAK_REQUIRED_MATH_TASKS_PER_DAY = 1
    const val WEEKLY_STREAK_DAYS_FOR_SET_REWARD = 7

    // --- Campaign size ---
    /**
     * Total number of ordered campaign slots (project.md §2 "Linjär kampanj"). Math is
     * independent from Swedish/English: Swedish (40 levels) and English (20 levels) occupy
     * orders 1..100 (order%5==0 -> English, else even -> math slot, else Swedish), while the
     * 100 math levels live at the even, non-multiple-of-10 orders within 1..100 (n1..n40) and at
     * orders 101..160 (n41..n100) (see [se.lixo.app.domain.content.MathCurriculum]). Unlocking is
     * per subject, using the previous level of the same subject, so the order gap between n40
     * (order 98) and n41 (order 101) doesn't block math progression.
     */
    const val CAMPAIGN_MAX_ORDER = 160

    // --- Privacy & Crashlytics Consent (§6) ---
    /**
     * Show Crashlytics opt-in consent dialog on the 3rd app launch so first test sessions
     * are never interrupted.
     */
    const val CRASHLYTICS_CONSENT_LAUNCH_COUNT = 3
}
