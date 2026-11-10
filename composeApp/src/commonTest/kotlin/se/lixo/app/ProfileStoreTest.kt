package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.local.FriendRemovalTombstone
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.local.toDomain
import se.lixo.app.data.local.toEntity
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.social.Nickname

/**
 * In-memory [ProfileStore] used to exercise [LixoRepository]'s persistence plumbing
 * (doc/plans/008-persist-profiles.md) without a real Room database.
 */
class FakeProfileStore : ProfileStore {
    private val profilesById = LinkedHashMap<String, ChildProfile>()
    var activeId: String? = null
    val unlocked = mutableSetOf<String>()
    val savedProfileIds = mutableListOf<String>()
    val levelProgress = LinkedHashMap<Pair<String, String>, LevelProgress>()
    val friendsByProfile = LinkedHashMap<String, LinkedHashMap<String, FriendProfile>>()
    var cloudUid: String? = null

    fun seed(profile: ChildProfile) {
        profilesById[profile.id] = profile
    }

    override suspend fun loadProfiles(): List<ChildProfile> =
        profilesById.values.sortedBy { it.createdAtEpochMillis }

    override suspend fun loadActiveProfileId(): String? = activeId

    override suspend fun loadUnlockedItemIds(): Set<String> = unlocked.toSet()

    override suspend fun loadLevelProgress(): List<LevelProgress> = levelProgress.values.toList()

    override suspend fun saveProfile(profile: ChildProfile) {
        profilesById[profile.id] = profile
        savedProfileIds.add(profile.id)
    }

    override suspend fun saveActiveProfileId(profileId: String) {
        activeId = profileId
    }

    override suspend fun saveUnlockedItem(itemId: String, source: String) {
        unlocked.add(itemId)
    }

    override suspend fun saveLevelProgress(progress: LevelProgress) {
        levelProgress[progress.profileId to progress.levelId] = progress
    }

    override suspend fun loadFriends(): Map<String, List<FriendProfile>> =
        friendsByProfile.mapValues { (_, byUid) -> byUid.values.toList() }

    override suspend fun saveFriend(profileId: String, friend: FriendProfile) {
        friendsByProfile.getOrPut(profileId) { LinkedHashMap() }[friend.userId] = friend
    }

    override suspend fun deleteFriend(profileId: String, friendUid: String) {
        friendsByProfile[profileId]?.remove(friendUid)
    }

    override suspend fun updateFriendCard(profileId: String, friendUid: String, nick: Nickname, appearance: AvatarAppearance) {
        val existing = friendsByProfile[profileId]?.get(friendUid) ?: return
        friendsByProfile.getOrPut(profileId) { LinkedHashMap() }[friendUid] = existing.copy(nick = nick, appearance = appearance)
    }

    override suspend fun setFriendLabel(profileId: String, friendUid: String, label: String?) {
        val existing = friendsByProfile[profileId]?.get(friendUid) ?: return
        friendsByProfile.getOrPut(profileId) { LinkedHashMap() }[friendUid] = existing.copy(label = label)
    }

    override suspend fun markFriendRevealed(profileId: String, friendUid: String, revealedAtEpochMillis: Long) {
        val existing = friendsByProfile[profileId]?.get(friendUid) ?: return
        friendsByProfile.getOrPut(profileId) { LinkedHashMap() }[friendUid] = existing.copy(revealedAtEpochMillis = revealedAtEpochMillis)
    }

    override suspend fun loadCloudUid(): String? = cloudUid

    override suspend fun saveCloudUid(uid: String) {
        cloudUid = uid
    }

    val tombstones = LinkedHashMap<Pair<String, String>, FriendRemovalTombstone>()

    override suspend fun loadFriendRemovalTombstones(): List<FriendRemovalTombstone> = tombstones.values.toList()

    override suspend fun saveFriendRemovalTombstone(tombstone: FriendRemovalTombstone) {
        tombstones[tombstone.profileId to tombstone.friendUid] = tombstone
    }

    override suspend fun deleteFriendRemovalTombstone(profileId: String, friendUid: String) {
        tombstones.remove(profileId to friendUid)
    }
}

class ProfileStoreTest {

    private fun newRepo(store: ProfileStore?, isDebugBuild: Boolean) =
        LixoRepository(
            FirebaseSyncService(),
            lexiconCache = null,
            isDebugBuild = isDebugBuild,
            profileStore = store,
            content = ContentRepository(SampleContentPack.activeContent())
        )

