package se.lixo.app.data.local

import kotlinx.datetime.Clock
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.platform.createPlatformLixoDatabase

/**
 * Local on-device persistence of child profiles, the active profile id, the universal
 * unlocked-item pool, and per-level progress. Backed by Room when a platform database is
 * available ([RoomProfileStore]), and absent (`null`) otherwise, in which case
 * [se.lixo.app.data.repository.LixoRepository] stays purely in-memory (same pattern as
 * [LexiconCache] / [createPlatformLexiconCache]).
 */
interface ProfileStore {
    suspend fun loadProfiles(): List<ChildProfile>
    suspend fun loadActiveProfileId(): String?
    suspend fun loadUnlockedItemIds(): Set<String>
    suspend fun loadLevelProgress(): List<LevelProgress>
    suspend fun saveProfile(profile: ChildProfile)
    suspend fun saveActiveProfileId(profileId: String)
    suspend fun saveUnlockedItem(itemId: String, source: String)
    suspend fun saveLevelProgress(progress: LevelProgress)
}

class RoomProfileStore(private val dao: LixoDao) : ProfileStore {
    override suspend fun loadProfiles(): List<ChildProfile> = dao.getAllProfiles().map { it.toDomain() }

    override suspend fun loadActiveProfileId(): String? = dao.getAppSettings()?.activeProfileId

    override suspend fun loadUnlockedItemIds(): Set<String> = dao.getUnlockedItemIds().toSet()

    override suspend fun loadLevelProgress(): List<LevelProgress> = dao.getAllLevelProgress().map { it.toDomain() }

    override suspend fun saveProfile(profile: ChildProfile) {
        dao.upsertProfile(profile.toEntity())
    }

    override suspend fun saveActiveProfileId(profileId: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = dao.getAppSettings()
        dao.upsertAppSettings(
            existing?.copy(activeProfileId = profileId, updatedAtEpochMillis = now)
                ?: AppSettingsEntity(activeProfileId = profileId, updatedAtEpochMillis = now)
        )
    }

    override suspend fun saveUnlockedItem(itemId: String, source: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        dao.insertUnlockedItem(UnlockedInventoryEntity(itemId = itemId, unlockedAtEpochMillis = now, unlockedBySource = source))
    }

    override suspend fun saveLevelProgress(progress: LevelProgress) {
        dao.upsertLevelProgress(progress.toEntity())
    }
}

fun createPlatformProfileStore(): ProfileStore? =
    createPlatformLixoDatabase()?.let { RoomProfileStore(it.lixoDao()) }
