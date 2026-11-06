package se.lixo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val publicUsername: String?,
    val ageYears: Int,
    val tutorialCompleted: Boolean,
    val tutorialSkippableByParent: Boolean,
    val appearanceJson: String,
    val unlockedAvatarsCsv: String,
    val currentLevelOrder: Int,
    val totalStars: Int,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val lastStreakDateIso: String?,
    val dailyReadingGoalMinutes: Int,
    val dailyMathGoalProblems: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subjectId: String,
    val minGrade: Int,
    val version: Int,
    val updatedAt: Long
)

@Entity(tableName = "levels")
data class LevelEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val order: Int,
    val title: String,
    val biomeNameSv: String,
    val subjectId: String,
    val difficulty: Int,
    val timeRequirementSeconds: Int,
    val referenceWpm: Int,
    val taskId: String
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val levelId: String,
    val type: String,
    val subjectId: String,
    val textContent: String?,
    val mathProblemsJson: String,
    val englishItemsJson: String,
    val rewardItemId: String?
)

@Entity(tableName = "wardrobe_items")
data class WardrobeItemEntity(
    @PrimaryKey val id: String,
    val nameSv: String,
    val descriptionSv: String,
    val slotId: String,
    val rarityId: String,
    val subjectId: String,
    val meshAssetPath: String,
    val meshNodeName: String,
    val scaleMultiplier: Float,
    val unlockStarRequirement: Int,
    val unlockAchievementId: String?,
    val isWeeklyStreakSetPiece: Boolean,
    val primaryColorHex: Long
)

/**
 * Universal Wardrobe unlocked item pool (`project.md` §2 "Universal Garderob:
 * Alla upplåsta föremål ligger i en gemensam pott och kan användas av vilken avatar som helst").
 */
@Entity(tableName = "unlocked_inventory")
data class UnlockedInventoryEntity(
    @PrimaryKey val itemId: String,
    val unlockedAtEpochMillis: Long,
    val unlockedBySource: String // e.g. "LEVEL_REWARD", "STAR_MILESTONE", "GIFT_BOX", "ACHIEVEMENT", "WEEKLY_STREAK"
)

@Entity(tableName = "level_progress", primaryKeys = ["profileId", "levelId"])
data class LevelProgressEntity(
    val profileId: String,
    val levelId: String,
    val levelOrder: Int,
    val starsEarned: Int,
    val completedInAiMode: Boolean,
    val bestTimeSeconds: Int,
    val bestWpm: Int,
    val completedTimedChallenge: Boolean,
    val timesCompleted: Int,
    val updatedAtEpochMillis: Long
)

@Entity(tableName = "daily_activity_log", primaryKeys = ["profileId", "calendarDateIso"])
data class DailyActivityEntity(
    val profileId: String,
    val calendarDateIso: String,
    val readingSecondsCompleted: Int,
    val readingTasksCompleted: Int,
    val mathProblemsCompleted: Int,
    val mathTasksCompleted: Int,
    val englishTasksCompleted: Int,
    val wordsReadCount: Int
)

/**
 * Strictly local-only log of words the child hesitated on or skipped (project.md §5 & §6).
 * NEVER synced to Firebase.
 */
@Entity(tableName = "struggled_words_log")
data class StruggledWordEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val wordNormalized: String,
    val syllables: String,
    val levelId: String,
    val encounterCount: Int,
    val lastEncounteredEpochMillis: Long
)

/**
 * Strictly local-only log of math errors & hint patterns for Parent View (project.md §5 & §6).
 * NEVER synced to Firebase.
 */
@Entity(tableName = "math_error_log")
data class MathErrorEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val problemTypeId: String,
    val problemPrompt: String,
    val wrongAttemptsCount: Int,
    val hintsUsedCount: Int,
    val timestampEpochMillis: Long
)

@Entity(tableName = "dictionary_entries")
data class DictionaryEntryEntity(
    @PrimaryKey val wordNormalized: String,
    val displayWord: String,
    val syllables: String,
    val explanationSv: String,
    val exampleSentenceSv: String,
    val languageIso: String
)

@Entity(tableName = "lexicon_meta")
data class LexiconMetaEntity(
    @PrimaryKey val singletonId: Int = 1,
    val version: Int,
    val entryCount: Int
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val appearanceJson: String,
    val totalStars: Int,
    val addedAtEpochMillis: Long
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val singletonId: Int = 1,
    val activeProfileId: String?,
    val isLocalOnlyMode: Boolean = true,
    val optionalAccountEmail: String? = null,
    val optionalCloudUserId: String? = null,
    val appLaunchCount: Int = 0,
    val crashlyticsConsentPromptShown: Boolean = false,
    val crashlyticsConsentGranted: Boolean = false,
    val updatedAtEpochMillis: Long = 0L
)
