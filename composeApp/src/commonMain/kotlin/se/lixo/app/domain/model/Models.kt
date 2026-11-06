package se.lixo.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 6 MVP Avatar types as specified in project.md §2 ("Avatartyper (MVP, 6 st)").
 */
@Serializable
enum class AvatarType(
    val id: String,
    val swedishNameKey: String,
    val isHumanoid: Boolean,
    val defaultUnlocked: Boolean,
    val unlockStarsRequired: Int
) {
    CAT("cat", "avatar_cat", isHumanoid = false, defaultUnlocked = true, unlockStarsRequired = 0),
    DOG("dog", "avatar_dog", isHumanoid = false, defaultUnlocked = false, unlockStarsRequired = 12),
    KNIGHT("knight", "avatar_knight", isHumanoid = true, defaultUnlocked = false, unlockStarsRequired = 30),
    PRINCESS("princess", "avatar_princess", isHumanoid = true, defaultUnlocked = false, unlockStarsRequired = 55),
    WIZARD("wizard", "avatar_wizard", isHumanoid = true, defaultUnlocked = false, unlockStarsRequired = 90),
    WITCH("witch", "avatar_witch", isHumanoid = true, defaultUnlocked = false, unlockStarsRequired = 135)
}

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

/**
 * Fortnite-inspired 2D background color scale as specified in project.md §2 ("Sällsynthet").
 */
@Serializable
enum class Rarity(
    val id: String,
    val swedishNameKey: String,
    val hexColorPrimary: Long,
    val hexColorAccent: Long,
    val giftDropWeight: Int
) {
    COMMON("common", "rarity_common", 0xFF9E9E9E, 0xFF757575, 50),
    UNCOMMON("uncommon", "rarity_uncommon", 0xFF2ECC71, 0xFF27AE60, 30),
    RARE("rare", "rarity_rare", 0xFF3498DB, 0xFF2980B9, 15),
    EPIC("epic", "rarity_epic", 0xFF9B59B6, 0xFF8E44AD, 4),
    LEGENDARY("legendary", "rarity_legendary", 0xFFF39C12, 0xFFE67E22, 1)
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
 * Customizable base traits for an avatar (project.md §2 "Karaktärsbas & Skapande").
 */
@Serializable
data class AvatarAppearance(
    val avatarType: AvatarType = AvatarType.CAT,
    val primaryTintHex: Long = 0xFFF5A623,
    val eyeExpressionIndex: Int = 0,
    val smileExpressionIndex: Int = 0,
    val equippedItems: Map<EquipmentSlot, String> = emptyMap() // Slot -> WardrobeItem.id
)

/**
 * Local child profile. Supports sibling profiles on the same device (project.md §5).
 */
@Serializable
data class ChildProfile(
    val id: String,
    val displayName: String,
    val publicUsername: String? = null,
    val ageYears: Int = 8,
    val tutorialCompleted: Boolean = false,
    val tutorialSkippableByParent: Boolean = false,
    val appearance: AvatarAppearance = AvatarAppearance(),
    val unlockedAvatars: Set<AvatarType> = setOf(AvatarType.CAT),
    val currentLevelOrder: Int = 1,
    val totalStars: Int = 0,
    val currentStreakDays: Int = 0,
    val bestStreakDays: Int = 0,
    val lastStreakDateIso: String? = null,
    val dailyReadingGoalMinutes: Int = 15,
    val dailyMathGoalProblems: Int = 10,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val chestProgressHalfPoints: Int = 0
)

/**
 * Universal wardrobe item (project.md §2 "Universal Garderob").
 * Usable by any avatar; no avatar-locked items.
 */
@Serializable
data class WardrobeItem(
    val id: String,
    val nameSv: String,
    val descriptionSv: String,
    val slot: EquipmentSlot,
    val rarity: Rarity,
    val subjectTheme: Subject,
    val meshAssetPath: String = "3d_sample.glb",
    val meshNodeName: String = "mesh_default",
    val scaleMultiplier: Float = 1.0f,
    val unlockStarRequirement: Int = 0,
    val unlockAchievementId: String? = null,
    val isWeeklyStreakSetPiece: Boolean = false,
    val primaryColorHex: Long = 0xFF3498DB
)

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
 * Achievement for unlocking Epic and Legendary items (project.md §2 "Epic/Legendary via prestationer").
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
    val rewardItemId: String
)

/**
 * Friend profile visible in the Friend Gallery via QR code or username (project.md §2 "Kompisgalleri").
 * Contains only anonymous username and avatar appearance — zero PII or chat.
 */
@Serializable
data class FriendProfile(
    val userId: String,
    val username: String,
    val appearance: AvatarAppearance,
    val totalStars: Int = 0,
    val addedAtEpochMillis: Long = 0L
)

/**
 * Reasons [LixoRepository.addFriendByQuery] can fail (project.md §2 "Kompisgalleri").
 */
enum class AddFriendError { NOT_FOUND, ALREADY_FRIEND, NETWORK }

class AddFriendException(val reason: AddFriendError) : Exception(reason.name)

/**
 * Complete bundled seed structure for `seed_campaign_content.json`.
 */
@Serializable
data class SeedContentBundle(
    val version: Int,
    val generatedAtIso: String,
    val courses: List<Course>,
    val levels: List<CampaignLevel>,
    val tasks: List<HomeworkTask>,
    val wardrobeItems: List<WardrobeItem>,
    val dictionary: List<DictionaryEntry>,
    val achievements: List<Achievement>
)
