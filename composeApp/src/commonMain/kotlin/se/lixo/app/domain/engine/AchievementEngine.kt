package se.lixo.app.domain.engine

import se.lixo.app.domain.model.ACHIEVEMENT_LADDERS
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AchievementAction
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChainCounting
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.actionForAchievementId
import se.lixo.app.domain.model.chainKey
import se.lixo.app.domain.model.counting
import se.lixo.app.domain.model.requiresAiMode

/**
 * Facts about a single completed run that [AchievementEngine] needs to evaluate achievement
 * progress (doc/plans/004, tiered into ladders by doc/plans/014). [streakDaysAfterRun],
 * [totalStarsAfterRun] and [earnedStarsThisRun] are always overwritten by
 * [ProgressionAndGiftEngine.recordLevelCompletion] with the post-run values once they're known;
 * callers should leave them at their defaults.
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
    val totalStarsAfterRun: Int = 0,
    val earnedStarsThisRun: Int = 0
)

/**
 * Per-profile achievement progress state (doc/plans/004): [progress] is the current count per
 * achievement id (and, since doc/plans/014, per chain key `chain:<action>`), [countedLevelIds]
 * tracks the distinct levels already counted for the distinct-level chains, and [unlockedIds] is
 * the set of achievement ids already unlocked.
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
 * Pure engine that evaluates [Achievement] progress for a single run against [AchievementState]
 * (doc/plans/004, tiered ladders by doc/plans/014). Has no Compose/UI dependency;
 * [ProgressionAndGiftEngine.recordLevelCompletion] wires it in and turns
 * [AchievementEvaluation.newlyUnlocked] into chest rewards.
 */
class AchievementEngine {

