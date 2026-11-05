package se.lixo.app.domain.engine

import kotlin.math.max
import kotlin.random.Random
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject
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
    val newlyUnlockedItems: List<WardrobeItem>,
    val giftOutcome: GiftRollOutcome?,
    val streakIncrementedToday: Boolean
)

/**
 * Progression, Linear Campaign, Calendar-Day Streak, and Non-Lootbox Gift Engine
 * (`project.md` §2 "Progression & Kampanjstruktur", "Presenter / gåvor", "Streak"):
 *
 * - Linear Campaign (1..100): levels must be completed in order.
 * - Anti-Star-Farming: replaying an already 3-starred level awards 0 additional stars;
 *   replaying a 1- or 2-starred level only awards the positive delta (`max(0, newStars - previousBest)`).
 * - Non-Lootbox Gifts: extra repetition awards a gift with an item. If the rolled item is a duplicate,
 *   nothing happens (`isDuplicate = true`, `newlyUnlockedItem = null`). Unlimited gifts per day.
 * - Calendar-Day Streak (00:00–24:00 local time): a streak day is earned when at least 1 reading task
 *   AND 1 math task are completed on that calendar date.
 */
class ProgressionAndGiftEngine(
    private val random: Random = Random.Default
) {

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
        allWardrobeItems: List<WardrobeItem>,
        currentlyUnlockedItemIds: Set<String>,
        todayActivityBeforeRun: DailyActivityRecord,
        nowEpochMillis: Long
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

        val newlyUnlockedItems = mutableListOf<WardrobeItem>()
        val workingUnlockedIds = currentlyUnlockedItemIds.toMutableSet()

        // 1. Direct level reward item on first completion
        if (rewardItemIdForTask != null && !workingUnlockedIds.contains(rewardItemIdForTask)) {
            allWardrobeItems.find { it.id == rewardItemIdForTask }?.let { item ->
                newlyUnlockedItems.add(item)
                workingUnlockedIds.add(item.id)
            }
        }

        // 2. Star-threshold item unlocks matching subject
        for (item in allWardrobeItems) {
            if (!workingUnlockedIds.contains(item.id) &&
                item.unlockAchievementId == null &&
                !item.isWeeklyStreakSetPiece &&
                item.unlockStarRequirement in 1..newTotalStars
            ) {
                newlyUnlockedItems.add(item)
                workingUnlockedIds.add(item.id)
            }
        }

        // 3. Avatar unlocks via star milestones
        val newlyUnlockedAvatars = AvatarType.entries
            .filter { !profile.unlockedAvatars.contains(it) && newTotalStars >= it.unlockStarsRequired }
            .toSet()

        // 4. Extra repetition gift roll (`project.md` §2 "Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer.")
        val giftOutcome = if (isReplay && eligibleForRepetitionGift && allWardrobeItems.isNotEmpty()) {
            val outcome = rollPresentGift(
                subject = level.subject,
                allItems = allWardrobeItems,
                unlockedItemIds = workingUnlockedIds
            )
            if (outcome.newlyUnlockedItem != null) {
                newlyUnlockedItems.add(outcome.newlyUnlockedItem)
                workingUnlockedIds.add(outcome.newlyUnlockedItem.id)
            }
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
            allWardrobeItems.firstOrNull { it.isWeeklyStreakSetPiece && !workingUnlockedIds.contains(it.id) }?.let { setPiece ->
                newlyUnlockedItems.add(setPiece)
                workingUnlockedIds.add(setPiece.id)
            }
        }

        val nextLevelOrder = max(profile.currentLevelOrder, (level.order + 1).coerceAtMost(100))
        val updatedProfile = profile.copy(
            unlockedAvatars = profile.unlockedAvatars + newlyUnlockedAvatars,
            currentLevelOrder = nextLevelOrder,
            totalStars = newTotalStars,
            currentStreakDays = nextStreakDays,
            bestStreakDays = nextBestStreak,
            lastStreakDateIso = nextLastStreakDate,
            updatedAtEpochMillis = nowEpochMillis
        )

        return LevelCompletionOutcome(
            updatedProfile = updatedProfile,
            updatedLevelProgress = updatedProgress,
            netNewStarsAwarded = netNewStars,
            newlyUnlockedAvatars = newlyUnlockedAvatars,
            newlyUnlockedItems = newlyUnlockedItems,
            giftOutcome = giftOutcome,
            streakIncrementedToday = didIncrementStreak
        )
    }

    /**
     * Rolls a non-lootbox present (`project.md` §2 "Presenter / gåvor (inte lootbox):
     * Extra repetition ger en present med ett föremål. Dubblett -> ingenting händer.").
     */
    fun rollPresentGift(
        subject: Subject,
        allItems: List<WardrobeItem>,
        unlockedItemIds: Set<String>
    ): GiftRollOutcome {
        val candidatePool = allItems.filter { it.subjectTheme == subject }.ifEmpty { allItems }
        val totalWeight = candidatePool.sumOf { it.rarity.giftDropWeight }.coerceAtLeast(1)
        var pick = random.nextInt(totalWeight)
        var chosen = candidatePool.first()

        for (item in candidatePool) {
            pick -= item.rarity.giftDropWeight
            if (pick < 0) {
                chosen = item
                break
            }
        }

        val isDuplicate = unlockedItemIds.contains(chosen.id)
        return GiftRollOutcome(
            rolledItem = chosen,
            isDuplicate = isDuplicate,
            newlyUnlockedItem = if (isDuplicate) null else chosen
        )
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
