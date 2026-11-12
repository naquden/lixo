package se.lixo.app.data.local

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.DEFAULT_AVATAR_ID
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.social.Nickname

/** doc/plans/045 §A: the format tag written to every exported profile backup file. */
const val PROFILE_BACKUP_FORMAT = "lixo-profile-backup"

/** doc/plans/045 §A: current backup schema version. Bump only with a documented migration. */
const val PROFILE_BACKUP_SCHEMA_VERSION = 1

const val PROFILE_BACKUP_MIME_TYPE = "application/json"

/**
 * Appearance DTO for [ProfileBackup] (doc/plans/045 §A). [equipped] is keyed by [EquipmentSlot]
 * NAME as a plain String (not the enum itself): kotlinx.serialization fails to decode a whole
 * `Map<EnumKey, V>` if even one key is unknown, so an older/newer build's unknown slot name would
 * otherwise fail the WHOLE backup decode instead of just dropping that one entry.
 */
@Serializable
data class BackupAppearance(
    val avatarId: String = DEFAULT_AVATAR_ID,
    val tintHex: Long? = null,
    val eyeIndex: Int = 0,
    val smileIndex: Int = 0,
    val equipped: Map<String, String> = emptyMap()
)

/** Every [ChildProfile] field except [ChildProfile.friendCardId] (doc/plans/045 §A). */
@Serializable
data class BackupProfile(
    val id: String,
    val displayName: String,
    val nickname: Nickname? = null,
    val ageYears: Int = 8,
    val tutorialCompleted: Boolean = false,
    val tutorialSkippableByParent: Boolean = false,
    val appearance: BackupAppearance = BackupAppearance(),
    val unlockedAvatarIds: List<String> = listOf(DEFAULT_AVATAR_ID),
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
    val achievementCountedLevelIds: Map<String, List<String>> = emptyMap(),
    val unlockedAchievementIds: List<String> = emptyList(),
    val pendingAchievementRewardIds: List<String> = emptyList(),
    val chestsSinceRarePlus: Int = 0,
    val chestsSinceEpicPlus: Int = 0,
    val chestsSinceLegendary: Int = 0
)

/** [LevelProgress] re-keyed to the imported profile id on import (doc/plans/045 §A). */
@Serializable
data class BackupLevelProgress(
    val levelId: String,
    val levelOrder: Int,
    val starsEarned: Int,
    val completedInAiMode: Boolean = false,
    val bestTimeSeconds: Int = 0,
    val bestWpm: Int = 0,
    val completedTimedChallenge: Boolean = false,
    val timesCompleted: Int = 1,
    val updatedAtEpochMillis: Long = 0L
)

@Serializable
data class BackupUnlockedItem(
    val itemId: String,
    val unlockedAtEpochMillis: Long = 0L,
    val unlockedBySource: String = "UNKNOWN"
)

@Serializable
data class ProfileBackup(
    val format: String = PROFILE_BACKUP_FORMAT,
    val schemaVersion: Int = PROFILE_BACKUP_SCHEMA_VERSION,
    val exportedAtEpochMillis: Long,
    val appVersionCode: Int? = null,
    val profile: BackupProfile,
    val levelProgress: List<BackupLevelProgress> = emptyList(),
    val unlockedItems: List<BackupUnlockedItem> = emptyList()
)

/** Where to put an imported profile relative to the profiles already on this device (doc/plans/045 §A). */
enum class ImportMode { REPLACE_EXISTING, AS_NEW }

/** Typed decode failures for [decodeProfileBackup] (doc/plans/045 §A). */
sealed class ProfileBackupError(msg: String) : Exception(msg) {
    class NotABackup : ProfileBackupError("Not a Lixo profile backup file")
    class UnsupportedVersion(val version: Int) : ProfileBackupError("Unsupported backup schema version $version")
    class Corrupt(cause: Throwable? = null) : ProfileBackupError(cause?.message ?: "Corrupt backup file")
}

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

fun encodeProfileBackup(b: ProfileBackup): String = backupJson.encodeToString(b)

/**
 * Decodes [text] into a [ProfileBackup], never throwing: parses to a [JsonObject] first and
 * checks `format`/`schemaVersion` BEFORE decoding, so e.g. `"{}"` fails with [ProfileBackupError.NotABackup]
 * instead of a generic missing-field decode error (doc/plans/045 pitfalls).
 */
