package se.lixo.app.domain.engine

import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Subject

/**
 * Facts about a single completed run that [AchievementEngine] needs to evaluate achievement
 * progress (doc/plans/004). [streakDaysAfterRun] and [totalStarsAfterRun] are always overwritten
 * by [ProgressionAndGiftEngine.recordLevelCompletion] with the post-run values once they're
 * known (step 5/the net-new-stars calculation); callers should leave them at their defaults.
 */
data class RunFacts(
    val subject: Subject,
    val levelId: String,
    val isAiMode: Boolean,
    val achievedWpm: Int = 0,
    val referenceWpm: Int = 0,
    val pronunciationEligible: Boolean = false,
    val mathFirstTryByType: Map<MathProblemType, Int> = emptyMap(),
    val streakDaysAfterRun: Int = 0,
    val totalStarsAfterRun: Int = 0
)

/**
 * Per-profile achievement progress state (doc/plans/004): [progress] is the current count per
 * achievement id, [countedLevelIds] tracks the distinct levels already counted for the
 * distinct-level rules (speed reader / pronunciation master), and [unlockedIds] is the set of
 * achievement ids already unlocked.
 */
data class AchievementState(
    val progress: Map<String, Int> = emptyMap(),
    val countedLevelIds: Map<String, Set<String>> = emptyMap(),
    val unlockedIds: Set<String> = emptySet()
) {
    companion object {
        fun of(profile: ChildProfile): AchievementState = AchievementState(
            progress = profile.achievementProgress,
            countedLevelIds = profile.achievementCountedLevelIds,
            unlockedIds = profile.unlockedAchievementIds
        )
    }
}

/** Result of [AchievementEngine.evaluate]: the updated [state] plus any newly unlocked achievements. */
data class AchievementEvaluation(
    val state: AchievementState,
    val newlyUnlocked: List<Achievement>
)

/**
 * The counting rule behind each achievement id (doc/plans/004). Kept in code, not content, since
 * each rule reads different [RunFacts] fields.
 */
sealed interface AchievementRule {
    data object SpeedReader : AchievementRule
    data object PronunciationMaster : AchievementRule
    data object ClockMoneyFirstTry : AchievementRule
    data object WeeklyStreak : AchievementRule
    data object StarCollection : AchievementRule

    companion object {
        const val SPEED_READER_ID = "ach_speed_reader_5"
        const val PRONUNCIATION_MASTER_ID = "ach_pronunciation_master_10"
        const val CLOCK_MONEY_ID = "ach_math_clock_money"
        const val WEEKLY_STREAK_ID = "ach_weekly_streak_7"

        private val starAchievementIds: Set<String> by lazy {
            BalanceConfig.STAR_ACHIEVEMENT_MILESTONES.map(BalanceConfig::starAchievementId).toSet()
        }

        fun forAchievementId(id: String): AchievementRule? = when {
            id == SPEED_READER_ID -> SpeedReader
            id == PRONUNCIATION_MASTER_ID -> PronunciationMaster
            id == CLOCK_MONEY_ID -> ClockMoneyFirstTry
            id == WEEKLY_STREAK_ID -> WeeklyStreak
            id in starAchievementIds -> StarCollection
            else -> null
        }
    }
}

/**
 * Pure engine that evaluates [Achievement] progress for a single run against [AchievementState]
 * (doc/plans/004). Has no Compose/UI dependency; [ProgressionAndGiftEngine.recordLevelCompletion]
 * wires it in and turns [AchievementEvaluation.newlyUnlocked] into chest rewards.
 */
class AchievementEngine {

    fun evaluate(
        facts: RunFacts,
        state: AchievementState,
        definitions: List<Achievement>
    ): AchievementEvaluation {
        var progress = state.progress
        var countedLevelIds = state.countedLevelIds
        var unlockedIds = state.unlockedIds
        val newlyUnlocked = mutableListOf<Achievement>()

        for (def in definitions) {
            if (def.id in unlockedIds) continue

            val rule = AchievementRule.forAchievementId(def.id)
            if (rule == null) {
                println("LixoAchievements: WARN no AchievementRule for ${def.id}; skipping")
                continue
            }
            if (def.requiresAiMode && !facts.isAiMode) continue

            val prev = progress[def.id] ?: 0
            val newProgress = when (rule) {
                AchievementRule.SpeedReader -> {
                    val counts = facts.isAiMode &&
                        facts.referenceWpm > 0 &&
                        facts.achievedWpm.toFloat() >= facts.referenceWpm * BalanceConfig.READING_THREE_STAR_WPM_RATIO
                    val counted = state.countedLevelIds[def.id].orEmpty()
                    val nextCounted = if (counts && facts.levelId !in counted) counted + facts.levelId else counted
                    countedLevelIds = countedLevelIds + (def.id to nextCounted)
                    nextCounted.size.coerceAtMost(def.targetCount)
                }

                AchievementRule.PronunciationMaster -> {
                    val counts = facts.isAiMode && facts.pronunciationEligible
                    val counted = state.countedLevelIds[def.id].orEmpty()
                    val nextCounted = if (counts && facts.levelId !in counted) counted + facts.levelId else counted
                    countedLevelIds = countedLevelIds + (def.id to nextCounted)
                    nextCounted.size.coerceAtMost(def.targetCount)
                }

                AchievementRule.ClockMoneyFirstTry -> {
                    val firstTry = (facts.mathFirstTryByType[MathProblemType.CLOCK] ?: 0) +
                        (facts.mathFirstTryByType[MathProblemType.MONEY] ?: 0)
                    (prev + firstTry).coerceAtMost(def.targetCount)
                }

                AchievementRule.WeeklyStreak ->
                    facts.streakDaysAfterRun.coerceIn(0, def.targetCount)

                AchievementRule.StarCollection ->
                    facts.totalStarsAfterRun.coerceIn(0, def.targetCount)
            }

            progress = progress + (def.id to newProgress)
            if (newProgress >= def.targetCount) {
                unlockedIds = unlockedIds + def.id
                newlyUnlocked += def
            }
        }

        return AchievementEvaluation(
            state = AchievementState(progress, countedLevelIds, unlockedIds),
            newlyUnlocked = newlyUnlocked
        )
    }

    companion object {
        /**
         * Overlays a profile's achievement progress onto the static [definitions] for display
         * (the Trophies screen): [Achievement.currentCount]/[Achievement.isUnlocked] become the
         * profile's real values instead of the seed defaults.
         */
        fun withProfileProgress(definitions: List<Achievement>, profile: ChildProfile): List<Achievement> =
            definitions.map { def ->
                val isUnlocked = def.id in profile.unlockedAchievementIds
                def.copy(
                    isUnlocked = isUnlocked,
                    currentCount = if (isUnlocked) {
                        def.targetCount
                    } else {
                        (profile.achievementProgress[def.id] ?: 0).coerceIn(0, def.targetCount)
                    }
                )
            }
    }
}
