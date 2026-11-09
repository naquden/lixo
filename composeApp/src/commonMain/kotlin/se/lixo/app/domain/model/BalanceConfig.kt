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

    /**
     * Milliseconds without word progress before showing gentle support options
     * (listen via TTS, see syllables, see explanation, or move on without penalty).
     */
    const val STUCK_WORD_PROMPT_DELAY_MS = 4_500L

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

    // --- Effort Chest "Flitkistan" (§2 "Presenter / gåvor") ---
    /**
     * Chest progress is stored as integer half-points to avoid float drift. Reaching this
     * threshold (20 half-points = 10 points) awards a chest and the counter keeps the overflow.
     */
    const val CHEST_POINTS_THRESHOLD_HALF = 20

    /** Half-points awarded for a run that earns new stars or clears a new level (1 point). */
    const val CHEST_POINTS_PROGRESS_RUN_HALF = 2

    /** Half-points awarded for replaying an already-passed level with no new stars (0.5 point). */
    const val CHEST_POINTS_REPLAY_RUN_HALF = 1

    /**
     * Pity floors for Flitkista chests: the Nth chest since the last chest at or above that
     * tier is guaranteed to be at least that tier. A playthrough only yields a few dozen chests,
     * so with 18/10/4 % odds a child could otherwise go 30+ chests without an epic or 60+
     * without a legendary. These floors cap the drought at 4/10/20 chests. Only Flitkistan
     * counts; onboarding, the star curve, gifts and achievements do not.
     */
    const val PITY_RARE_PLUS_CHESTS = 4
    const val PITY_EPIC_PLUS_CHESTS = 10
    const val PITY_LEGENDARY_CHESTS = 20

    // --- Reward pacing (§2 "Belöningskurva") ---
    /**
     * Reward pacing has three layers that each cover a different part of a profile's lifetime:
     * 1. **Onboarding track** ([ONBOARDING_REWARD_LEVELS]): early, paced by levels cleared across
     *    all subjects, independent of how many stars a run earns.
     * 2. **Flitkistan**: unchanged, every 10 progress runs (see [CHEST_POINTS_THRESHOLD_HALF]).
     * 3. **Star curve**: a single global, widening-gap sequence from ~31 to 250 stars, generated
     *    by `tools/item_pipeline/generate_wardrobe_items.py`.
     */

    /**
     * Cleared-level counts (across all subjects) that award an onboarding item on the *next*
     * level cleared, i.e. `clearedLevelsCountBeforeRun + 1 in ONBOARDING_REWARD_LEVELS`:
     *
     * | Levels cleared | Cadence |
     * |---|---|
     * | 1–6 | every level |
     * | 7–20 | every 2nd level (8, 10, …, 20) |
     * | 21–40 | every 3rd level (23, 26, …, 38) |
     *
     * 19 slots in total.
     */
    val ONBOARDING_REWARD_LEVELS: Set<Int> = buildSet {
        addAll(1..6)
        addAll(8..20 step 2)
        addAll(23..40 step 3)
    }

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
     * 480 max, on a widening-gap curve, deliberately kept off the [AvatarType] unlock milestones (12,
     * 30, 55, 90, 135) so the big moments don't land in the same run. The ladder's new 5 and 10 tiers
     * (`ach_stars_5`/`ach_stars_10`) are NOT milestones here and also stay off the AvatarType
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
