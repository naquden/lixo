package se.lixo.app.domain.model

import kotlinx.serialization.Serializable
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.DEFAULT_AVATAR_ID
import se.lixo.app.domain.content.NicknameWords
import se.lixo.app.domain.social.Nickname

/**
 * doc/plans/030 §1 / review L2: email backup is out of scope for 030 (every child now identifies
 * to Firestore via Firebase Anonymous Auth, `LixoRepository.ensureCloudIdentity`), and completing
 * an email-link sign-in REPLACES that anonymous user, which would orphan every friend card. Both
 * the parent-screen email/account UI (`ParentGateAndDashboardScreen`) and the email-link deep
 * link completion path (`LixoApp`'s `LaunchedEffect(incomingEmailLink)`) are gated behind this
 * single flag so they can't drift out of sync; flip it back on only once email backup ships as
 * its own feature with a proper "link, don't replace" merge story.
 */
const val EMAIL_BACKUP_FEATURE_ENABLED = false

/**
 * 9 MVP Equipment slots as specified in project.md §2 ("Utrustningsplatser (9 slots)").
 * Face, aura/particles, and skin are deferred to post-MVP.
 */
@Serializable
enum class EquipmentSlot(
    val id: String,
    val swedishNameKey: String,
    val socketName: String
) {
    HAT("hat", "slot_hat", "socket_hat"),
    SHOULDERS_BACK("shoulders_back", "slot_shoulders_back", "socket_shoulders_back"),
    ARMS_GLOVES("arms_gloves", "slot_arms_gloves", "socket_arms_gloves"),
    ARMOR_SHIRT("armor_shirt", "slot_armor_shirt", "socket_armor_shirt"),
    PANTS("pants", "slot_pants", "socket_pants"),
    SHOES("shoes", "slot_shoes", "socket_shoes"),
    HAND_RIGHT("hand_right", "slot_hand_right", "socket_hand_right"),
    HAND_LEFT("hand_left", "slot_hand_left", "socket_hand_left"),
    TAIL("tail", "slot_tail", "socket_tail")
}

/** Whether this slot is one of the two hand slots that share one item pool (doc/plans/032). */
val EquipmentSlot.isHand: Boolean get() = this == EquipmentSlot.HAND_LEFT || this == EquipmentSlot.HAND_RIGHT

/** Whether an item authored for [itemSlot] may be equipped in this slot: same slot, or both hand slots (shared hand pool, doc/plans/032). */
fun EquipmentSlot.accepts(itemSlot: EquipmentSlot): Boolean = this == itemSlot || (isHand && itemSlot.isHand)

/**
 * Fortnite-inspired 2D background color scale as specified in project.md §2 ("Sällsynthet").
 */
@Serializable
enum class Rarity(
    val id: String,
    val swedishNameKey: String,
    val hexColorPrimary: Long,
    val hexColorAccent: Long
) {
    // Target percent chance per roll that this rarity is drawn, rarity-first, now lives in the
    // content pack's `dropRules.rarityWeights` (doc/content/content-pack.md § Drop rules).
    COMMON("common", "rarity_common", 0xFF9E9E9E, 0xFF757575),
    UNCOMMON("uncommon", "rarity_uncommon", 0xFF2ECC71, 0xFF27AE60),
    RARE("rare", "rarity_rare", 0xFF3498DB, 0xFF2980B9),
    EPIC("epic", "rarity_epic", 0xFF9B59B6, 0xFF8E44AD),
    LEGENDARY("legendary", "rarity_legendary", 0xFFF39C12, 0xFFE67E22)
}

/**
 * MVP Subjects as specified in project.md §1 & §2.
 * Geography and Art/Creation are deferred.
 */
