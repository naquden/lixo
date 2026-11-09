package se.lixo.app.domain.engine

import kotlin.math.max
import kotlin.random.Random
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ChestTheme
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.DropRules
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.UnlockType
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.UnlockedReward

data class GiftRollOutcome(
    val rolledItem: ContentItem,
    val isDuplicate: Boolean,
    val newlyUnlockedItem: ContentItem?
)

data class LevelCompletionOutcome(
    val updatedProfile: ChildProfile,
    val updatedLevelProgress: LevelProgress,
    val netNewStarsAwarded: Int,
    /** Newly unlocked avatar ids, already sorted by `(unlock.stars, order)`. */
    val newlyUnlockedAvatarIds: List<String>,
    val newlyUnlockedRewards: List<UnlockedReward>,
    val giftOutcome: GiftRollOutcome?,
    val streakIncrementedToday: Boolean,
    val chestOutcome: GiftRollOutcome? = null,
    val chestPointsAwardedHalf: Int = 0,
    val newlyUnlockedAchievements: List<Achievement> = emptyList(),
    val nextChallengeByRewardItemId: Map<String, Achievement> = emptyMap(),
    /**
     * Next tiers (with profile progress) for achievements resolved this run whose reward item was
     * already owned (no chest, no duplicate), so the "Ny utmaning!" step still shows (doc/plans/018).
     */
    val ownedRewardNextChallenges: List<Achievement> = emptyList(),
    /** The run's active event's chest theme (025's [EventSnapshot.chestTheme]), or null outside an event. */
    val chestTheme: ChestTheme? = null
) {
    /** Derived view of [newlyUnlockedRewards] for call sites that only care about the items. */
    val newlyUnlockedItems: List<ContentItem> get() = newlyUnlockedRewards.map { it.item }
}

/**
 * Progression, Linear Campaign, Calendar-Day Streak, and Non-Lootbox Gift Engine
 * (`project.md` §2 "Progression & Kampanjstruktur", "Presenter / gåvor", "Streak"):
 *
 * - Campaign (orders 1..160; 40 Swedish, 100 Math, 20 English). Math is independent from
 *   Swedish/English: Swedish and English are interleaved across orders 1..100, while math sits
 *   at the even, non-multiple-of-10 orders within 1..100 (n1..n40) plus orders 101..160
 *   (n41..n100, see [se.lixo.app.domain.content.MathCurriculum]). Unlocking is per subject world:
 *   levels must be completed in order within their subject, regardless of their position among
 *   other subjects.
 * - Anti-Star-Farming: replaying an already 3-starred level awards 0 additional stars;
 *   replaying a 1- or 2-starred level only awards the positive delta (`max(0, newStars - previousBest)`).
 * - Non-Lootbox Gifts: extra repetition awards a gift with an item, rolled rarity-first using the
 *   run's `content.dropRules.rarityWeights` (see doc/content/content-pack.md § Drop rules), then
 *   uniformly within the rarity. If the rolled item is a duplicate, nothing happens
 *   (`isDuplicate = true`, `newlyUnlockedItem = null`). Unlimited gifts per day.
 * - Effort Chest "Flitkistan" (`project.md` §2 "Presenter / gåvor"): every run that earns new stars or
 *   clears a new level pays `dropRules.progressRunHalfPoints`; a replay with no new stars pays
 *   `dropRules.replayRunHalfPoints` (0 in manual mode / when not eligible for gifts). Points are
 *   stored as integer half-points. At `dropRules.chestCostHalfPoints` a chest is awarded with a
 *   random item from the catalog, excluding weekly-streak set pieces and achievement-linked items,
 *   and the counter keeps the overflow. The chest prefers unowned items (nearest rarity with an
 *   unowned item, lower first); only when every eligible item is owned does it roll a duplicate,
 *   which unlocks nothing. The pool has no subject bias. Pity floors (`dropRules.pity`) guarantee
 *   at least that tier every Nth Flitkista chest since the last one at or above that tier; only
 *   Flitkistan counts toward these counters. See doc/content/content-pack.md § Drop rules.
 * - Calendar-Day Streak (00:00–24:00 local time): a streak day is earned when at least 1 reading task
 *   AND 1 math task are completed on that calendar date.
 * - Reward pacing (`doc/plans/001-reward-pacing-early-chests.md`, `project.md` §2 "Belöningskurva"):
 *   three layers avoid both floods and droughts. (1) An **onboarding track**
 *   (`dropRules.onboarding.levels`) awards one item on specific levels-cleared counts, tapering
 *   from every level to every 2nd to every 3rd. (2) **Flitkistan**, unchanged. (3) A **star curve**
 *   that only starts once the onboarding track has mostly run its course. At most
 *   [BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN] item is awarded per [RewardSource] per run; LEVEL
 *   and STARS share one "progression" slot, so an onboarding/task item in a run defers any star
 *   unlock to a later run. Other sources may stack in the same run. The celebration order of
 *   [LevelCompletionOutcome.newlyUnlockedRewards] is: progression (LEVEL or STARS), FLITKISTA,
 *   GIFT, WEEKLY_STREAK, ACHIEVEMENT. The onboarding-track item is the level's curated, app-owned
 *   item (`content.levelRewardItemId(level.order)`, from the content pack's `dropRules.levelRewards`,
 *   see `doc/plans/002-level-reward-item-ids.md`) when it resolves, is unowned, and is
 *   chest-eligible; otherwise it falls back to [rollOnboardingItem]. Off-schedule clears and
 *   replays never resolve the curated item.
 * - Achievements (`doc/plans/004-achievement-tracking.md`, tiered into ladders by
 *   `doc/plans/014-achievement-ladders.md`): [AchievementEngine] evaluates the profile's
 *   definitions against a [RunFacts] built from the run. At most
 *   [BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN] achievement reward (1) is handed out per run, in
 *   `achievementDefinitions` order, merging any already-pending ids
 *   ([ChildProfile.pendingAchievementRewardIds]) with newly unlocked ones; the rest stay queued
 *   for a later run. The reward item for an achievement is resolved from the run's
 *   `content.dropRules.achievementRewards[id]`, falling back to the achievement's own
 *   `rewardItemId`, so the run's frozen pack (not the achievement definition baked in at
 *   repository init) decides the item. [LevelCompletionOutcome.nextChallengeByRewardItemId] maps
 *   each awarded achievement's *resolved* reward item id to its chain's next tier (if any), for
 *   the "Ny utmaning!" prompt. When the reward item is already owned, nothing is awarded or
 *   queued, but the chain's next tier goes into [LevelCompletionOutcome.ownedRewardNextChallenges].
 * - Item flags moved from hand-written booleans to the content pack's `pool` (022) and
 *   `dropRules` (023): weekly-streak set pieces, achievement-linked items and star-curve
 *   requirements are all resolved from [ActiveContent] now. Retired items are still excluded from
 *   every pool here (chests, onboarding, gifts, the weekly-streak set piece, the star curve) but
 *   stay owned and renderable if already unlocked.
 * - Holiday-special events (025): [EventSnapshot] (`EventEngine.snapshot`, frozen at run start
 *   like [content]) lets [rollChest]/[rollPresentGift] roll from the active event's pool first,
 *   with probability `dropBoost.eventItemChance`/`presentGiftChance`; otherwise they fall back to
 *   the standard roll, now also treating ended `joinStandard` event items as chest-eligible (see
 *   [isChestEligible]). [LevelCompletionOutcome.chestTheme] carries the active event's chest
 *   look through to the celebration.
 */
