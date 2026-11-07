package se.lixo.app.data.local

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress

/**
 * Encodes defaults so a later change to [AvatarAppearance]'s defaults (e.g. `primaryTintHex`)
 * never silently re-skins a persisted avatar, and ignores unknown keys so an older row from a
 * previous app version never fails to decode.
 */
private val profileJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Runs [block], falling back to [default] and logging the Throwable if it fails, so a corrupt
 * JSON/CSV field on [field] in profile [id] never blocks the rest of the profile from decoding.
 */
private inline fun <T> ProfileEntity.decodeOr(field: String, default: T, block: () -> T): T =
    runCatching(block).onFailure { println("LixoProfiles: WARN profile $id $field decode failed: $it") }.getOrDefault(default)

internal fun ChildProfile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    displayName = displayName,
    publicUsername = publicUsername,
    ageYears = ageYears,
    tutorialCompleted = tutorialCompleted,
    tutorialSkippableByParent = tutorialSkippableByParent,
    appearanceJson = profileJson.encodeToString(appearance),
    unlockedAvatarsCsv = unlockedAvatars.joinToString(",") { it.name },
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
    achievementProgressJson = profileJson.encodeToString(achievementProgress),
    achievementCountedLevelIdsJson = profileJson.encodeToString(achievementCountedLevelIds),
    unlockedAchievementIdsJson = profileJson.encodeToString(unlockedAchievementIds),
    pendingAchievementRewardIdsJson = profileJson.encodeToString(pendingAchievementRewardIds)
)

internal fun ProfileEntity.toDomain(): ChildProfile = ChildProfile(
    id = id,
    displayName = displayName,
    publicUsername = publicUsername,
    ageYears = ageYears,
    tutorialCompleted = tutorialCompleted,
    tutorialSkippableByParent = tutorialSkippableByParent,
    appearance = decodeOr("appearance", AvatarAppearance()) {
        profileJson.decodeFromString<AvatarAppearance>(appearanceJson)
    },
    unlockedAvatars = decodeOr("unlockedAvatars", emptySet()) {
        unlockedAvatarsCsv.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name ->
                runCatching { AvatarType.valueOf(name) }
                    .onFailure { println("LixoProfiles: WARN profile $id unknown avatar type '$name'") }
                    .getOrNull()
            }
            .toSet()
    },
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
    achievementProgress = decodeOr("achievementProgress", emptyMap()) {
        profileJson.decodeFromString<Map<String, Int>>(achievementProgressJson)
    },
    achievementCountedLevelIds = decodeOr("achievementCountedLevelIds", emptyMap()) {
        profileJson.decodeFromString<Map<String, Set<String>>>(achievementCountedLevelIdsJson)
    },
    unlockedAchievementIds = decodeOr("unlockedAchievementIds", emptySet()) {
        profileJson.decodeFromString<Set<String>>(unlockedAchievementIdsJson)
    },
    pendingAchievementRewardIds = decodeOr("pendingAchievementRewardIds", emptySet()) {
        profileJson.decodeFromString<Set<String>>(pendingAchievementRewardIdsJson)
    }
)

internal fun LevelProgress.toEntity(): LevelProgressEntity = LevelProgressEntity(
    profileId = profileId,
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

internal fun LevelProgressEntity.toDomain(): LevelProgress = LevelProgress(
    profileId = profileId,
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