@Serializable
enum class Subject(
    val id: String,
    val swedishNameKey: String,
    val primaryRewardSlots: List<EquipmentSlot>
) {
    SWEDISH(
        id = "swedish",
        swedishNameKey = "subject_swedish",
        primaryRewardSlots = listOf(
            EquipmentSlot.ARMOR_SHIRT,
            EquipmentSlot.SHOULDERS_BACK,
            EquipmentSlot.PANTS,
            EquipmentSlot.SHOES,
            EquipmentSlot.TAIL
        )
    ),
    MATH(
        id = "math",
        swedishNameKey = "subject_math",
        primaryRewardSlots = listOf(
            EquipmentSlot.HAND_RIGHT,
            EquipmentSlot.HAND_LEFT,
            EquipmentSlot.ARMS_GLOVES
        )
    ),
    ENGLISH(
        id = "english",
        swedishNameKey = "subject_english",
        primaryRewardSlots = listOf(
            EquipmentSlot.HAT
        )
    )
}

/**
 * Reading mode locked at start of a reading session (project.md §2 "Manuellt läsläge").
 */
@Serializable
enum class ReadingMode {
    AI_LISTENING,
    MANUAL
}

/**
 * Math problem categories required by project.md §2 ("Matematik-stöttning").
 */
@Serializable
enum class MathProblemType(val id: String, val swedishNameKey: String) {
    ADDITION("addition", "math_type_addition"),
    SUBTRACTION("subtraction", "math_type_subtraction"),
    TEN_CROSSING("ten_crossing", "math_type_ten_crossing"),
    MULTIPLICATION("multiplication", "math_type_multiplication"),
    DIVISION("division", "math_type_division"),
    CLOCK("clock", "math_type_clock"),
    MONEY("money", "math_type_money"),
    WORD_PROBLEM("word_problem", "math_type_word_problem")
}

/**
 * Local child profile. Supports sibling profiles on the same device (project.md §5).
 */
@Serializable
data class ChildProfile(
    val id: String,
    val displayName: String,
    /** The generated friend nickname (doc/plans/030 §3), assigned when [friendCardId] is first created. */
    val nickname: Nickname? = null,
    /** This profile's `friend_cards/{cardId}` id (doc/plans/030 §4/§5), or null until the first friend-related action. */
    val friendCardId: String? = null,
    val ageYears: Int = 8,
    val tutorialCompleted: Boolean = false,
    val tutorialSkippableByParent: Boolean = false,
    val appearance: AvatarAppearance = AvatarAppearance(),
    val unlockedAvatarIds: Set<String> = setOf(DEFAULT_AVATAR_ID),
    val currentLevelOrder: Int = 1,
    val totalStars: Int = 0,
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    val lastStreakDateIso: String? = null,
    val dailyReadingGoalMinutes: Int = 15,
    val dailyMathGoalProblems: Int = 10,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val chestProgressHalfPoints: Int = 0,
    val achievementProgress: Map<String, Int> = emptyMap(),
    val achievementCountedLevelIds: Map<String, Set<String>> = emptyMap(),
    val unlockedAchievementIds: Set<String> = emptySet(),
    val pendingAchievementRewardIds: Set<String> = emptySet(),
    /**
     * Flitkista chests opened since the last chest at or above RARE / EPIC / LEGENDARY
     * respectively, used for the pity floor in
     * [se.lixo.app.domain.engine.ProgressionAndGiftEngine.rollChest].
     */
    val chestsSinceRarePlus: Int = 0,
    val chestsSinceEpicPlus: Int = 0,
    val chestsSinceLegendary: Int = 0
)

/**
 * Visual decoration pattern drawn on top of an item's base shape, used to telegraph rarity
 * (project.md §2 "Epic/Legendary via prestationer").
 */
@Serializable
enum class ItemPattern { NONE, STRIPES, DOTS, STARS, ZIGZAG, CHECKER }

/**
 * Trim/decoration applied to an item, escalating with rarity: COMMON NONE, UNCOMMON TRIM,
 * RARE GEM, EPIC GLOW, LEGENDARY SPARKLE. LEGENDARY additionally always renders the GLOW halo
 * behind the item too, whatever its own ornament value (see the layer renderer's drawsGlowHalo
 * in se.lixo.app.ui.components.avatar).
 */
@Serializable
enum class ItemOrnament { NONE, TRIM, GEM, GLOW, SPARKLE }

/**
 * Source of a single unlocked reward within a run (`project.md` §2 "Belöningskurva"). The
 * declaration order below is also the celebration order within a run: progression (LEVEL or
 * STARS, which share one progression slot per run), then FLITKISTA, GIFT, WEEKLY_STREAK and
 * finally ACHIEVEMENT.
 */
