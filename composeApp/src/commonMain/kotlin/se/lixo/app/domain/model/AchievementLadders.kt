package se.lixo.app.domain.model

/**
 * Tiered challenge ladders ("chains") for achievements. See doc/plans/014-achievement-ladders.md.
 *
 * Each [AchievementAction] owns an ordered list of [LadderTier]s: targets strictly rising, rarity
 * rising (or staying) as you go up the ladder. The first 12 tiers across all chains are the
 * pre-existing handmade/star achievements kept as tiers (their ids are preserved via [LadderTier.legacyId]
 * or via [BalanceConfig.starAchievementId]), so saved progress and unlocks survive this change.
 */

/** How a chain's counter accumulates run after run. */
enum class ChainCounting { DISTINCT_LEVELS, CUMULATIVE, MAX }

/** How [AchievementEngine] accumulates the counter for this chain. */
val AchievementAction.counting: ChainCounting
    get() = when (this) {
        AchievementAction.READ_STORY,
        AchievementAction.SOLVE_MATH,
        AchievementAction.PERFECT_LEVEL,
        AchievementAction.READ_FAST,
        AchievementAction.READ_ALOUD,
        AchievementAction.EXPLORE_WORLD -> ChainCounting.DISTINCT_LEVELS
        AchievementAction.FIRST_TRY,
        AchievementAction.CLOCK_MONEY -> ChainCounting.CUMULATIVE
        AchievementAction.COLLECT_STARS,
        AchievementAction.STREAK_DAY -> ChainCounting.MAX
    }

/** Chains that only progress during AI-assisted reading runs. */
val AchievementAction.requiresAiMode: Boolean
    get() = this == AchievementAction.READ_FAST || this == AchievementAction.READ_ALOUD

/** The key used in [ChildProfile.achievementProgress]/[ChildProfile.achievementCountedLevelIds] for this chain's own counter. */
val AchievementAction.chainKey: String
    get() = "chain:$name"

/** One rung of a ladder: the counter value needed to unlock it, its reward rarity, and its legacy id (if any). */
data class LadderTier(val target: Int, val rarity: Rarity, val legacyId: String? = null)

/** All 10 challenge ladders, in [AchievementAction] order. 56 tiers total. */
val ACHIEVEMENT_LADDERS: Map<AchievementAction, List<LadderTier>> = mapOf(
    AchievementAction.READ_STORY to listOf(
        LadderTier(1, Rarity.COMMON),
        LadderTier(3, Rarity.UNCOMMON),
        LadderTier(8, Rarity.RARE),
        LadderTier(20, Rarity.EPIC),
        LadderTier(50, Rarity.LEGENDARY)
    ),
    AchievementAction.SOLVE_MATH to listOf(
        LadderTier(1, Rarity.COMMON),
        LadderTier(3, Rarity.UNCOMMON),
        LadderTier(8, Rarity.RARE),
        LadderTier(20, Rarity.EPIC),
        LadderTier(50, Rarity.LEGENDARY)
    ),
    AchievementAction.COLLECT_STARS to listOf(
        LadderTier(5, Rarity.COMMON),
        LadderTier(10, Rarity.UNCOMMON),
        LadderTier(20, Rarity.RARE, BalanceConfig.starAchievementId(20)),
        LadderTier(45, Rarity.RARE, BalanceConfig.starAchievementId(45)),
        LadderTier(75, Rarity.RARE, BalanceConfig.starAchievementId(75)),
        LadderTier(120, Rarity.EPIC, BalanceConfig.starAchievementId(120)),
        LadderTier(175, Rarity.EPIC, BalanceConfig.starAchievementId(175)),
        LadderTier(250, Rarity.EPIC, BalanceConfig.starAchievementId(250)),
        LadderTier(350, Rarity.LEGENDARY, BalanceConfig.starAchievementId(350)),
        LadderTier(480, Rarity.LEGENDARY, BalanceConfig.starAchievementId(480))
    ),
    AchievementAction.PERFECT_LEVEL to listOf(
        LadderTier(1, Rarity.COMMON),
        LadderTier(5, Rarity.UNCOMMON),
        LadderTier(15, Rarity.RARE),
        LadderTier(40, Rarity.EPIC),
        LadderTier(80, Rarity.LEGENDARY)
    ),
    AchievementAction.FIRST_TRY to listOf(
        LadderTier(10, Rarity.COMMON),
        LadderTier(30, Rarity.UNCOMMON),
        LadderTier(75, Rarity.RARE),
        LadderTier(150, Rarity.EPIC),
        LadderTier(300, Rarity.LEGENDARY)
    ),
    AchievementAction.STREAK_DAY to listOf(
        LadderTier(2, Rarity.COMMON),
        LadderTier(3, Rarity.UNCOMMON),
        LadderTier(5, Rarity.RARE),
        LadderTier(7, Rarity.LEGENDARY, "ach_weekly_streak_7"),
        LadderTier(14, Rarity.LEGENDARY),
        LadderTier(30, Rarity.LEGENDARY)
    ),
    AchievementAction.CLOCK_MONEY to listOf(
        LadderTier(3, Rarity.COMMON),
        LadderTier(8, Rarity.UNCOMMON),
        LadderTier(15, Rarity.EPIC, "ach_math_clock_money"),
        LadderTier(40, Rarity.LEGENDARY)
    ),
    AchievementAction.READ_FAST to listOf(
        LadderTier(1, Rarity.UNCOMMON),
        LadderTier(3, Rarity.RARE),
        LadderTier(5, Rarity.EPIC, "ach_speed_reader_5"),
        LadderTier(12, Rarity.LEGENDARY)
    ),
    AchievementAction.READ_ALOUD to listOf(
        LadderTier(1, Rarity.UNCOMMON),
        LadderTier(4, Rarity.RARE),
        LadderTier(10, Rarity.LEGENDARY, "ach_pronunciation_master_10"),
        LadderTier(20, Rarity.LEGENDARY)
    ),
    AchievementAction.EXPLORE_WORLD to listOf(
        LadderTier(1, Rarity.COMMON),
        LadderTier(3, Rarity.COMMON),
        LadderTier(6, Rarity.UNCOMMON),
        LadderTier(10, Rarity.UNCOMMON),
        LadderTier(15, Rarity.RARE),
        LadderTier(20, Rarity.RARE),
        LadderTier(25, Rarity.EPIC),
        LadderTier(30, Rarity.LEGENDARY)
    )
)

/** The handmade legacy achievement ids kept as the first tiers of their chains, with their original title/description/reward. */
val HANDMADE_ACHIEVEMENT_IDS: Set<String> = setOf(
    "ach_speed_reader_5", "ach_pronunciation_master_10", "ach_math_clock_money", "ach_weekly_streak_7"
)

/** The stable achievement id for a ladder tier: its legacy id if it has one, else a generated `ach_<action>_<target>` id. */
fun achievementId(action: AchievementAction, tier: LadderTier): String {
    tier.legacyId?.let { return it }
    if (action == AchievementAction.COLLECT_STARS) return BalanceConfig.starAchievementId(tier.target)
    return "ach_${action.name.lowercase()}_${tier.target}"
}

/** Reverse lookup used as the fallback for achievement definitions with no [Achievement.action] set. */
fun actionForAchievementId(id: String): AchievementAction? =
    ACHIEVEMENT_LADDERS.entries.firstOrNull { (action, tiers) -> tiers.any { achievementId(action, it) == id } }?.key
