package se.lixo.app.domain.engine

import kotlin.math.max
import kotlin.random.Random
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.UnlockedReward
import se.lixo.app.domain.model.WardrobeItem

data class GiftRollOutcome(
    val rolledItem: WardrobeItem,
    val isDuplicate: Boolean,
    val newlyUnlockedItem: WardrobeItem?
)

data class LevelCompletionOutcome(
    val updatedProfile: ChildProfile,
    val updatedLevelProgress: LevelProgress,
    val netNewStarsAwarded: Int,
    val newlyUnlockedAvatars: Set<AvatarType>,
    val newlyUnlockedRewards: List<UnlockedReward>,
    val giftOutcome: GiftRollOutcome?,
    val streakIncrementedToday: Boolean,
    val chestOutcome: GiftRollOutcome? = null,
    val chestPointsAwardedHalf: Int = 0,
    val newlyUnlockedAchievements: List<Achievement> = emptyList()
) {
    /** Derived view of [newlyUnlockedRewards] for call sites that only care about the items. */
    val newlyUnlockedItems: List<WardrobeItem> get() = newlyUnlockedRewards.map { it.item }
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
 * - Non-Lootbox Gifts: extra repetition awards a gift with an item, rolled rarity-first
 *   (Rarity.giftDropWeight 40/28/18/10/4 %), then uniformly within the rarity. If the rolled item
 *   is a duplicate, nothing happens (`isDuplicate = true`, `newlyUnlockedItem = null`). Unlimited
 *   gifts per day.
 * - Effort Chest "Flitkistan" (`project.md` §2 "Presenter / gåvor"): every run that earns new stars or
 *   clears a new level pays 1 point; a replay with no new stars pays 0.5 point (0 in manual mode / when
 *   not eligible for gifts). Points are stored as integer half-points. At 10 points (20 half-points) a
 *   chest is awarded with a random item from the catalog, excluding weekly-streak set pieces and
 *   achievement-linked items, and the counter keeps the overflow. The chest prefers unowned items
 *   (nearest rarity with an unowned item, lower first); only when every eligible item is owned does
 *   it roll a duplicate, which unlocks nothing. The pool has no subject bias. Pity floors
 *   ([BalanceConfig.PITY_RARE_PLUS_CHESTS]/[BalanceConfig.PITY_EPIC_PLUS_CHESTS]/
 *   [BalanceConfig.PITY_LEGENDARY_CHESTS] = 4/10/20) guarantee at least RARE/EPIC/LEGENDARY every
 *   4th/10th/20th Flitkista chest since the last one at or above that tier; only Flitkistan counts
 *   toward these counters.
 * - Calendar-Day Streak (00:00–24:00 local time): a streak day is earned when at least 1 reading task
 *   AND 1 math task are completed on that calendar date.
 * - Reward pacing (`doc/plans/001-reward-pacing-early-chests.md`, `project.md` §2 "Belöningskurva"):
 *   three layers avoid both floods and droughts. (1) An **onboarding track**
 *   ([BalanceConfig.ONBOARDING_REWARD_LEVELS]) awards one item on specific levels-cleared counts,
 *   tapering from every level to every 2nd to every 3rd. (2) **Flitkistan**, unchanged. (3) A
 *   **star curve** that only starts once the onboarding track has mostly run its course. At most
 *   [BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN] item is awarded per [RewardSource] per run; LEVEL
 *   and STARS share one "progression" slot, so an onboarding/task item in a run defers any star
 *   unlock to a later run (the star item stays eligible, since `unlockStarRequirement <= newTotalStars`
 *   keeps holding). Other sources may stack in the same run. The celebration order of
 *   [LevelCompletionOutcome.newlyUnlockedRewards] is: progression (LEVEL or STARS), FLITKISTA,
 *   GIFT, WEEKLY_STREAK, ACHIEVEMENT. The onboarding-track
 *   item is the level's curated, app-owned `rewardItemId`
 *   ([se.lixo.app.data.repository.LixoRepository.buildLevelRewardPool], see
 *   `doc/plans/002-level-reward-item-ids.md`) when it resolves, is unowned, and is chest-eligible;
 *   otherwise it falls back to [rollOnboardingItem]. Off-schedule clears and replays ignore
 *   `rewardItemIdForTask` entirely.
 * - Achievements (`doc/plans/004-achievement-tracking.md`): [AchievementEngine] evaluates the
 *   profile's definitions against a [RunFacts] built from the run. At most
 *   [BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN] achievement reward (1) is handed out per run, in
 *   `achievementDefinitions` order, merging any already-pending ids
 *   ([ChildProfile.pendingAchievementRewardIds]) with newly unlocked ones; the rest stay queued
 *   for a later run.
 */
class ProgressionAndGiftEngine(
    private val random: Random = Random.Default,
    private val achievementEngine: AchievementEngine = AchievementEngine()
) {

    companion object {
        /**
         * Shared eligibility filter for chest/onboarding/level rolls: excludes weekly-streak set
         * pieces and achievement-linked items, which have their own dedicated reward moments.
         */
        fun isChestEligible(item: WardrobeItem): Boolean =
            !item.isWeeklyStreakSetPiece && item.unlockAchievementId == null

        /**
         * Items preferred for the onboarding track (`doc/plans/002-level-reward-item-ids.md`):
         * chest-eligible, COMMON/UNCOMMON and not part of the star curve, so onboarding never eats
         * into the star curve's items.
         */
        fun isOnboardingPreferred(item: WardrobeItem): Boolean =
            isChestEligible(item) &&
                (item.rarity == Rarity.COMMON || item.rarity == Rarity.UNCOMMON) &&
                item.unlockStarRequirement == 0

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
         * The pity floor for the next Flitkista chest (see [rollChest]): LEGENDARY if the next
         * chest would be the [BalanceConfig.PITY_LEGENDARY_CHESTS]th since the last legendary,
         * else EPIC if it would be the [BalanceConfig.PITY_EPIC_PLUS_CHESTS]th since the last
         * EPIC+, else RARE if it would be the [BalanceConfig.PITY_RARE_PLUS_CHESTS]th since the
         * last RARE+, else COMMON (no floor).
         */
        fun chestMinRarity(
            chestsSinceRarePlus: Int,
            chestsSinceEpicPlus: Int,
            chestsSinceLegendary: Int
        ): Rarity = when {
            chestsSinceLegendary + 1 >= BalanceConfig.PITY_LEGENDARY_CHESTS -> Rarity.LEGENDARY
            chestsSinceEpicPlus + 1 >= BalanceConfig.PITY_EPIC_PLUS_CHESTS -> Rarity.EPIC
            chestsSinceRarePlus + 1 >= BalanceConfig.PITY_RARE_PLUS_CHESTS -> Rarity.RARE
            else -> Rarity.COMMON
        }

        /**
         * The chest-eligible rarity tiers that have no unowned item left in [allItems] given
         * [unlockedItemIds] (see [isChestEligible]). A tier with zero chest-eligible items also
         * counts as exhausted, so a catalog missing a rarity entirely never floors pity forever at
         * that tier (see [rollChest]/[advanceChestPity]).
         */
        fun exhaustedChestTiers(allItems: List<WardrobeItem>, unlockedItemIds: Set<String>): Set<Rarity> =
            Rarity.entries
                .filter { r -> allItems.none { isChestEligible(it) && it.rarity == r && it.id !in unlockedItemIds } }
                .toSet()

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
     * @param rewardItemIdForTask the level's app-owned curated item id
     * ([se.lixo.app.data.repository.LixoRepository.levelRewardItemIdFor]), used as the
     * onboarding-track item on scheduled first clears only; ignored on replays and off-schedule
     * clears.
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
        rewardItemIdForTask: String?,
        clearedLevelsCountBeforeRun: Int,
        allWardrobeItems: List<WardrobeItem>,
        currentlyUnlockedItemIds: Set<String>,
        todayActivityBeforeRun: DailyActivityRecord,
        nowEpochMillis: Long,
        achievementDefinitions: List<Achievement> = emptyList(),
        runFacts: RunFacts? = null
    ): LevelCompletionOutcome {
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

        val newlyUnlockedRewards = mutableListOf<UnlockedReward>()
        val workingUnlockedIds = currentlyUnlockedItemIds.toMutableSet()

        fun award(item: WardrobeItem, source: RewardSource, labelDetailSv: String? = null) {
            newlyUnlockedRewards += UnlockedReward(item, source, labelDetailSv)
            workingUnlockedIds += item.id
        }

        // 1. Progression slot: at most 1 item, shared by LEVEL (curated level item or onboarding
        // roll) and STARS. An onboarding/task item in this run defers any star unlock to a later run.

        // 1a/1b. Onboarding track (`BalanceConfig.ONBOARDING_REWARD_LEVELS`): on a scheduled first
        // clear, award the level's curated, app-owned item (`rewardItemIdForTask`,
        // doc/plans/002-level-reward-item-ids.md) if it resolves to a catalog item, isn't already
        // owned, and is chest-eligible; otherwise fall back to 001's random roll. Off-schedule
        // clears and replays never award a level item.
        val onOnboardingSlot = !isReplay && (clearedLevelsCountBeforeRun + 1) in BalanceConfig.ONBOARDING_REWARD_LEVELS
        val taskRewardItem = rewardItemIdForTask?.let { id ->
            allWardrobeItems.find { it.id == id }.also {
                if (it == null) println("LixoRewards: WARN ${level.id} rewardItemId=$id is not in the wardrobe catalog; using the onboarding roll instead")
            }
        }
        val curatedItem = taskRewardItem?.takeIf { onOnboardingSlot && it.id !in workingUnlockedIds && isChestEligible(it) }
        val progressionItem = when {
            !onOnboardingSlot -> null
            curatedItem != null -> curatedItem.also { println("LixoRewards: ${level.id} clear #${clearedLevelsCountBeforeRun + 1} awarded curated level item ${it.id}") }
            else -> rollOnboardingItem(allWardrobeItems, workingUnlockedIds)
        }
        progressionItem?.let { award(it, RewardSource.LEVEL) }

        // 1c. Star-threshold item unlocks, lowest eligible first, limited to the remaining
        // progression slot(s) for this run.
        val progressionSlotsLeft = BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN - newlyUnlockedRewards.size
        if (progressionSlotsLeft > 0) {
            allWardrobeItems
                .filter { item ->
                    item.id !in workingUnlockedIds &&
                        item.unlockAchievementId == null &&
                        !item.isWeeklyStreakSetPiece &&
                        item.unlockStarRequirement in 1..newTotalStars
                }
                .sortedWith(compareBy({ it.unlockStarRequirement }, { it.id }))
                .take(progressionSlotsLeft)
                .forEach { award(it, RewardSource.STARS) }
        }

        // 2. Avatar unlocks via star milestones
        val newlyUnlockedAvatars = AvatarType.entries
            .filter { !profile.unlockedAvatars.contains(it) && newTotalStars >= it.unlockStarsRequired }
            .toSet()

        // 3. Effort chest "Flitkistan" points (`project.md` §2 "Presenter / gåvor"): 1 point for a
        // run that earns new stars or clears a new level, 0.5 point for a replay with no new stars,
        // 0 in manual / non-gift-eligible mode. At 10 points (20 half-points) a chest is rolled and
        // the counter keeps the overflow.
        val chestPointsAwardedHalf = chestPointsForRun(
            netNewStars = netNewStars,
            isReplay = isReplay,
            eligible = eligibleForRepetitionGift
        )
        var chestProgress = profile.chestProgressHalfPoints + chestPointsAwardedHalf
        val chestOutcome = if (chestProgress >= BalanceConfig.CHEST_POINTS_THRESHOLD_HALF) {
            chestProgress -= BalanceConfig.CHEST_POINTS_THRESHOLD_HALF
            rollChest(
                allWardrobeItems,
                workingUnlockedIds,
                profile.chestsSinceRarePlus,
                profile.chestsSinceEpicPlus,
                profile.chestsSinceLegendary
            )?.also { o ->
                o.newlyUnlockedItem?.let { award(it, RewardSource.FLITKISTA) }
                println(
                    "LixoRewards: Flitkista floor=${
                        chestMinRarity(profile.chestsSinceRarePlus, profile.chestsSinceEpicPlus, profile.chestsSinceLegendary)
                    } cap=${
                        Rarity.entries.lastOrNull { it !in exhaustedChestTiers(allWardrobeItems, workingUnlockedIds) }
                    } rolled=${o.rolledItem.rarity} id=${o.rolledItem.id} dup=${o.isDuplicate}"
                )
            }
        } else {
            null
        }
        val pityProfile = chestOutcome?.let {
            advanceChestPity(profile, it.rolledItem.rarity, exhaustedChestTiers(allWardrobeItems, workingUnlockedIds))
        } ?: profile

        // 4. Extra repetition gift roll (`project.md` §2 "Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer.")
        val giftOutcome = if (isReplay && eligibleForRepetitionGift && allWardrobeItems.isNotEmpty()) {
            val outcome = rollPresentGift(
                subject = level.subject,
                allItems = allWardrobeItems,
                unlockedItemIds = workingUnlockedIds
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

        // 6. Unlock Weekly Streak Set piece if reaching 7-day streak multiple
        if (didIncrementStreak && nextStreakDays > 0 && nextStreakDays % BalanceConfig.WEEKLY_STREAK_DAYS_FOR_SET_REWARD == 0) {
            allWardrobeItems.firstOrNull {
                it.isWeeklyStreakSetPiece && it.unlockAchievementId == null && it.id !in workingUnlockedIds
            }?.let { setPiece ->
                award(setPiece, RewardSource.WEEKLY_STREAK)
            }
        }

        // 7. Achievements (`doc/plans/004-achievement-tracking.md`): evaluate against the
        // post-run streak/star values, then award at most 1 chest this run (progression-defined
        // order), merging already-pending ids with newly unlocked ones; the rest stay pending.
        val facts = (runFacts ?: RunFacts(level.subject, level.id, completedInAiMode))
            .copy(streakDaysAfterRun = nextStreakDays, totalStarsAfterRun = newTotalStars)
        val evaluation = achievementEngine.evaluate(facts, AchievementState.of(profile), achievementDefinitions)
        val newlyIds = evaluation.newlyUnlocked.map { it.id }.toSet()
        val queue = achievementDefinitions.filter { it.id in profile.pendingAchievementRewardIds || it.id in newlyIds }
        var slotsLeft = BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN
        val stillPending = linkedSetOf<String>()
        for (ach in queue) {
            val item = allWardrobeItems.find { it.id == ach.rewardItemId }
            if (item == null) {
                println("LixoRewards: WARN achievement ${ach.id} rewardItemId=${ach.rewardItemId} not in catalog")
                continue
            }
            if (item.id in workingUnlockedIds) {
                continue
            }
            if (slotsLeft > 0) {
                award(item, RewardSource.ACHIEVEMENT, ach.titleSv)
                slotsLeft--
                println("LixoRewards: achievement ${ach.id} awarded ${item.id}")
            } else {
                stillPending += ach.id
            }
        }

        val nextLevelOrder = max(profile.currentLevelOrder, (level.order + 1).coerceAtMost(BalanceConfig.CAMPAIGN_MAX_ORDER))
        val updatedProfile = profile.copy(
            unlockedAvatars = profile.unlockedAvatars + newlyUnlockedAvatars,
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

        return LevelCompletionOutcome(
            updatedProfile = updatedProfile,
            updatedLevelProgress = updatedProgress,
            netNewStarsAwarded = netNewStars,
            newlyUnlockedAvatars = newlyUnlockedAvatars,
            newlyUnlockedRewards = newlyUnlockedRewards,
            giftOutcome = giftOutcome,
            streakIncrementedToday = didIncrementStreak,
            chestOutcome = chestOutcome,
            chestPointsAwardedHalf = chestPointsAwardedHalf,
            newlyUnlockedAchievements = evaluation.newlyUnlocked
        )
    }

    /**
     * Rolls a non-lootbox present (`project.md` §2 "Presenter / gåvor (inte lootbox):
     * Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer."). Presents
     * never prefer unowned items, so a duplicate roll keeps "Dubblett -> ingenting händer".
     */
    fun rollPresentGift(
        subject: Subject,
        allItems: List<WardrobeItem>,
        unlockedItemIds: Set<String>
    ): GiftRollOutcome {
        val eligible = allItems.filter { isChestEligible(it) }.ifEmpty { allItems }
        val pool = eligible.filter { it.subjectTheme == subject }.ifEmpty { eligible }
        return rollWeighted(pool, unlockedItemIds, preferUnowned = false)
    }

    /**
     * The highest chest-eligible rarity tier in [pool] that still has at least one unowned item,
     * or null when everything in [pool] is owned.
     */
    private fun highestTierWithUnowned(pool: List<WardrobeItem>, unlockedItemIds: Set<String>): Rarity? =
        exhaustedChestTiers(pool, unlockedItemIds).let { ex -> Rarity.entries.lastOrNull { it !in ex } }

    /**
     * Rolls a chest item for the effort chest "Flitkistan" (`project.md` §2 "Presenter / gåvor"):
     * the pool is every catalog item except weekly-streak set pieces and achievement-linked items,
     * with no subject bias. The chest prefers unowned items (nearest rarity with an unowned item,
     * lower before higher); only when every eligible item is already owned does it roll a
     * duplicate, which unlocks nothing. [chestsSinceRarePlus]/[chestsSinceEpicPlus]/
     * [chestsSinceLegendary] (default 0, see [ChildProfile]) set a pity floor via
     * [chestMinRarity]; when none applies (all 0) the roll is identical to today's. The floor is
     * capped at the highest tier that still has an unowned chest-eligible item, so it never points
     * at a fully owned tier, falling back to COMMON when everything is owned. Returns null if the
     * pool is empty.
     */
    fun rollChest(
        allItems: List<WardrobeItem>,
        unlockedItemIds: Set<String>,
        chestsSinceRarePlus: Int = 0,
        chestsSinceEpicPlus: Int = 0,
        chestsSinceLegendary: Int = 0
    ): GiftRollOutcome? {
        val candidatePool = allItems.filter { isChestEligible(it) }
        if (candidatePool.isEmpty()) return null
        val floor = minOf(
            chestMinRarity(chestsSinceRarePlus, chestsSinceEpicPlus, chestsSinceLegendary),
            highestTierWithUnowned(candidatePool, unlockedItemIds) ?: Rarity.COMMON
        )
        return rollWeighted(candidatePool, unlockedItemIds, preferUnowned = true, minRarity = floor)
    }

    /**
     * Rolls the onboarding-track item for a run matching [BalanceConfig.ONBOARDING_REWARD_LEVELS]
     * (`doc/plans/001-reward-pacing-early-chests.md`). This is the fallback used when the level's
     * curated item (`rewardItemIdForTask`, doc/plans/002-level-reward-item-ids.md) is missing or
     * already owned. The pool excludes already-owned items up front, so an onboarding slot can
     * never land a duplicate. Prefers COMMON/UNCOMMON items that aren't also part of the star curve
     * (`unlockStarRequirement == 0`), so onboarding never eats into the star curve's items; falls
     * back to the whole pool if that's empty. Since the preferred pool is already unowned,
     * `preferUnowned = true` doesn't change the result, but gives it the same 40:28 COMMON:UNCOMMON
     * target odds within that pool. Returns null if there's nothing left to award.
     */
    fun rollOnboardingItem(allItems: List<WardrobeItem>, unlockedItemIds: Set<String>): WardrobeItem? {
        val pool = allItems.filter { isChestEligible(it) && it.id !in unlockedItemIds }
        if (pool.isEmpty()) return null
        val preferred = pool.filter { isOnboardingPreferred(it) }
        return rollWeighted(preferred.ifEmpty { pool }, unlockedItemIds, preferUnowned = true).newlyUnlockedItem
    }

    /**
     * Two-step roll over [pool]: first picks a rarity present in the pool weighted by
     * [se.lixo.app.domain.model.Rarity.giftDropWeight] ([pickRarity]), then picks an item uniformly
     * within that rarity. [minRarity] (default [Rarity.COMMON], a no-op) is a pity floor (see
     * [rollChest]/[chestMinRarity]): step 1 only considers rarities at or above the floor, falling
     * back to the whole pool if none qualify. When [preferUnowned] is true, step 2 first tries
     * unowned items at or above the floor (nearest to the rolled rarity first, via
     * [rarityFallbackOrder]), then unowned items below the floor, before falling back to a
     * duplicate. If every rarity is fully owned (or [preferUnowned] is false), it falls back to a
     * uniform pick within the rolled rarity, and the usual duplicate rule applies: a duplicate roll
     * unlocks nothing.
     */
    private fun rollWeighted(
        pool: List<WardrobeItem>,
        unlockedItemIds: Set<String>,
        preferUnowned: Boolean,
        minRarity: Rarity = Rarity.COMMON
    ): GiftRollOutcome {
        val byRarity = pool.groupBy { it.rarity }
        val present = Rarity.entries.filter { !byRarity[it].isNullOrEmpty() }
        val rolled = pickRarity(present.filter { it >= minRarity }.ifEmpty { present })

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
     * item in the pool) weighted by [se.lixo.app.domain.model.Rarity.giftDropWeight]. Weights are
     * summed only over [present], so a rarity's share is implicitly spread proportionally over the
     * others when it's missing from the pool.
     */
    private fun pickRarity(present: List<Rarity>): Rarity {
        val total = present.sumOf { it.giftDropWeight }.coerceAtLeast(1)
        var pick = random.nextInt(total)
        for (r in present) {
            pick -= r.giftDropWeight
            if (pick < 0) return r
        }
        return present.first()
    }

    /**
     * Chest points awarded for a single run (`project.md` §2 "Presenter / gåvor"), stored as
     * integer half-points: 0 when not eligible for gifts (e.g. manual reading mode's anti-cheat
     * "no gifts" rule), 1 point (2 half-points) for a run earning new stars or clearing a new
     * level, 0.5 point (1 half-point) for a replay with no new stars.
     */
    fun chestPointsForRun(netNewStars: Int, isReplay: Boolean, eligible: Boolean): Int {
        if (!eligible) return 0
        return if (netNewStars > 0 || !isReplay) {
            BalanceConfig.CHEST_POINTS_PROGRESS_RUN_HALF
        } else {
            BalanceConfig.CHEST_POINTS_REPLAY_RUN_HALF
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