enum class RewardSource { LEVEL, STARS, FLITKISTA, GIFT, WEEKLY_STREAK, ACHIEVEMENT }

/**
 * A single reward moment produced by [se.lixo.app.domain.engine.ProgressionAndGiftEngine.recordLevelCompletion]:
 * the unlocked item and which [RewardSource] produced it. Not persisted. [labelDetailSv] carries an
 * extra celebration detail for the reward's label, e.g. the achievement's `titleSv` for ACHIEVEMENT
 * rewards ("Prestation: Blixtläsaren").
 */
data class UnlockedReward(val item: ContentItem, val source: RewardSource, val labelDetailSv: String? = null)

/**
 * Firestore / local Course model (`courses/{courseId}`).
 */
@Serializable
data class Course(
    val id: String,
    val title: String,
    val subject: Subject,
    val minGrade: Int,
    val version: Int,
    val updatedAt: Long
)

/**
 * Firestore / local Campaign Level (`levels/{levelId}`).
 * Campaign of 160 levels, orders 1..160 (project.md §2 "Linjär kampanj"). Math is independent
 * from Swedish/English: Swedish (40 levels) and English (20 levels) are interleaved across
 * orders 1..100, while the 100 math levels sit at the even, non-multiple-of-10 orders within
 * 1..100 (n1..n40) plus orders 101..160 (n41..n100). See
 * [se.lixo.app.domain.content.MathCurriculum]. Unlocking is per subject (previous level of the
 * same subject), independent of other subjects' positions.
 */
@Serializable
data class CampaignLevel(
    val id: String,
    val courseId: String,
    val order: Int, // 1..160
    val title: String,
    val biomeNameSv: String,
    val subject: Subject,
    val difficulty: Int, // 1..6 (roughly corresponding to school years)
    val timeRequirementSeconds: Int,
    val referenceWpm: Int,
    val taskId: String
)

/**
 * Static Math Problem definition (project.md §3 "Mattetal är statiska").
 */
@Serializable
data class MathProblem(
    val id: String,
    val type: MathProblemType,
    val promptSv: String,
    val correctAnswer: String,
    val choices: List<String> = emptyList(),
    val hintStep1Sv: String,
    val hintStep2Sv: String,
    val visualClockHour: Int? = null,
    val visualClockMinute: Int? = null,
    val visualMoneyBreakdownKr: List<Int> = emptyList()
)

/**
 * English vocabulary / simple reading item for English MVP subject.
 */
@Serializable
data class EnglishGlosaItem(
    val id: String,
    val englishWord: String,
    val swedishTranslation: String,
    val simpleSentenceEn: String,
    val simpleSentenceSv: String
)

/**
 * Firestore / local Homework Task (`tasks/{taskId}`).
 */
@Serializable
data class HomeworkTask(
    val id: String,
    val levelId: String,
    val type: TaskType,
    val subject: Subject,
    val textContent: String? = null,
    val mathProblems: List<MathProblem> = emptyList(),
    val englishItems: List<EnglishGlosaItem> = emptyList(),
    /** Assigned from content.dropRules.levelRewards; curriculum JSON/Firestore must not carry it. Used as the onboarding-track item, see doc/plans/002. */
    val rewardItemId: String? = null
)

@Serializable
enum class TaskType {
    READING,
    MATH,
    ENGLISH_VOCAB
}

/**
 * Pre-generated offline dictionary entry with syllable split (`rid-dar-vagn`)
 * and child-friendly explanation (project.md §2 "Ordförklaringar" & "Stavelsedelning").
 */
@Serializable
data class DictionaryEntry(
    val wordNormalized: String,
    val displayWord: String,
    val syllables: String, // e.g. "rid-dar-vagn"
    val explanationSv: String,
    val exampleSentenceSv: String,
    val languageIso: String = "sv-SE"
)

/**
 * Per-level progress for a child profile.
 */