class ProgressionAndGiftEngine(
    private val random: Random = Random.Default,
    private val achievementEngine: AchievementEngine = AchievementEngine()
) {

    companion object {
        /**
         * Shared eligibility filter for chest/onboarding/level rolls: standard-pool, non-retired
         * items only. Event/achievement/level/setpiece-pool items have their own dedicated reward
         * moments.
         */
        fun isChestEligible(item: ContentItem): Boolean =
            item.pool == ItemPool.STANDARD && !item.retired

        /**
         * [isChestEligible] extended to cover ended `joinStandard` event items (025): once an
         * event with `afterEnd = joinStandard` has ended, its items behave like standard items
         * for every chest/pity purpose.
         */
        fun isChestEligible(item: ContentItem, events: EventSnapshot): Boolean =
            isChestEligible(item) || item.id in events.joinedStandardItemIds

        /**
         * Items preferred for the onboarding track (`doc/plans/002-level-reward-item-ids.md`):
         * chest-eligible, COMMON/UNCOMMON and not part of the star curve, so onboarding never eats
         * into the star curve's items.
         */
        fun isOnboardingPreferred(item: ContentItem, content: ActiveContent): Boolean =
            isChestEligible(item) &&
                (item.rarity == Rarity.COMMON || item.rarity == Rarity.UNCOMMON) &&
                item.id !in content.starRequirementByItemId

        /**
         * The fallback order used when `preferUnowned` finds the rolled rarity fully owned
         * (see [rollWeighted]): [rolled] first, then nearest distance first, and the lower
         * rarity before the higher one at equal distance. E.g. RARE gives
         * `[RARE, UNCOMMON, EPIC, COMMON, LEGENDARY]`; COMMON gives
         * `[COMMON, UNCOMMON, RARE, EPIC, LEGENDARY]`.
         */
        fun rarityFallbackOrder(rolled: Rarity): List<Rarity> {
            val entries = Rarity.entries
            val order = mutableListOf(rolled)
            for (d in 1 until entries.size) {
                entries.getOrNull(rolled.ordinal - d)?.let { order += it }
                entries.getOrNull(rolled.ordinal + d)?.let { order += it }
            }
            return order
        }

        /**
         * The pity floor for the next Flitkista chest (see [rollChest]), from the run's
         * `dropRules.pity` (doc/content/content-pack.md § Drop rules): LEGENDARY if the next
         * chest would be the `pity[LEGENDARY]`th since the last legendary, else EPIC if it would
         * be the `pity[EPIC]`th since the last EPIC+, else RARE if it would be the `pity[RARE]`th
         * since the last RARE+, else COMMON (no floor). A tier missing from [pity] has no floor.
         */
        fun chestMinRarity(
            chestsSinceRarePlus: Int,
            chestsSinceEpicPlus: Int,
            chestsSinceLegendary: Int,
            pity: Map<Rarity, Int>
        ): Rarity = when {
            pity[Rarity.LEGENDARY]?.let { chestsSinceLegendary + 1 >= it } == true -> Rarity.LEGENDARY
            pity[Rarity.EPIC]?.let { chestsSinceEpicPlus + 1 >= it } == true -> Rarity.EPIC
            pity[Rarity.RARE]?.let { chestsSinceRarePlus + 1 >= it } == true -> Rarity.RARE
            else -> Rarity.COMMON
        }

        /**
         * The rarity tiers that have no unowned item left in [pool] given [unlockedItemIds], with
         * no eligibility filter of its own: callers pass an already-filtered [pool] (see
         * [exhaustedChestTiers] for the chest-eligible pool, or an event pool directly, which
         * [isChestEligible] alone would otherwise report as fully exhausted).
         */
        fun exhaustedTiers(pool: List<ContentItem>, unlockedItemIds: Set<String>): Set<Rarity> =
            Rarity.entries
                .filter { r -> pool.none { it.rarity == r && it.id !in unlockedItemIds } }
                .toSet()

        /**
         * The chest-eligible rarity tiers that have no unowned item left in [allItems] given
         * [unlockedItemIds] (see [isChestEligible]). A tier with zero chest-eligible items also
         * counts as exhausted, so a catalog missing a rarity entirely never floors pity forever at
         * that tier (see [rollChest]/[advanceChestPity]).
         */
        fun exhaustedChestTiers(
            allItems: List<ContentItem>,
            unlockedItemIds: Set<String>,
            events: EventSnapshot = EventSnapshot.NONE
        ): Set<Rarity> = exhaustedTiers(allItems.filter { isChestEligible(it, events) }, unlockedItemIds)

        /**
         * Advances [profile]'s Flitkista pity counters after rolling [rolled]: resets the counter
         * for [rolled]'s tier and every lower tier to 0, and increments the rest. A counter also
         * resets when its tier and every higher tier are in [exhaustedTiers] (see
         * [exhaustedChestTiers]), since there is nothing left to pity toward there.
         */
        fun advanceChestPity(
            profile: ChildProfile,
            rolled: Rarity,
            exhaustedTiers: Set<Rarity> = emptySet()
        ): ChildProfile = profile.copy(
            chestsSinceRarePlus = if (rolled >= Rarity.RARE ||
                exhaustedTiers.containsAll(listOf(Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY))
            ) 0 else profile.chestsSinceRarePlus + 1,
            chestsSinceEpicPlus = if (rolled >= Rarity.EPIC ||
                exhaustedTiers.containsAll(listOf(Rarity.EPIC, Rarity.LEGENDARY))
            ) 0 else profile.chestsSinceEpicPlus + 1,
            chestsSinceLegendary = if (rolled == Rarity.LEGENDARY || Rarity.LEGENDARY in exhaustedTiers) {
                0
            } else {
                profile.chestsSinceLegendary + 1
            }
        )
    }

    /**
     * Checks whether a campaign level is unlocked for the given profile.
     * Unlocking is per-subject "world" (plan: "Ämnesväljare med tre världar"): a level requires
     * the previous level *in the same subject* (largest `order` < levelOrder with the same subject)
     * to have >= 1 star. The first level of each subject is always unlocked.
     */
    fun isLevelUnlocked(
        levelOrder: Int,
        progressByLevelId: Map<String, LevelProgress>,
        levels: List<CampaignLevel>
    ): Boolean {
        val currentLevel = levels.find { it.order == levelOrder } ?: return levelOrder <= 1
        val prevLevel = levels
            .filter { it.subject == currentLevel.subject && it.order < levelOrder }
            .maxByOrNull { it.order }
            ?: return true
        val prevProgress = progressByLevelId[prevLevel.id]
        return (prevProgress?.starsEarned ?: 0) >= 1
    }

    /**
     * The level the child should land on when entering a subject's world: the first unlocked
     * level in that subject with 0 stars, or the last level of that subject if all are starred.
     */
    fun currentLevelForSubject(
        subject: Subject,
        levels: List<CampaignLevel>,
        progressByLevelId: Map<String, LevelProgress>
    ): CampaignLevel? {
        val subjectLevels = levels.filter { it.subject == subject }.sortedBy { it.order }
        if (subjectLevels.isEmpty()) return null

        for (level in subjectLevels) {
            val isUnlocked = isLevelUnlocked(level.order, progressByLevelId, levels)
            val stars = progressByLevelId[level.id]?.starsEarned ?: 0
            if (isUnlocked && stars == 0) return level
        }
        return subjectLevels.last()
    }

    /**
     * Processes level completion, calculates net star gain (preventing farming on maxed levels),
     * unlocks eligible avatars/items, rolls a repetition gift if applicable, and updates calendar-day streak.
     *
     * Rewards are collected as [UnlockedReward]s in celebration order: progression (curated level
     * item or onboarding roll, else a star-threshold item — these share one slot per run), then
     * Flitkistan, the repetition gift, and the weekly streak set piece. See the class KDoc for the
     * full reward-pacing rules.
     *
     * @param content the active content pack: avatars, items and drop rules (`content.dropRules`,
     * see doc/content/content-pack.md § Drop rules) all come from here. Callers should pass a
     * snapshot frozen at run start (doc/plans/023), so a mid-run content hot swap (024) can't
     * change this run's outcome. The curated level item comes from
     * `content.levelRewardItemId(level.order)`, resolved only on a scheduled first clear.
     */
    fun recordLevelCompletion(
        profile: ChildProfile,
        level: CampaignLevel,
        previousProgress: LevelProgress?,
        earnedStarsThisRun: Int,
        completedInAiMode: Boolean,
        elapsedSeconds: Int,
        achievedWpm: Int,
        completedTimedChallenge: Boolean,
        eligibleForRepetitionGift: Boolean,
        clearedLevelsCountBeforeRun: Int,
        content: ActiveContent,
        currentlyUnlockedItemIds: Set<String>,
        todayActivityBeforeRun: DailyActivityRecord,
        nowEpochMillis: Long,
        achievementDefinitions: List<Achievement> = emptyList(),
        runFacts: RunFacts? = null,
        events: EventSnapshot = EventSnapshot.NONE
    ): LevelCompletionOutcome {
        val rules = content.dropRules
        val clampedStars = earnedStarsThisRun.coerceIn(1, 3)
        val previousBestStars = previousProgress?.starsEarned ?: 0
        val isReplay = previousProgress != null

        // Anti-farming rule: "Omspel av redan maxade banor ger inte fler stjärnor" (project.md §2)
        val netNewStars = max(0, clampedStars - previousBestStars)
        val updatedBestStars = max(previousBestStars, clampedStars)
        val newTotalStars = profile.totalStars + netNewStars

        val updatedProgress = LevelProgress(
            profileId = profile.id,
            levelId = level.id,
            levelOrder = level.order,
            starsEarned = updatedBestStars,
            completedInAiMode = (previousProgress?.completedInAiMode == true) || completedInAiMode,
            bestTimeSeconds = if (previousProgress == null) elapsedSeconds else minOf(previousProgress.bestTimeSeconds, elapsedSeconds),
            bestWpm = max(previousProgress?.bestWpm ?: 0, achievedWpm),
            completedTimedChallenge = (previousProgress?.completedTimedChallenge == true) || completedTimedChallenge,
            timesCompleted = (previousProgress?.timesCompleted ?: 0) + 1,
            updatedAtEpochMillis = nowEpochMillis
        )

        val allItems = content.items
        val newlyUnlockedRewards = mutableListOf<UnlockedReward>()
        val workingUnlockedIds = currentlyUnlockedItemIds.toMutableSet()

        fun award(item: ContentItem, source: RewardSource, labelDetailSv: String? = null) {
            newlyUnlockedRewards += UnlockedReward(item, source, labelDetailSv)
            workingUnlockedIds += item.id
        }

        // 1. Progression slot: at most 1 item, shared by LEVEL (curated level item or onboarding
        // roll) and STARS. An onboarding/task item in this run defers any star unlock to a later run.

        // 1a/1b. Onboarding track (`dropRules.onboarding.levels`): on a scheduled first clear,
        // award the level's curated, app-owned item (`content.levelRewardItemId(level.order)`,
        // doc/plans/002-level-reward-item-ids.md) if it resolves to a catalog item, isn't already
        // owned, and is chest-eligible; otherwise fall back to 001's random roll. Off-schedule
        // clears and replays never award a level item.
        val onOnboardingSlot = !isReplay && (clearedLevelsCountBeforeRun + 1) in rules.onboarding.levels
        val curatedItem = if (onOnboardingSlot) {
            content.levelRewardItemId(level.order)?.let { id ->
                content.itemsById[id].also {
                    if (it == null) println("LixoRewards: WARN ${level.id} levelRewards itemId=$id is not in the content pack; using the onboarding roll instead")
                }
            }?.takeIf { it.id !in workingUnlockedIds && isChestEligible(it) }
        } else {
            null
        }
        val progressionItem = when {
            !onOnboardingSlot -> null
            curatedItem != null -> curatedItem.also { println("LixoRewards: ${level.id} clear #${clearedLevelsCountBeforeRun + 1} awarded curated level item ${it.id}") }
            else -> rollOnboardingItem(content, workingUnlockedIds)
        }
        progressionItem?.let { award(it, RewardSource.LEVEL) }

        // 1c. Star-threshold item unlocks, lowest eligible first, limited to the remaining
        // progression slot(s) for this run.
        val progressionSlotsLeft = BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN - newlyUnlockedRewards.size
        if (progressionSlotsLeft > 0) {
            content.dropRules.starUnlocks
                .filter { it.stars in 1..newTotalStars && it.itemId !in workingUnlockedIds }
                .mapNotNull { unlock -> content.itemsById[unlock.itemId]?.takeIf { it.pool == ItemPool.STANDARD && !it.retired }?.let { unlock.stars to it } }
                .sortedWith(compareBy({ it.first }, { it.second.id }))
                .take(progressionSlotsLeft)
                .forEach { (_, item) -> award(item, RewardSource.STARS) }
        }

        // 2. Avatar unlocks via star milestones, sorted by (unlock.stars, order).
        val newlyUnlockedAvatarIds = content.avatarsOrdered
            .filter { it.unlock.type == UnlockType.STARS && it.unlock.stars <= newTotalStars && it.id !in profile.unlockedAvatarIds }
            .sortedWith(compareBy({ it.unlock.stars }, { it.order }))
            .map { it.id }

        // 3. Effort chest "Flitkistan" points (`project.md` §2 "Presenter / gåvor"): 1 point for a
        // run that earns new stars or clears a new level, 0.5 point for a replay with no new stars,
        // 0 in manual / non-gift-eligible mode. At 10 points (20 half-points) a chest is rolled and
        // the counter keeps the overflow.
        val chestPointsAwardedHalf = chestPointsForRun(
            netNewStars = netNewStars,
            isReplay = isReplay,
            eligible = eligibleForRepetitionGift,
            dropRules = rules
        )
        var chestProgress = profile.chestProgressHalfPoints + chestPointsAwardedHalf
        val chestOutcome = if (chestProgress >= rules.chestCostHalfPoints) {
            chestProgress -= rules.chestCostHalfPoints
            rollChest(
                allItems,
                workingUnlockedIds,
                rules,
                profile.chestsSinceRarePlus,
                profile.chestsSinceEpicPlus,
                profile.chestsSinceLegendary,
                events
            )?.also { o ->
                o.newlyUnlockedItem?.let { award(it, RewardSource.FLITKISTA) }
                println(
                    "LixoRewards: Flitkista floor=${
                        chestMinRarity(profile.chestsSinceRarePlus, profile.chestsSinceEpicPlus, profile.chestsSinceLegendary, rules.pity)
                    } cap=${
                        Rarity.entries.lastOrNull { it !in exhaustedChestTiers(allItems, workingUnlockedIds, events) }
                    } rolled=${o.rolledItem.rarity} id=${o.rolledItem.id} dup=${o.isDuplicate}"
                )
            }
        } else {
            null
        }
        val pityProfile = chestOutcome?.let {
            advanceChestPity(profile, it.rolledItem.rarity, exhaustedChestTiers(allItems, workingUnlockedIds, events))
        } ?: profile

        // 4. Extra repetition gift roll (`project.md` §2 "Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer.")
        val giftOutcome = if (isReplay && eligibleForRepetitionGift && allItems.isNotEmpty()) {
            val outcome = rollPresentGift(
                subject = level.subject,
                allItems = allItems,
                unlockedItemIds = workingUnlockedIds,
                dropRules = rules,
                events = events
            )
            outcome.newlyUnlockedItem?.let { award(it, RewardSource.GIFT) }
            outcome
        } else {
            null
        }

        // 5. Update Daily Activity & Calendar-Day Streak (00:00 - 24:00 local time)
        val updatedDaily = when (level.subject) {
            Subject.SWEDISH -> todayActivityBeforeRun.copy(
                readingSecondsCompleted = todayActivityBeforeRun.readingSecondsCompleted + elapsedSeconds,
                readingTasksCompleted = todayActivityBeforeRun.readingTasksCompleted + 1
            )
            Subject.MATH -> todayActivityBeforeRun.copy(
                mathTasksCompleted = todayActivityBeforeRun.mathTasksCompleted + 1,
                mathProblemsCompleted = todayActivityBeforeRun.mathProblemsCompleted + 5
            )
            Subject.ENGLISH -> todayActivityBeforeRun.copy(
                englishTasksCompleted = todayActivityBeforeRun.englishTasksCompleted + 1,
                readingTasksCompleted = todayActivityBeforeRun.readingTasksCompleted + 1
            )
        }

        val (nextStreakDays, nextBestStreak, nextLastStreakDate, didIncrementStreak) = evaluateCalendarDayStreak(
            currentStreakDays = profile.currentStreakDays,
            bestStreakDays = profile.bestStreakDays,
            lastStreakDateIso = profile.lastStreakDateIso,
            todayActivity = updatedDaily
        )

        // 6. Unlock Weekly Streak Set piece if reaching 7-day streak multiple. Excludes items that
        // are also achievement rewards (e.g. item_hat_weekly_hero), which have their own dedicated
        // reward moment.
        if (didIncrementStreak && nextStreakDays > 0 && nextStreakDays % BalanceConfig.WEEKLY_STREAK_DAYS_FOR_SET_REWARD == 0) {
            rules.weeklyStreakSetPieceItemIds
                .firstNotNullOfOrNull { id ->
                    content.itemsById[id]?.takeIf {
                        it.id !in workingUnlockedIds && !it.retired && it.id !in content.achievementRewardItemIds
                    }
                }
                ?.let { setPiece -> award(setPiece, RewardSource.WEEKLY_STREAK) }
        }

        // 7. Achievements (`doc/plans/004-achievement-tracking.md`): evaluate against the
        // post-run streak/star values, then award at most 1 chest this run (progression-defined
        // order), merging already-pending ids with newly unlocked ones; the rest stay pending.
        val facts = (runFacts ?: RunFacts(level.subject, level.id, completedInAiMode))
            .copy(streakDaysAfterRun = nextStreakDays, totalStarsAfterRun = newTotalStars, earnedStarsThisRun = clampedStars)
        val evaluation = achievementEngine.evaluate(facts, AchievementState.of(profile), achievementDefinitions)
        val newlyIds = evaluation.newlyUnlocked.map { it.id }.toSet()
        val queue = achievementDefinitions.filter { it.id in profile.pendingAchievementRewardIds || it.id in newlyIds }
        var slotsLeft = BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN
        val stillPending = linkedSetOf<String>()
        val awardedAchievements = mutableListOf<Achievement>()
        val ownedRewardAchievements = mutableListOf<Achievement>()
        val resolvedRewardIdByAch = mutableMapOf<String, String>()
        for (ach in queue) {
            val rewardItemId = rules.achievementRewards[ach.id] ?: ach.rewardItemId
            val item = content.itemsById[rewardItemId]
            if (item == null) {
                println("LixoRewards: WARN achievement ${ach.id} rewardItemId=$rewardItemId not in catalog")
                continue
            }
            resolvedRewardIdByAch[ach.id] = rewardItemId
            if (item.id in workingUnlockedIds) {
                ownedRewardAchievements += ach
                println("LixoRewards: achievement ${ach.id} reward ${item.id} already owned, no chest")
                continue
            }
            if (slotsLeft > 0) {
                award(item, RewardSource.ACHIEVEMENT, ach.titleSv)
                slotsLeft--
                awardedAchievements += ach
                println("LixoRewards: achievement ${ach.id} awarded ${item.id}")
            } else {
                stillPending += ach.id
            }
        }

        val nextLevelOrder = max(profile.currentLevelOrder, (level.order + 1).coerceAtMost(BalanceConfig.CAMPAIGN_MAX_ORDER))
        val updatedProfile = profile.copy(
            unlockedAvatarIds = profile.unlockedAvatarIds + newlyUnlockedAvatarIds,
            currentLevelOrder = nextLevelOrder,
            totalStars = newTotalStars,
            currentStreakDays = nextStreakDays,
            bestStreakDays = nextBestStreak,
            lastStreakDateIso = nextLastStreakDate,
            updatedAtEpochMillis = nowEpochMillis,
            chestProgressHalfPoints = chestProgress,
            achievementProgress = evaluation.state.progress,
            achievementCountedLevelIds = evaluation.state.countedLevelIds,
            unlockedAchievementIds = evaluation.state.unlockedIds,
            pendingAchievementRewardIds = stillPending,
            chestsSinceRarePlus = pityProfile.chestsSinceRarePlus,
            chestsSinceEpicPlus = pityProfile.chestsSinceEpicPlus,
            chestsSinceLegendary = pityProfile.chestsSinceLegendary
        )

        val nextChallengeByRewardItemId = awardedAchievements.mapNotNull { ach ->
            AchievementEngine.nextTier(ach, achievementDefinitions, updatedProfile.unlockedAchievementIds)
                ?.let { next ->
                    resolvedRewardIdByAch.getValue(ach.id) to
                        AchievementEngine.withProfileProgress(listOf(next), updatedProfile).single()
                }
        }.toMap()

        val awardedNextIds = nextChallengeByRewardItemId.values.mapTo(HashSet()) { it.id }
        val ownedRewardNextChallenges = AchievementEngine.withProfileProgress(
            ownedRewardAchievements.mapNotNull {
                AchievementEngine.nextTier(it, achievementDefinitions, updatedProfile.unlockedAchievementIds)
            }.distinctBy { it.id }.filter { it.id !in awardedNextIds },
            updatedProfile
        )

        return LevelCompletionOutcome(
            updatedProfile = updatedProfile,
            updatedLevelProgress = updatedProgress,
            netNewStarsAwarded = netNewStars,
            newlyUnlockedAvatarIds = newlyUnlockedAvatarIds,
            newlyUnlockedRewards = newlyUnlockedRewards,
            giftOutcome = giftOutcome,
            streakIncrementedToday = didIncrementStreak,
            chestOutcome = chestOutcome,
            chestPointsAwardedHalf = chestPointsAwardedHalf,
            newlyUnlockedAchievements = evaluation.newlyUnlocked,
            nextChallengeByRewardItemId = nextChallengeByRewardItemId,
            ownedRewardNextChallenges = ownedRewardNextChallenges,
            chestTheme = events.chestTheme
        )
    }

    /**
     * Rolls a non-lootbox present (`project.md` §2 "Presenter / gåvor (inte lootbox):
     * Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer."). Presents
     * never prefer unowned items, so a duplicate roll keeps "Dubblett -> ingenting händer". Rarity
     * odds come from [dropRules]`.rarityWeights` (doc/content/content-pack.md § Drop rules).
     */
    fun rollPresentGift(
        subject: Subject,
        allItems: List<ContentItem>,
        unlockedItemIds: Set<String>,
        dropRules: DropRules,
        events: EventSnapshot = EventSnapshot.NONE
    ): GiftRollOutcome {
        val eventPool = allItems.filter { it.id in events.eventPoolItemIds }
        if (eventPool.isNotEmpty() &&
            eventPool.any { it.id !in unlockedItemIds } &&
            events.presentGiftChance > 0f &&
            random.nextFloat() < events.presentGiftChance
        ) {
            return rollWeighted(eventPool, unlockedItemIds, preferUnowned = false, weights = dropRules.rarityWeights)
        }
        val eligible = allItems.filter { isChestEligible(it, events) }.ifEmpty { allItems }
        val pool = eligible.filter { it.subjectTheme == subject }.ifEmpty { eligible }
        return rollWeighted(pool, unlockedItemIds, preferUnowned = false, weights = dropRules.rarityWeights)
    }

    /**
     * The highest chest-eligible rarity tier in [pool] that still has at least one unowned item,
     * or null when everything in [pool] is owned.
     */
    private fun highestTierWithUnowned(pool: List<ContentItem>, unlockedItemIds: Set<String>): Rarity? =
        exhaustedTiers(pool, unlockedItemIds).let { ex -> Rarity.entries.lastOrNull { it !in ex } }

    /**
     * Rolls a chest item for the effort chest "Flitkistan" (`project.md` §2 "Presenter / gåvor"):
     * the pool is every catalog item except weekly-streak set pieces and achievement-linked items,
     * with no subject bias. The chest prefers unowned items (nearest rarity with an unowned item,
     * lower before higher); only when every eligible item is already owned does it roll a
     * duplicate, which unlocks nothing. [chestsSinceRarePlus]/[chestsSinceEpicPlus]/
     * [chestsSinceLegendary] (default 0, see [ChildProfile]) set a pity floor via
     * [chestMinRarity] using [dropRules]`.pity`; when none applies (all 0) the roll is identical
     * to today's. The floor is capped at the highest tier that still has an unowned
     * chest-eligible item, so it never points at a fully owned tier, falling back to COMMON when
     * everything is owned. Rarity odds come from [dropRules]`.rarityWeights`
     * (doc/content/content-pack.md § Drop rules). Returns null if the pool is empty.
     */
    fun rollChest(
        allItems: List<ContentItem>,
        unlockedItemIds: Set<String>,
        dropRules: DropRules,
        chestsSinceRarePlus: Int = 0,
        chestsSinceEpicPlus: Int = 0,
        chestsSinceLegendary: Int = 0,
        events: EventSnapshot = EventSnapshot.NONE
    ): GiftRollOutcome? {
        val eventPool = allItems.filter { it.id in events.eventPoolItemIds }
        if (eventPool.isNotEmpty() &&
            eventPool.any { it.id !in unlockedItemIds } &&
            events.eventItemChance > 0f &&
            random.nextFloat() < events.eventItemChance
        ) {
            val floor = minOf(
                chestMinRarity(chestsSinceRarePlus, chestsSinceEpicPlus, chestsSinceLegendary, dropRules.pity),
                highestTierWithUnowned(eventPool, unlockedItemIds) ?: Rarity.COMMON
            )
            return rollWeighted(eventPool, unlockedItemIds, preferUnowned = true, weights = dropRules.rarityWeights, minRarity = floor)
        }

        val candidatePool = allItems.filter { isChestEligible(it, events) }
        if (candidatePool.isEmpty()) return null
        val floor = minOf(
            chestMinRarity(chestsSinceRarePlus, chestsSinceEpicPlus, chestsSinceLegendary, dropRules.pity),
            highestTierWithUnowned(candidatePool, unlockedItemIds) ?: Rarity.COMMON
        )
        return rollWeighted(candidatePool, unlockedItemIds, preferUnowned = true, weights = dropRules.rarityWeights, minRarity = floor)
    }

    /**
     * Rolls the onboarding-track item for a run matching `content.dropRules.onboarding.levels`
     * (`doc/plans/001-reward-pacing-early-chests.md`). This is the fallback used when the level's
     * curated item (`content.levelRewardItemId(level.order)`, doc/plans/002-level-reward-item-ids.md)
     * is missing or already owned. The pool excludes already-owned items up front, so an
     * onboarding slot can never land a duplicate. Prefers COMMON/UNCOMMON items that aren't also
     * part of the star curve, so onboarding never eats into the star curve's items; falls back to
     * the whole pool if that's empty. Since the preferred pool is already unowned,
     * `preferUnowned = true` doesn't change the result, but gives it the same odds within that
     * pool as [dropRules]`.rarityWeights` (doc/content/content-pack.md § Drop rules). Returns null
     * if there's nothing left to award.
     */
    fun rollOnboardingItem(content: ActiveContent, unlockedItemIds: Set<String>): ContentItem? {
        val pool = content.items.filter { isChestEligible(it) && it.id !in unlockedItemIds }
        if (pool.isEmpty()) return null
        val preferred = pool.filter { isOnboardingPreferred(it, content) }
        return rollWeighted(
            preferred.ifEmpty { pool },
            unlockedItemIds,
            preferUnowned = true,
            weights = content.dropRules.rarityWeights
        ).newlyUnlockedItem
    }

    /**
     * Two-step roll over [pool]: first picks a rarity present in the pool weighted by [weights]
     * (from `content.dropRules.rarityWeights`, see doc/content/content-pack.md § Drop rules;
     * [pickRarity]), then picks an item uniformly within that rarity. [minRarity] (default
     * [Rarity.COMMON], a no-op) is a pity floor (see [rollChest]/[chestMinRarity]): step 1 only
     * considers rarities at or above the floor, falling back to the whole pool if none qualify.
     * When [preferUnowned] is true, step 2 first tries unowned items at or above the floor
     * (nearest to the rolled rarity first, via [rarityFallbackOrder]), then unowned items below
     * the floor, before falling back to a duplicate. If every rarity is fully owned (or
     * [preferUnowned] is false), it falls back to a uniform pick within the rolled rarity, and the
     * usual duplicate rule applies: a duplicate roll unlocks nothing.
     */
    private fun rollWeighted(
        pool: List<ContentItem>,
        unlockedItemIds: Set<String>,
        preferUnowned: Boolean,
        weights: Map<Rarity, Int>,
        minRarity: Rarity = Rarity.COMMON
    ): GiftRollOutcome {
        val byRarity = pool.groupBy { it.rarity }
        val present = Rarity.entries.filter { !byRarity[it].isNullOrEmpty() }
        val rolled = pickRarity(present.filter { it >= minRarity }.ifEmpty { present }, weights)

        val chosen = (
            if (preferUnowned) {
                val order = rarityFallbackOrder(rolled).let { o ->
                    o.filter { it >= minRarity } + o.filter { it < minRarity }
                }
                order.firstNotNullOfOrNull { r ->
                    byRarity[r].orEmpty().filter { it.id !in unlockedItemIds }.ifEmpty { null }
                }?.let { it[random.nextInt(it.size)] }
            } else {
                null
            }
        ) ?: byRarity.getValue(rolled).let { it[random.nextInt(it.size)] }

        val isDuplicate = unlockedItemIds.contains(chosen.id)
        return GiftRollOutcome(
            rolledItem = chosen,
            isDuplicate = isDuplicate,
            newlyUnlockedItem = if (isDuplicate) null else chosen
        )
    }

    /**
     * Step 1 of [rollWeighted]: picks a rarity from [present] (the rarities that have at least one
     * item in the pool) weighted by [weights] (`content.dropRules.rarityWeights`, see
     * doc/content/content-pack.md § Drop rules; a missing rarity's weight is 0). Weights are
     * summed only over [present], so a rarity's share is implicitly spread proportionally over the
     * others when it's missing from the pool. Always returns a valid rarity, even when every
     * weight in [present] is 0.
     */
    private fun pickRarity(present: List<Rarity>, weights: Map<Rarity, Int>): Rarity {
        fun weight(r: Rarity) = (weights[r] ?: 0).coerceAtLeast(0)
        val total = present.sumOf { weight(it) }.coerceAtLeast(1)
        var pick = random.nextInt(total)
        for (r in present) {
            pick -= weight(r)
            if (pick < 0) return r
        }
        return present.first()
    }

    /**
     * Chest points awarded for a single run (`project.md` §2 "Presenter / gåvor"), stored as
     * integer half-points from [dropRules] (doc/content/content-pack.md § Drop rules): 0 when not
     * eligible for gifts (e.g. manual reading mode's anti-cheat "no gifts" rule),
     * `dropRules.progressRunHalfPoints` for a run earning new stars or clearing a new level,
     * `dropRules.replayRunHalfPoints` for a replay with no new stars.
     */
    fun chestPointsForRun(netNewStars: Int, isReplay: Boolean, eligible: Boolean, dropRules: DropRules): Int {
        if (!eligible) return 0
        return if (netNewStars > 0 || !isReplay) {
            dropRules.progressRunHalfPoints
        } else {
            dropRules.replayRunHalfPoints
        }
    }

    /**
     * Evaluates calendar-day streak (`project.md` §2 "Per kalenderdag kl. 00–24 lokal tid.
     * Tills vidare: en streak-dag = ett avklarat uppdrag per kategori (t.ex. en läsning och ett matteuppdrag)").
     */
    fun evaluateCalendarDayStreak(
        currentStreakDays: Int,
        bestStreakDays: Int,
        lastStreakDateIso: String?,
        todayActivity: DailyActivityRecord
    ): StreakEvaluationResult {
        val qualifiesToday =
            todayActivity.readingTasksCompleted >= BalanceConfig.STREAK_REQUIRED_READING_TASKS_PER_DAY &&
            todayActivity.mathTasksCompleted >= BalanceConfig.STREAK_REQUIRED_MATH_TASKS_PER_DAY

        if (!qualifiesToday) {
            return StreakEvaluationResult(
                newStreakDays = currentStreakDays,
                newBestStreakDays = bestStreakDays,
                newLastStreakDateIso = lastStreakDateIso,
                incrementedToday = false
            )
        }

        val todayDate = LocalDate.parse(todayActivity.calendarDateIso)
        if (lastStreakDateIso == todayActivity.calendarDateIso) {
            // Already counted today's streak
            return StreakEvaluationResult(
                newStreakDays = currentStreakDays,
                newBestStreakDays = bestStreakDays,
                newLastStreakDateIso = lastStreakDateIso,
                incrementedToday = false
            )
        }

        val isConsecutiveYesterday = if (lastStreakDateIso != null) {
            val prevDate = LocalDate.parse(lastStreakDateIso)
            prevDate.daysUntil(todayDate) == 1
        } else {
            false
        }

        val nextStreak = if (isConsecutiveYesterday) currentStreakDays + 1 else 1
        val nextBest = max(bestStreakDays, nextStreak)
        return StreakEvaluationResult(
            newStreakDays = nextStreak,
            newBestStreakDays = nextBest,
            newLastStreakDateIso = todayActivity.calendarDateIso,
            incrementedToday = true
        )
    }
}

data class StreakEvaluationResult(
    val newStreakDays: Int,
    val newBestStreakDays: Int,
    val newLastStreakDateIso: String?,
    val incrementedToday: Boolean
)
