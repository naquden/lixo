package se.lixo.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** JSON of [se.lixo.app.domain.social.Nickname] (030), or null until a friend card exists. */
    @ColumnInfo(defaultValue = "NULL") val nicknameJson: String? = null,
    /** This profile's `friend_cards/{cardId}` id (030), or null until a friend card exists. */
    @ColumnInfo(defaultValue = "NULL") val friendCardId: String? = null,
    val ageYears: Int,
    val tutorialCompleted: Boolean,
    val tutorialSkippableByParent: Boolean,
    /** JSON of [se.lixo.app.domain.content.AvatarAppearance] (022; was domain.model.AvatarAppearance). */
    val appearanceJson: String,
    val unlockedAvatarIdsCsv: String,
    val currentLevelOrder: Int,
    val totalStars: Int,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val lastStreakDateIso: String?,
    val dailyReadingGoalMinutes: Int,
    val dailyMathGoalProblems: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val chestProgressHalfPoints: Int = 0,
    @ColumnInfo(defaultValue = "'{}'") val achievementProgressJson: String = "{}",
    @ColumnInfo(defaultValue = "'{}'") val achievementCountedLevelIdsJson: String = "{}",
    @ColumnInfo(defaultValue = "'[]'") val unlockedAchievementIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'") val pendingAchievementRewardIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val chestsSinceRarePlus: Int = 0,
    @ColumnInfo(defaultValue = "0") val chestsSinceEpicPlus: Int = 0,
    @ColumnInfo(defaultValue = "0") val chestsSinceLegendary: Int = 0
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

/**
 * A friend as seen by one local child profile (doc/plans/030 §5): [friendCardId]/[nickJson]/
 * [appearanceJson] mirror the friend's cloud `friend_cards/{cardId}` doc (kept fresh by
 * [se.lixo.app.data.repository.LixoRepository.observeFriendCards]); [label] is the private name
 * *this* device's child gave the friend and is Room-only, never sent to the cloud or logged.
 * [revealedAtEpochMillis] is null until the "Ny kompis!" celebration has played on this device.
 */
@Entity(tableName = "friends", primaryKeys = ["profileId", "friendUid"])
data class FriendEntity(
    val profileId: String,
    val friendUid: String,
    val friendCardId: String,
    val nickJson: String,
    val appearanceJson: String,
    val label: String? = null,
    val addedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "NULL") val revealedAtEpochMillis: Long? = null
)

/**
 * A pending "please also delete this friend on the cloud" tombstone (M2 in
 * temp/verify/030_review.md): [se.lixo.app.data.repository.LixoRepository.removeFriend] deletes
 * the [FriendEntity] row from Room immediately (so the UI updates right away) but the matching
 * [se.lixo.app.data.remote.FriendCloud.removeFriend] cloud write is fire-and-forget -- if that
 * fails (offline, app killed mid-flight), the next [se.lixo.app.data.repository.LixoRepository.refreshFriends]
 * would otherwise re-discover the still-live reader doc on the cloud and re-insert the friend
 * (replaying the "Ny kompis!" reveal). A row here survives until the cloud delete actually
 * succeeds: [refreshFriends] skips any uid with a tombstone when re-adding readers, and retries
 * the cloud delete for every tombstone still present.
 */
@Entity(tableName = "friend_removal_tombstones", primaryKeys = ["profileId", "friendUid"])
data class FriendRemovalTombstoneEntity(
    val profileId: String,
    val friendUid: String,
    val myCardId: String,
    val friendCardId: String,
    val createdAtEpochMillis: Long
)

/**
 * Content-pack cloud sync (024) metadata: the one cached pack's schema/version/sha256/chunk
 * count. Kept as a separate singleton row (`id = 0`) rather than folded into `app_settings` so
 * [ContentPackDao.replace] can delete+reinsert the chunk rows and this row in one transaction.
 */
@Entity(tableName = "content_pack_meta")
data class ContentPackMetaEntity(
    @PrimaryKey val id: Int = 0,
    val schema: Int,
    val version: Int,
    val sha256: String,
    val chunkCount: Int
)

/**
 * One ~900 KB slice of a cached content pack's JSON (024), keyed by (version, idx) so Room's
 * 2 MB CursorWindow per row is never at risk and an old version's rows are cleanly replaced.
 */
@Entity(tableName = "content_pack_chunks", primaryKeys = ["version", "idx"])
data class ContentPackChunkEntity(
    val version: Int,
    val idx: Int,
    val data: String
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val singletonId: Int = 1,
    val activeProfileId: String?,
    val isLocalOnlyMode: Boolean = true,
    val optionalAccountEmail: String? = null,
    /**
     * The current Firebase Auth uid (030): anonymous by default, or the uid after an optional
     * email-account upgrade. Renamed from `optionalCloudUserId` in place (Room schema stays v1,
     * destructive fallback; see [LixoDatabase]'s KDoc).
     */
    val cloudUid: String? = null,
    val appLaunchCount: Int = 0,
    val crashlyticsConsentPromptShown: Boolean = false,
    val crashlyticsConsentGranted: Boolean = false,
    val updatedAtEpochMillis: Long = 0L
)