@Serializable
data class LevelProgress(
    val profileId: String,
    val levelId: String,
    val levelOrder: Int,
    val starsEarned: Int, // 0..3
    val completedInAiMode: Boolean,
    val bestTimeSeconds: Int,
    val bestWpm: Int,
    val completedTimedChallenge: Boolean = false,
    val timesCompleted: Int = 1,
    val updatedAtEpochMillis: Long = 0L
)

/**
 * Local-only log of words the child hesitated on or skipped (project.md §2 & §5).
 * NEVER uploaded to cloud.
 */
@Serializable
data class StruggledWordLog(
    val id: String,
    val profileId: String,
    val wordNormalized: String,
    val syllables: String,
    val levelId: String,
    val encounterCount: Int,
    val lastEncounteredEpochMillis: Long
)

/**
 * Local-only log of math errors and hint usage by category for Parent View (project.md §5).
 * NEVER uploaded to cloud.
 */
@Serializable
data class MathErrorLog(
    val id: String,
    val profileId: String,
    val problemType: MathProblemType,
    val problemPrompt: String,
    val wrongAttemptsCount: Int,
    val hintsUsedCount: Int,
    val timestampEpochMillis: Long
)

/**
 * Daily activity summary per child profile for calendar-day streak (00:00-24:00) and Parent View.
 */
@Serializable
data class DailyActivityRecord(
    val profileId: String,
    val calendarDateIso: String, // YYYY-MM-DD local time
    val readingSecondsCompleted: Int = 0,
    val readingTasksCompleted: Int = 0,
    val mathProblemsCompleted: Int = 0,
    val mathTasksCompleted: Int = 0,
    val englishTasksCompleted: Int = 0,
    val wordsReadCount: Int = 0
)

/**
 * One action a challenge chain (ladder) tracks. See plan doc/plans/014-achievement-ladders.md.
 * Order matters: it is the seed order, the pending-reward queue order and the challenge-card tie-break order.
 */
@Serializable
enum class AchievementAction {
    READ_STORY, SOLVE_MATH, COLLECT_STARS, PERFECT_LEVEL, FIRST_TRY, STREAK_DAY, CLOCK_MONEY, READ_FAST, READ_ALOUD
}

/**
 * Achievement for unlocking Epic and Legendary items (project.md §2 "Epic/Legendary via prestationer").
 * As of plan 014 achievements form tiered challenge ladders ("chains"); [action] identifies the chain
 * and [tier] is the 1-based index of this achievement within its chain (see AchievementLadders.kt).
 */
@Serializable
data class Achievement(
    val id: String,
    val titleSv: String,
    val descriptionSv: String,
    val rarity: Rarity,
    val requiresAiMode: Boolean,
    val targetCount: Int,
    val currentCount: Int = 0,
    val isUnlocked: Boolean = false,
    val rewardItemId: String,
    val action: AchievementAction? = null,
    val tier: Int = 0,
    val shortSv: String = "",
    val spokenSv: String = ""
)

/**
 * Friend profile visible in the friend village ("Kompisbyn", doc/plans/030), added in person via
 * QR code or typed invite code. Contains only the friend's generated [nick] and current avatar
 * appearance — zero PII, zero stars, zero chat. [label] is the private name *this* device's
 * child gave the friend; it is Room-only and never leaves the device (doc/plans/030 §5).
 */
@Serializable
data class FriendProfile(
    val userId: String,
    val cardId: String,
    val nick: Nickname,
    val appearance: AvatarAppearance,
    val label: String? = null,
    val addedAtEpochMillis: Long = 0L,
    /** `null` = not yet celebrated with the "Ny kompis!" reveal on this device (doc/plans/030 §7). */
    val revealedAtEpochMillis: Long? = null
) {
    /** The private [label] if the child set one, else the generated nickname rendered against [words]. */
    fun shownName(words: NicknameWords): String = label ?: nick.render(words)
}

/**
 * Reasons [se.lixo.app.data.repository.LixoRepository.addFriendByCode] can fail (doc/plans/030 §4).
 */
enum class AddFriendError { NOT_FOUND, ALREADY_FRIEND, NETWORK, EXPIRED, SELF, FULL, INVALID_QR }

class AddFriendException(val reason: AddFriendError) : Exception(reason.name)