fun decodeProfileBackup(text: String): Result<ProfileBackup> {
    val stripped = text.removePrefix("﻿")
    val element = try {
        backupJson.parseToJsonElement(stripped)
    } catch (e: Exception) {
        return Result.failure(ProfileBackupError.NotABackup())
    }
    val obj = element as? JsonObject ?: return Result.failure(ProfileBackupError.NotABackup())
    val format = (obj["format"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    if (format != PROFILE_BACKUP_FORMAT) return Result.failure(ProfileBackupError.NotABackup())
    val schemaVersion = (obj["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    if (schemaVersion == null || schemaVersion < 1) return Result.failure(ProfileBackupError.Corrupt())
    if (schemaVersion > PROFILE_BACKUP_SCHEMA_VERSION) {
        return Result.failure(ProfileBackupError.UnsupportedVersion(schemaVersion))
    }
    val backup = try {
        backupJson.decodeFromJsonElement<ProfileBackup>(obj)
    } catch (e: Exception) {
        return Result.failure(ProfileBackupError.Corrupt(e))
    }
    if (backup.profile.id.isBlank() || backup.profile.displayName.isBlank()) {
        return Result.failure(ProfileBackupError.Corrupt())
    }
    return Result.success(backup)
}

internal fun ChildProfile.toBackupProfile(): BackupProfile = BackupProfile(
    id = id,
    displayName = displayName,
    nickname = nickname,
    ageYears = ageYears,
    tutorialCompleted = tutorialCompleted,
    tutorialSkippableByParent = tutorialSkippableByParent,
    appearance = BackupAppearance(
        avatarId = appearance.avatarId,
        tintHex = appearance.tintHex,
        eyeIndex = appearance.eyeIndex,
        smileIndex = appearance.smileIndex,
        equipped = appearance.equipped.entries.associate { (slot, itemId) -> slot.name to itemId }
    ),
    unlockedAvatarIds = unlockedAvatarIds.sorted(),
    currentLevelOrder = currentLevelOrder,
    totalStars = totalStars,
    currentStreakDays = currentStreakDays,
    bestStreakDays = bestStreakDays,
    lastStreakDateIso = lastStreakDateIso,
    dailyReadingGoalMinutes = dailyReadingGoalMinutes,
    dailyMathGoalProblems = dailyMathGoalProblems,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    chestProgressHalfPoints = chestProgressHalfPoints,
    achievementProgress = achievementProgress,
    achievementCountedLevelIds = achievementCountedLevelIds.mapValues { (_, v) -> v.sorted() },
    unlockedAchievementIds = unlockedAchievementIds.sorted(),
    pendingAchievementRewardIds = pendingAchievementRewardIds.sorted(),
    chestsSinceRarePlus = chestsSinceRarePlus,
    chestsSinceEpicPlus = chestsSinceEpicPlus,
    chestsSinceLegendary = chestsSinceLegendary
)

/** friendCardId is always null: a backup never carries it (doc/plans/045 §A). */
internal fun BackupProfile.toChildProfile(): ChildProfile = ChildProfile(
    id = id,
    displayName = displayName,
    nickname = nickname,
    friendCardId = null,
    ageYears = ageYears,
    tutorialCompleted = tutorialCompleted,
    tutorialSkippableByParent = tutorialSkippableByParent,
    appearance = AvatarAppearance(
        avatarId = appearance.avatarId,
        tintHex = appearance.tintHex,
        eyeIndex = appearance.eyeIndex,
        smileIndex = appearance.smileIndex,
        equipped = appearance.equipped.mapNotNull { (slotName, itemId) ->
            EquipmentSlot.entries.firstOrNull { it.name == slotName }?.let { it to itemId }
        }.toMap()
    ),
    unlockedAvatarIds = unlockedAvatarIds.toSet(),
    currentLevelOrder = currentLevelOrder,
    totalStars = totalStars,
    currentStreakDays = currentStreakDays,
    bestStreakDays = bestStreakDays,
    lastStreakDateIso = lastStreakDateIso,
    dailyReadingGoalMinutes = dailyReadingGoalMinutes,
    dailyMathGoalProblems = dailyMathGoalProblems,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    chestProgressHalfPoints = chestProgressHalfPoints,
    achievementProgress = achievementProgress,
    achievementCountedLevelIds = achievementCountedLevelIds.mapValues { (_, v) -> v.toSet() },
    unlockedAchievementIds = unlockedAchievementIds.toSet(),
    pendingAchievementRewardIds = pendingAchievementRewardIds.toSet(),
    chestsSinceRarePlus = chestsSinceRarePlus,
    chestsSinceEpicPlus = chestsSinceEpicPlus,
    chestsSinceLegendary = chestsSinceLegendary
)

internal fun LevelProgress.toBackup(): BackupLevelProgress = BackupLevelProgress(
    levelId = levelId,
    levelOrder = levelOrder,
    starsEarned = starsEarned,
    completedInAiMode = completedInAiMode,
    bestTimeSeconds = bestTimeSeconds,
    bestWpm = bestWpm,
    completedTimedChallenge = completedTimedChallenge,
    timesCompleted = timesCompleted,
    updatedAtEpochMillis = updatedAtEpochMillis
)

internal fun BackupLevelProgress.toDomain(profileId: String): LevelProgress = LevelProgress(
    profileId = profileId,
    levelId = levelId,
    levelOrder = levelOrder,
    starsEarned = starsEarned.coerceIn(0, 3),
    completedInAiMode = completedInAiMode,
    bestTimeSeconds = bestTimeSeconds,
    bestWpm = bestWpm,
    completedTimedChallenge = completedTimedChallenge,
    timesCompleted = timesCompleted,
    updatedAtEpochMillis = updatedAtEpochMillis
)

internal fun BackupUnlockedItem.toUnlockedItemRecord(): UnlockedItemRecord =
    UnlockedItemRecord(itemId, unlockedAtEpochMillis, unlockedBySource)

internal fun UnlockedItemRecord.toBackup(): BackupUnlockedItem =
    BackupUnlockedItem(itemId, unlockedAtEpochMillis, source)

/**
 * `"lixo-<sanitized displayName>-<yyyy-MM-dd>.json"`, sanitized to
 * `[A-Za-z0-9åäöÅÄÖ_-]` and falling back to `"profil"` if that leaves nothing (doc/plans/045 §A).
 */
fun profileBackupFileName(
    displayName: String,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val sanitized = displayName.replace(Regex("[^A-Za-z0-9åäöÅÄÖ_-]"), "").ifBlank { "profil" }
    val localDate = kotlin.time.Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
    return "lixo-$sanitized-$localDate.json"
}
