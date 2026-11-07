package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.local.toDomain
import se.lixo.app.data.local.toEntity
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject

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
}

class ProfileStoreTest {

    private fun newRepo(store: ProfileStore?, isDebugBuild: Boolean) =
        LixoRepository(FirebaseSyncService(), lexiconCache = null, isDebugBuild = isDebugBuild, profileStore = store)

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
        assertTrue(repo.unlockedItemIds.value.contains(BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID))
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

        repoA.unlockWardrobeItem("item_test_hat")
        repoA.equipItemOnActiveProfile(EquipmentSlot.HAT, "item_test_hat")
        repoA.awaitPendingProfileWrites()

        val repoB = newRepo(store, isDebugBuild = false)
        repoB.profilesLoaded.first { it }

        assertTrue(repoB.unlockedItemIds.value.contains("item_test_hat"))
        val profile = repoB.profiles.value.single { it.id == ella.id }
        assertEquals("item_test_hat", profile.appearance.equippedItems[EquipmentSlot.HAT])
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
            publicUsername = "testa_123",
            ageYears = 9,
            tutorialCompleted = true,
            tutorialSkippableByParent = true,
            appearance = AvatarAppearance(
                avatarType = AvatarType.DOG,
                primaryTintHex = 0xFF112233,
                eyeExpressionIndex = 2,
                smileExpressionIndex = 1,
                equippedItems = mapOf(
                    EquipmentSlot.HAT to "item_hat_1",
                    EquipmentSlot.SHOES to "item_shoes_1"
                )
            ),
            unlockedAvatars = setOf(AvatarType.CAT, AvatarType.DOG),
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
            pendingAchievementRewardIds = setOf("ach_2")
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
}
