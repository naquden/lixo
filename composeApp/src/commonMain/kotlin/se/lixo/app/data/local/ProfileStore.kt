package se.lixo.app.data.local

import kotlin.time.Clock
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.social.Nickname
import se.lixo.app.platform.createPlatformLixoDatabase

/**
 * Local on-device persistence of child profiles, the active profile id, the universal
 * unlocked-item pool, per-level progress, friends and the cloud identity uid. Backed by Room when
 * a platform database is available ([RoomProfileStore]), and absent (`null`) otherwise, in which
 * case [se.lixo.app.data.repository.LixoRepository] stays purely in-memory (same pattern as
 * [LexiconCache] / [createPlatformLexiconCache]).
 */
/** Plain (non-Room) mirror of [FriendRemovalTombstoneEntity] (doc/plans/030 review M2). */
data class FriendRemovalTombstone(
    val profileId: String,
    val friendUid: String,
    val myCardId: String,
    val friendCardId: String,
    val createdAtEpochMillis: Long
)

interface ProfileStore {
    suspend fun loadProfiles(): List<ChildProfile>
    suspend fun loadActiveProfileId(): String?
    suspend fun loadUnlockedItemIds(): Set<String>
    suspend fun loadLevelProgress(): List<LevelProgress>
    suspend fun saveProfile(profile: ChildProfile)
    suspend fun saveActiveProfileId(profileId: String)
    suspend fun saveUnlockedItem(itemId: String, source: String)
    suspend fun saveLevelProgress(progress: LevelProgress)

    /** doc/plans/030 §5: every friend, for every profile, keyed by owning profile id. */
    suspend fun loadFriends(): Map<String, List<FriendProfile>>
    suspend fun saveFriend(profileId: String, friend: FriendProfile)
    suspend fun deleteFriend(profileId: String, friendUid: String)

    /** Updates the card-derived fields only; never touches the device-only [FriendProfile.label]. */
    suspend fun updateFriendCard(profileId: String, friendUid: String, nick: Nickname, appearance: AvatarAppearance)
    suspend fun setFriendLabel(profileId: String, friendUid: String, label: String?)
    suspend fun markFriendRevealed(profileId: String, friendUid: String, revealedAtEpochMillis: Long)

    /** doc/plans/030 review M2: pending cloud-side friend removals, retried by `refreshFriends`. */
    suspend fun loadFriendRemovalTombstones(): List<FriendRemovalTombstone>
    suspend fun saveFriendRemovalTombstone(tombstone: FriendRemovalTombstone)
    suspend fun deleteFriendRemovalTombstone(profileId: String, friendUid: String)

    /** doc/plans/030 §1: the current Firebase Auth uid, cached so it survives an offline restart. */
    suspend fun loadCloudUid(): String?
    suspend fun saveCloudUid(uid: String)
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

    override suspend fun loadFriends(): Map<String, List<FriendProfile>> =
        dao.getAllFriends().groupBy({ it.profileId }, { it.toDomain() })

    override suspend fun saveFriend(profileId: String, friend: FriendProfile) {
        dao.insertFriend(friend.toEntity(profileId))
    }

    override suspend fun deleteFriend(profileId: String, friendUid: String) {
        dao.deleteFriend(profileId, friendUid)
    }

    override suspend fun updateFriendCard(profileId: String, friendUid: String, nick: Nickname, appearance: AvatarAppearance) {
        dao.updateFriendCard(
            profileId = profileId,
            friendUid = friendUid,
            nickJson = profileJson.encodeToString(nick),
            appearanceJson = profileJson.encodeToString(appearance)
        )
    }

    override suspend fun setFriendLabel(profileId: String, friendUid: String, label: String?) {
        dao.setLabel(profileId, friendUid, label)
    }

    override suspend fun markFriendRevealed(profileId: String, friendUid: String, revealedAtEpochMillis: Long) {
        dao.markRevealed(profileId, friendUid, revealedAtEpochMillis)
    }

    override suspend fun loadFriendRemovalTombstones(): List<FriendRemovalTombstone> =
        dao.getAllFriendRemovalTombstones().map {
            FriendRemovalTombstone(it.profileId, it.friendUid, it.myCardId, it.friendCardId, it.createdAtEpochMillis)
        }

    override suspend fun saveFriendRemovalTombstone(tombstone: FriendRemovalTombstone) {
        dao.insertFriendRemovalTombstone(
            FriendRemovalTombstoneEntity(
                profileId = tombstone.profileId,
                friendUid = tombstone.friendUid,
                myCardId = tombstone.myCardId,
                friendCardId = tombstone.friendCardId,
                createdAtEpochMillis = tombstone.createdAtEpochMillis
            )
        )
    }

    override suspend fun deleteFriendRemovalTombstone(profileId: String, friendUid: String) {
        dao.deleteFriendRemovalTombstone(profileId, friendUid)
    }

    override suspend fun loadCloudUid(): String? = dao.getAppSettings()?.cloudUid

    override suspend fun saveCloudUid(uid: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = dao.getAppSettings()
        dao.upsertAppSettings(
            existing?.copy(cloudUid = uid, updatedAtEpochMillis = now)
                ?: AppSettingsEntity(activeProfileId = null, cloudUid = uid, updatedAtEpochMillis = now)
        )
    }
}

fun createPlatformProfileStore(): ProfileStore? =
    createPlatformLixoDatabase()?.let { RoomProfileStore(it.lixoDao()) }
