package se.lixo.app.domain.model

/**
 * Centralized tunable production & balancing parameters corresponding to
 * project.md §2 and §8 ("Öppna detaljer (produktion / balans — ej kravblockerare)").
 */
object BalanceConfig {

    // --- Tutorial Defaults (§2 & §8) ---
    const val TUTORIAL_LEVEL_ID = "level_tutorial_0"
    const val TUTORIAL_STORY_SV =
        "Katten Lixo hittar en glittrande hatt i skogen. " +
        "Hatten lyser med varmt guld när Lixo läser högt. " +
        "Nu börjar ett magiskt äventyr bland sagor och stjärnor!"

    // --- Profile Creation (first-run setup & sibling profiles) ---
    /** Lowest age offered when creating a profile. */
    const val PROFILE_MIN_AGE_YEARS = 6
    /** Highest age offered when creating a profile. */
    const val PROFILE_MAX_AGE_YEARS = 12
    /** Default pre-selected age when creating a profile. */
    const val PROFILE_DEFAULT_AGE_YEARS = 8

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
     * Fixed wait in Manual Reading Mode before the done button ('Klar med läsningen')
     * unlocks; same for every story regardless of length, see doc/plans/017.
     */
    const val MANUAL_MODE_UNLOCK_SECONDS = 15

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

    /** Max recent unmatched STT words kept to show "Jag hörde" for yellow words. */
    const val STT_HEARD_TEXT_BUFFER_MAX_WORDS = 4

    /** Target words at or below this letter count are eligible for the short-word swallow/lenient rules. */
    const val STT_SHORT_WORD_MAX_LETTERS = 3

    /** Minimum raw phonetic similarity accepted for a short word, but only at the reading cursor. */
    const val STT_SHORT_WORD_LENIENT_THRESHOLD = 0.5f

    /** Minimum match threshold used for proper-noun tokens (names, invented words) instead of the default. */
    const val STT_PROPER_NOUN_THRESHOLD = 0.5f

    /** Per-word alternate spellings the recognizer commonly produces for names/invented words, keyed by [se.lixo.app.domain.engine.ForcedAlignmentEngine.Companion.normalizeForAlignment]d target word. */
    val STT_WORD_ALIASES: Map<String, List<String>> = mapOf(
        "lixo" to listOf("lixå", "lisso", "liksso", "lixxo", "licko", "lix", "lissa", "lixa", "liso", "leckso")
    )

    /** Requested number of N-best transcripts from the speech recognizer (`EXTRA_MAX_RESULTS`). */
    const val STT_MAX_RESULTS = 5

    /** Fallback token confidence used when the recognizer bundle has no `CONFIDENCE_SCORES`. */
    const val STT_DEFAULT_TOKEN_CONFIDENCE = 0.88f

    /** Max words joined into the single "upcoming phrase" bias string. */
    const val STT_BIAS_PHRASE_MAX_WORDS = 12

    /** Max total entries passed as recognizer biasing strings (`EXTRA_BIASING_STRINGS`). */
    const val STT_BIAS_MAX_STRINGS = 100

    /** iOS SFSpeechRecognitionTask hard limit is ~1 min; end audio and restart before it. */
    const val STT_IOS_TASK_RESTART_MILLIS = 50_000L

    /** Kill-switch: prefer the on-device speech recognizer over the cloud one when available. */
    const val STT_PREFER_ON_DEVICE = true

    /** Timeout for `checkRecognitionSupport` when probing on-device recognizer language support. */
    const val STT_ON_DEVICE_SUPPORT_TIMEOUT_MS = 2_000L

    /**
     * Milliseconds without word progress before showing gentle support options
     * (listen via TTS, see syllables, see explanation, or move on without penalty).
     */
    const val STUCK_WORD_PROMPT_DELAY_MS = 4_500L

    /**
     * Milliseconds stalled on the final story word before offering "Jag har läst klart"
     * (the recognizer probably missed the last word).
     */
    const val LAST_WORD_MISSED_FALLBACK_DELAY_MS = STUCK_WORD_PROMPT_DELAY_MS

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
     * (order 98) and n41 (order 101) doesn't block math progression. Orders 161..190 are the 30
     * geography levels (see [se.lixo.app.domain.content.GeographyCurriculum]), also unlocked
     * per subject.
     */
    const val CAMPAIGN_MAX_ORDER = 190

    // --- Privacy & Crashlytics Consent (§6) ---
    /**
     * Show Crashlytics opt-in consent dialog on the 3rd app launch so first test sessions
     * are never interrupted.
     */
    const val CRASHLYTICS_CONSENT_LAUNCH_COUNT = 3

    // --- Loot tuning moved to the content pack (023) ---
    // Rarity roll weights, chest cost and points, pity floors, onboarding levels, star unlocks,
    // and level/achievement/tutorial/set-piece reward item ids all live in the content pack's
    // `dropRules` now, so they can be changed from the cloud. See
    // doc/content/content-pack.md § Drop rules.

    /**
     * Max items awarded per [RewardSource] in a single run. LEVEL and STARS share one
     * "progression" slot (an onboarding/level item defers a star unlock to a later run); other
     * sources (FLITKISTA, GIFT, WEEKLY_STREAK, ACHIEVEMENT) may stack with it and each other.
     * ACHIEVEMENT is also capped at [MAX_ITEMS_PER_SOURCE_PER_RUN] per run: any further unlocks in
     * the same run stay unlocked and queue their reward item ids in
     * [ChildProfile.pendingAchievementRewardIds] for a later run (see doc/plans/004).
     */
    const val MAX_ITEMS_PER_SOURCE_PER_RUN = 1

    // --- Star-collection achievements (§2 "Epic/Legendary via prestationer", see doc/plans/004, doc/plans/014) ---
    /**
     * The legacy `ach_stars_<N>` tiers of the plan-014 COLLECT_STARS ladder: 160 levels x 3 stars =
     * 480 max, on a widening-gap curve, deliberately kept off the content pack's avatar star
     * unlocks (12/30/55/90/135) so the big moments don't land in the same run. The ladder's new 5
     * and 10 tiers (`ach_stars_5`/`ach_stars_10`) are NOT milestones here and also stay off those
     * thresholds. This is a getter (not an initializer) so BalanceConfig and AchievementLadders never
     * depend on each other during init. See doc/plans/014.
     */
    val STAR_ACHIEVEMENT_MILESTONES: List<Int>
        get() = ACHIEVEMENT_LADDERS.getValue(AchievementAction.COLLECT_STARS)
            .filter { it.legacyId != null }
            .map { it.target }

    /** The achievement id for a given [STAR_ACHIEVEMENT_MILESTONES] entry. */
    fun starAchievementId(stars: Int): String = "ach_stars_$stars"
}