    @Test
    fun profiles_persistAcrossRepositoryInstances() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        repoA.awaitPendingProfileWrites()

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        assertEquals(listOf(ella.id), repoB.profiles.value.map { it.id })
        assertEquals("Ella", repoB.profiles.value.single().displayName)
        assertEquals(ella.id, repoB.activeProfileId.value)
    }

    @Test
    fun debugSeed_doesNotOverwriteUserProfiles() = runTest {
        val now = 1_000L
        val store = FakeProfileStore()
        val ella = ChildProfile(id = "profile_ella", displayName = "Ella", createdAtEpochMillis = now, updatedAtEpochMillis = now)
        val saga = ChildProfile(
            id = LixoRepository.DEBUG_SAGA_PROFILE_ID,
            displayName = "Saga2",
            totalStars = 9,
            createdAtEpochMillis = now + 1,
            updatedAtEpochMillis = now + 1
        )
        store.seed(ella)
        store.seed(saga)

        val repo = newRepo(store, isDebugBuild = true)
        repo.profilesLoaded.first { it }
        repo.awaitPendingProfileWrites()

        val byId = repo.profiles.value.associateBy { it.id }
        assertEquals("Ella", byId.getValue("profile_ella").displayName)
        assertEquals("Saga2", byId.getValue(LixoRepository.DEBUG_SAGA_PROFILE_ID).displayName)
        assertEquals(9, byId.getValue(LixoRepository.DEBUG_SAGA_PROFILE_ID).totalStars)
        assertTrue(store.savedProfileIds.isEmpty())
    }

    @Test
    fun debugSeed_addsSagaWhenMissing() = runTest {
        val now = 1_000L
        val store = FakeProfileStore()
        store.seed(ChildProfile(id = "profile_ella", displayName = "Ella", createdAtEpochMillis = now, updatedAtEpochMillis = now))
        store.activeId = "profile_ella"

        val repo = newRepo(store, isDebugBuild = true)
        repo.profilesLoaded.first { it }
        repo.awaitPendingProfileWrites()

        val ids = repo.profiles.value.map { it.id }.toSet()
        assertTrue(ids.contains(LixoRepository.DEBUG_SAGA_PROFILE_ID))
        assertTrue(ids.contains("profile_ella"))
        assertEquals("profile_ella", repo.activeProfileId.value)
        assertTrue(repo.unlockedItemIds.value.contains(repo.content.active.value.dropRules.tutorialRewardItemId))
        assertEquals(listOf(LixoRepository.DEBUG_SAGA_PROFILE_ID), store.savedProfileIds)
    }

    @Test
    fun release_neverSeedsSaga() = runTest {
        val store = FakeProfileStore()
        val repo = newRepo(store, isDebugBuild = false)
        repo.profilesLoaded.first { it }
        repo.awaitPendingProfileWrites()

        assertTrue(repo.profiles.value.isEmpty())
        assertNull(repo.activeProfileId.value)
        assertTrue(store.loadProfiles().isEmpty())
    }

    @Test
    fun unlockAndEquip_persisted() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        repoA.awaitPendingProfileWrites()

        repoA.unlockWardrobeItem("item_hat_test_cone")
        repoA.equipItemOnActiveProfile(EquipmentSlot.HAT, "item_hat_test_cone")
        repoA.awaitPendingProfileWrites()

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        assertTrue(repoB.unlockedItemIds.value.contains("item_hat_test_cone"))
        val profile = repoB.profiles.value.single { it.id == ella.id }
        assertEquals("item_hat_test_cone", profile.appearance.equipped[EquipmentSlot.HAT])
    }

    @Test
    fun selectProfile_persistsActiveId() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        val kalle = repoA.createProfile("Kalle", 8)
        repoA.selectProfile(ella.id)
        repoA.awaitPendingProfileWrites()

        assertEquals(ella.id, store.loadActiveProfileId())

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }
        assertEquals(ella.id, repoB.activeProfileId.value)
        assertEquals(2, repoB.profiles.value.size)
        assertFalse(kalle.id == ella.id)
    }

    @Test
    fun mapper_roundTrip() {
        val p = ChildProfile(
            id = "profile_x",
            displayName = "Testa",
            nickname = Nickname(adj = 1, animal = 2, num = 42),
            friendCardId = "card_testa_123",
            ageYears = 9,
            tutorialCompleted = true,
            tutorialSkippableByParent = true,
            appearance = AvatarAppearance(
                avatarId = "avatar_dog",
                tintHex = 0xFF112233,
                eyeIndex = 2,
                smileIndex = 1,
                equipped = mapOf(
                    EquipmentSlot.HAT to "item_hat_1",
                    EquipmentSlot.SHOES to "item_shoes_1"
                )
            ),
            unlockedAvatarIds = setOf("avatar_cat", "avatar_dog"),
            currentLevelOrder = 12,
            totalStars = 42,
            currentStreakDays = 3,
            bestStreakDays = 10,
            lastStreakDateIso = "2026-11-01",
            dailyReadingGoalMinutes = 20,
            dailyMathGoalProblems = 15,
            createdAtEpochMillis = 1000L,
            updatedAtEpochMillis = 2000L,
            chestProgressHalfPoints = 3,
            achievementProgress = mapOf("ach_1" to 2, "ach_2" to 5),
            achievementCountedLevelIds = mapOf("ach_1" to setOf("level_1", "level_2")),
            unlockedAchievementIds = setOf("ach_1"),
            pendingAchievementRewardIds = setOf("ach_2"),
            chestsSinceRarePlus = 2,
            chestsSinceEpicPlus = 7,
            chestsSinceLegendary = 13
        )

        assertEquals(p, p.toEntity().toDomain())
    }

    @Test
    fun levelProgress_persistsAcrossRepositoryInstances() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        val l1 = repoA.levels.value.filter { it.subject == Subject.SWEDISH }.sortedBy { it.order }.first()
        val saved = LevelProgress(
            profileId = ella.id,
            levelId = l1.id,
            levelOrder = l1.order,
            starsEarned = 2,
            completedInAiMode = false,
            bestTimeSeconds = 42,
            bestWpm = 30,
            completedTimedChallenge = true,
            timesCompleted = 3,
            updatedAtEpochMillis = 1234L
        )
        repoA.saveLevelProgress(saved)
        repoA.awaitPendingProfileWrites()

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        assertEquals(saved, repoB.levelProgressByProfile.value[ella.id]?.get(l1.id))
    }

    @Test
    fun levelProgress_nextLevelUnlockedAfterColdStart() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        val swedishLevels = repoA.levels.value.filter { it.subject == Subject.SWEDISH }.sortedBy { it.order }
        val l1 = swedishLevels[0]
        val l2 = swedishLevels[1]
        val l3 = swedishLevels[2]
        repoA.saveLevelProgress(
            LevelProgress(
                profileId = ella.id,
                levelId = l1.id,
                levelOrder = l1.order,
                starsEarned = 2,
                completedInAiMode = false,
                bestTimeSeconds = 42,
                bestWpm = 30,
                completedTimedChallenge = true,
                timesCompleted = 3,
                updatedAtEpochMillis = 1234L
            )
        )
        repoA.awaitPendingProfileWrites()

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        val progressByLevelId = repoB.levelProgressByProfile.value[ella.id].orEmpty()
        val engine = ProgressionAndGiftEngine()
        assertTrue(engine.isLevelUnlocked(l2.order, progressByLevelId, repoB.levels.value))
        assertFalse(engine.isLevelUnlocked(l3.order, progressByLevelId, repoB.levels.value))
    }

    @Test
    fun levelProgress_mapperRoundTrip() {
        val p = LevelProgress(
            profileId = "profile_x",
            levelId = "level_1",
            levelOrder = 1,
            starsEarned = 3,
            completedInAiMode = true,
            bestTimeSeconds = 60,
            bestWpm = 55,
            completedTimedChallenge = true,
            timesCompleted = 5,
            updatedAtEpochMillis = 9999L
        )

        assertEquals(p, p.toEntity().toDomain())
    }

    @Test
    fun friend_mapperRoundTrip() {
        val friend = FriendProfile(
            userId = "friend_uid_1",
            cardId = "card_friend_1",
            nick = Nickname(adj = 3, animal = 4, num = 7),
            appearance = AvatarAppearance(avatarId = "avatar_dog", equipped = mapOf(EquipmentSlot.HAT to "item_hat_1")),
            label = "Olle",
            addedAtEpochMillis = 1000L,
            revealedAtEpochMillis = 2000L
        )
        assertEquals(friend, friend.toEntity("profile_x").toDomain())
    }

    @Test
    fun friends_persistAcrossRepositoryInstancesAndStayScopedPerProfile() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store, isDebugBuild = false)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 7)
        val kalle = repoA.createProfile("Kalle", 8)

        store.saveFriend(
            ella.id,
            FriendProfile(
                userId = "friend_1",
                cardId = "card_1",
                nick = Nickname(0, 0, 1),
                appearance = AvatarAppearance(),
                label = "Min vän",
                addedAtEpochMillis = 500L
            )
        )

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        assertEquals(1, repoB.friendsByProfile.value[ella.id]?.size)
        assertEquals("Min vän", repoB.friendsByProfile.value[ella.id]?.first()?.label)
        assertTrue(repoB.friendsByProfile.value[kalle.id].orEmpty().isEmpty())
    }
}