    fun evaluate(
        facts: RunFacts,
        state: AchievementState,
        definitions: List<Achievement>
    ): AchievementEvaluation {
        var progress = state.progress
        var countedLevelIds = state.countedLevelIds
        val unlockedIdsAtStart = state.unlockedIds

        // (a) One counter per action present in the definitions, always computed (even if every
        // tier of that chain is already unlocked), so chain counters never fall behind.
        val actions = definitions.mapNotNull { it.action ?: actionForAchievementId(it.id) }.distinct()
        val counters = mutableMapOf<AchievementAction, Int>()
        for (action in actions) {
            val chainKey = action.chainKey
            val currentState = AchievementState(progress, countedLevelIds, unlockedIdsAtStart)
            when (action.counting) {
                ChainCounting.DISTINCT_LEVELS -> {
                    val prevIds = chainLevelIds(action, currentState)
                    val qualifies = when (action) {
                        AchievementAction.READ_STORY -> facts.subject == Subject.SWEDISH || facts.subject == Subject.ENGLISH
                        AchievementAction.SOLVE_MATH -> facts.subject == Subject.MATH
                        AchievementAction.PERFECT_LEVEL -> facts.earnedStarsThisRun >= 3
                        AchievementAction.READ_FAST -> facts.isAiMode && facts.referenceWpm > 0 &&
                            facts.achievedWpm.toFloat() >= facts.referenceWpm * BalanceConfig.READING_THREE_STAR_WPM_RATIO
                        AchievementAction.READ_ALOUD -> facts.isAiMode && facts.pronunciationEligible
                        AchievementAction.EXPLORE_WORLD -> facts.subject == Subject.GEOGRAPHY
                        else -> false
                    }
                    val nextIds = if (qualifies && facts.levelId !in prevIds) prevIds + facts.levelId else prevIds
                    countedLevelIds = countedLevelIds + (chainKey to nextIds)
                    counters[action] = nextIds.size
                }

                ChainCounting.CUMULATIVE -> {
                    val prevCount = chainCount(action, currentState)
                    val increment = when (action) {
                        AchievementAction.FIRST_TRY -> facts.mathFirstTryByType.values.sum()
                        AchievementAction.CLOCK_MONEY ->
                            (facts.mathFirstTryByType[MathProblemType.CLOCK] ?: 0) +
                                (facts.mathFirstTryByType[MathProblemType.MONEY] ?: 0)
                        else -> 0
                    }
                    val next = prevCount + increment
                    progress = progress + (chainKey to next)
                    counters[action] = next
                }

                ChainCounting.MAX -> {
                    val prevCount = chainCount(action, currentState)
                    val runValue = when (action) {
                        AchievementAction.COLLECT_STARS -> facts.totalStarsAfterRun
                        AchievementAction.STREAK_DAY -> facts.streakDaysAfterRun
                        else -> prevCount
                    }
                    val next = maxOf(prevCount, runValue)
                    progress = progress + (chainKey to next)
                    counters[action] = next
                }
            }
        }

        // (b) Unlock individual tiers whose chain counter has reached their target.
        var unlockedIds = unlockedIdsAtStart
        val newlyUnlocked = mutableListOf<Achievement>()
        for (def in definitions) {
            if (def.id in unlockedIds) continue
            val action = def.action ?: actionForAchievementId(def.id)
            if (action == null) {
                println("LixoAchievements: WARN no action for ${def.id}; skipping")
                continue
            }
            if (def.requiresAiMode && !facts.isAiMode) continue

            val counter = counters[action] ?: 0
            progress = progress + (def.id to counter.coerceAtMost(def.targetCount))
            if (counter >= def.targetCount) {
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
        /** The distinct level ids counted so far for a DISTINCT_LEVELS chain, carrying over from its legacy tiers. */
        internal fun chainLevelIds(action: AchievementAction, state: AchievementState): Set<String> {
            state.countedLevelIds[action.chainKey]?.let { return it }
            val legacyTiers = ACHIEVEMENT_LADDERS.getValue(action).filter { it.legacyId != null }
            return legacyTiers.fold(emptySet()) { acc, tier ->
                acc + (state.countedLevelIds[tier.legacyId] ?: emptySet())
            }
        }

        /** The current chain counter value, carrying over from legacy tiers when the chain key hasn't been written yet. */
        internal fun chainCount(action: AchievementAction, state: AchievementState): Int {
            if (action.counting == ChainCounting.DISTINCT_LEVELS) return chainLevelIds(action, state).size
            state.progress[action.chainKey]?.let { return it }
            val legacyTiers = ACHIEVEMENT_LADDERS.getValue(action).filter { it.legacyId != null }
            if (legacyTiers.isEmpty()) return 0
            return legacyTiers.maxOf { tier ->
                val legacyId = tier.legacyId!!
                maxOf(state.progress[legacyId] ?: 0, if (legacyId in state.unlockedIds) tier.target else 0)
            }
        }

        /**
         * Overlays a profile's achievement progress onto the static [definitions] for display
         * (the Trophies screen): [Achievement.currentCount]/[Achievement.isUnlocked] become the
         * profile's real values instead of the seed defaults. For a locked tier whose chain has an
         * action, this also carries over legacy progress and the profile's live totals
         * ([ChildProfile.totalStars]/[ChildProfile.currentStreakDays]) so a fresh ladder shows
         * correct progress before the next run.
         */
        fun withProfileProgress(definitions: List<Achievement>, profile: ChildProfile): List<Achievement> {
            val state = AchievementState.of(profile)
            return definitions.map { def ->
                val isUnlocked = def.id in profile.unlockedAchievementIds
                val currentCount = if (isUnlocked) {
                    def.targetCount
                } else {
                    val action = def.action ?: actionForAchievementId(def.id)
                    val base = profile.achievementProgress[def.id] ?: 0
                    if (action != null) {
                        val extra = when (action) {
                            AchievementAction.COLLECT_STARS -> profile.totalStars
                            AchievementAction.STREAK_DAY -> profile.currentStreakDays
                            else -> 0
                        }
                        maxOf(base, chainCount(action, state), extra).coerceIn(0, def.targetCount)
                    } else {
                        base.coerceIn(0, def.targetCount)
                    }
                }
                def.copy(isUnlocked = isUnlocked, currentCount = currentCount)
            }
        }

        /** The lowest-tier achievement in a chain that isn't unlocked yet, or the highest tier if the whole chain is done. */
        fun currentTier(chainTiers: List<Achievement>): Achievement =
            chainTiers.filter { !it.isUnlocked }.minByOrNull { it.tier }
                ?: chainTiers.maxBy { it.tier }

        /** One [currentTier] per chain, grouped by [Achievement.action] in enum order. */
        fun currentTiers(definitions: List<Achievement>, profile: ChildProfile): List<Achievement> =
            withProfileProgress(definitions, profile)
                .filter { it.action != null }
                .groupBy { it.action!! }
                .entries.sortedBy { it.key.ordinal }
                .map { currentTier(it.value) }

        /**
         * The next tier of [def]'s chain after it, excluding tiers already in [unlockedIds] (so a
         * tier unlocked in the same run is never offered as "Ny utmaning!").
         */
        fun nextTier(def: Achievement, definitions: List<Achievement>, unlockedIds: Set<String>): Achievement? {
            val action = def.action ?: return null
            return definitions
                .filter { it.action == action && it.tier > def.tier && it.id !in unlockedIds }
                .minByOrNull { it.tier }
        }
    }
}
