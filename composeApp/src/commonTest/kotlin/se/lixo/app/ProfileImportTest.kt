package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.local.ImportMode
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.social.Nickname

class ProfileImportTest {

    private fun newRepo(store: FakeProfileStore, uid: String = "uid_import") =
        LixoRepository(
            FirebaseSyncService(),
            friendCloud = FakeFriendCloud(uid),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = store,
            content = ContentRepository(SampleContentPack.activeContent())
        )

    @Test
    fun importAsNew_addsProfileProgressAndSelectsIt() = runTest {
        val sourceStore = FakeProfileStore()
        val source = newRepo(sourceStore)
        source.profilesLoaded.first { it }
        val ella = source.createProfile("Ella", 8)
        source.unlockWardrobeItem("item_hat_test_cone")
        source.saveLevelProgress(LevelProgress(ella.id, "level_1", 1, 3, false, 30, 40))
        source.awaitPendingProfileWrites()
        val backup = source.buildProfileBackup(ella.id)!!

        val destStore = FakeProfileStore()
        val dest = newRepo(destStore, "uid_dest")
        dest.profilesLoaded.first { it }

        assertFalse(dest.backupConflictsWithExisting(backup))
        val result = dest.importProfileBackup(backup, ImportMode.AS_NEW)
        dest.awaitPendingProfileWrites()

        val restored = result.getOrThrow()
        assertEquals(ella.id, restored.id)
        assertEquals(ella.id, dest.activeProfileId.value)
        assertEquals(1, dest.levelProgressByProfile.value[ella.id]?.size)
        assertTrue("item_hat_test_cone" in dest.unlockedItemIds.value)
    }

    @Test
    fun importReplace_overwritesProgressKeepsLocalFriendsAndCardId() = runTest {
        val store = FakeProfileStore()
        val now = 1_000L
        val ella = ChildProfile(
            id = "profile_ella",
            displayName = "Ella",
            friendCardId = "card_local",
            nickname = Nickname(1, 1, 1),
            totalStars = 10,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        store.seed(ella)
        store.activeId = ella.id
        store.saveFriend(ella.id, FriendProfile(userId = "friend_uid", cardId = "friend_card", nick = Nickname(2, 2, 2), appearance = AvatarAppearance()))

        val sourceRepo = newRepo(store)
        sourceRepo.profilesLoaded.first { it }
        sourceRepo.saveLevelProgress(LevelProgress(ella.id, "level_1", 1, 3, false, 30, 40))
        sourceRepo.awaitPendingProfileWrites()
        val backup = sourceRepo.buildProfileBackup(ella.id)!!
        assertEquals(10, backup.profile.totalStars)

        // Mutate local state after taking the backup: stars drop, progress changes, an extra
        // local-only level row appears that the backup doesn't know about.
        sourceRepo.updateProfile(sourceRepo.profiles.value.first { it.id == ella.id }.copy(totalStars = 0))
        sourceRepo.saveLevelProgress(LevelProgress(ella.id, "level_1", 1, 0, false, 0, 0))
        sourceRepo.saveLevelProgress(LevelProgress(ella.id, "level_extra", 2, 1, false, 10, 10))
        sourceRepo.awaitPendingProfileWrites()

        assertTrue(sourceRepo.backupConflictsWithExisting(backup))
        val result = sourceRepo.importProfileBackup(backup, ImportMode.REPLACE_EXISTING)
        sourceRepo.awaitPendingProfileWrites()

        val restored = result.getOrThrow()
        assertEquals("card_local", restored.friendCardId)
        assertEquals(Nickname(1, 1, 1), restored.nickname)
        assertEquals(10, restored.totalStars)
        assertEquals(1, sourceRepo.friendsByProfile.value[ella.id]?.size)

        val progress = sourceRepo.levelProgressByProfile.value[ella.id].orEmpty()
        assertEquals(setOf("level_1"), progress.keys)
        assertEquals(3, progress["level_1"]?.starsEarned)
        assertFalse(store.levelProgress.keys.any { it.first == ella.id && it.second == "level_extra" })
    }

    @Test
    fun import_unionsUnlockedItems_neverRemoves() = runTest {
        val store = FakeProfileStore()
        val repo = newRepo(store)
        repo.profilesLoaded.first { it }
        val ella = repo.createProfile("Ella", 8)
        repo.unlockWardrobeItem("item_pants_test_shorts")
        repo.awaitPendingProfileWrites()

        val backup = repo.buildProfileBackup(ella.id)!!.copy(
            unlockedItems = listOf(
                se.lixo.app.data.local.BackupUnlockedItem("item_pants_test_shorts", 1L, "STAR_MILESTONE"),
                se.lixo.app.data.local.BackupUnlockedItem("item_hat_test_cone", 2L, "LEVEL_REWARD")
            )
        )
        repo.importProfileBackup(backup, ImportMode.REPLACE_EXISTING)
        repo.awaitPendingProfileWrites()

        assertTrue("item_pants_test_shorts" in repo.unlockedItemIds.value)
        assertTrue("item_hat_test_cone" in repo.unlockedItemIds.value)
    }

    @Test
    fun import_unknownAvatarOrEquippedItem_fallsBackToDefaults() = runTest {
        val store = FakeProfileStore()
        val repo = newRepo(store)
        repo.profilesLoaded.first { it }
        val ella = repo.createProfile("Ella", 8)
        repo.awaitPendingProfileWrites()
        val backup = repo.buildProfileBackup(ella.id)!!

        val defaultAvatarId = repo.content.active.value.defaultAvatar.id
        val backupWithBogusAppearance = backup.copy(
            profile = backup.profile.copy(
                appearance = backup.profile.appearance.copy(
                    avatarId = "avatar_nope",
                    equipped = mapOf(
                        "HAT" to "item_not_in_pack",
                        "PANTS" to "item_pants_test_shorts" // not unlocked by this profile
                    )
                ),
                unlockedAvatarIds = listOf("avatar_nope")
            )
        )

        val result = repo.importProfileBackup(backupWithBogusAppearance, ImportMode.REPLACE_EXISTING)
        repo.awaitPendingProfileWrites()
        val restored = result.getOrThrow()

        assertEquals(defaultAvatarId, restored.appearance.avatarId)
        assertTrue(restored.appearance.equipped.isEmpty())
        assertFalse("avatar_nope" in restored.unlockedAvatarIds)
    }

    @Test
    fun exportThenImportAfterWipe_restoresSameProfile() = runTest {
        val store = FakeProfileStore()
        val repoA = newRepo(store)
        repoA.profilesLoaded.first { it }
        val ella = repoA.createProfile("Ella", 8)
        repoA.unlockWardrobeItem("item_hat_test_cone")
        repoA.saveLevelProgress(LevelProgress(ella.id, "level_1", 1, 3, false, 30, 40))
        repoA.awaitPendingProfileWrites()
        val backup = repoA.buildProfileBackup(ella.id)!!

        val freshStore = FakeProfileStore()
        val repoB = newRepo(freshStore, "uid_fresh")
        repoB.profilesLoaded.first { it }
        repoB.importProfileBackup(backup, ImportMode.AS_NEW)
        repoB.awaitPendingProfileWrites()

        val repoC = newRepo(freshStore, "uid_fresh_2")
        repoC.profilesLoaded.first { it }
        assertEquals(listOf(ella.id), repoC.profiles.value.map { it.id })
        assertEquals(1, repoC.levelProgressByProfile.value[ella.id]?.size)
        assertTrue("item_hat_test_cone" in repoC.unlockedItemIds.value)
    }

    @Test
    fun conflictAndModeMismatchFailures() = runTest {
        val store = FakeProfileStore()
        val repo = newRepo(store)
        repo.profilesLoaded.first { it }
        val ella = repo.createProfile("Ella", 8)
        repo.awaitPendingProfileWrites()
        val backup = repo.buildProfileBackup(ella.id)!!

        assertTrue(repo.backupConflictsWithExisting(backup))

        val asNewOnExisting = repo.importProfileBackup(backup, ImportMode.AS_NEW)
        assertTrue(asNewOnExisting.isFailure)

        val nonExistentBackup = backup.copy(profile = backup.profile.copy(id = "profile_nobody"))
        assertFalse(repo.backupConflictsWithExisting(nonExistentBackup))
        val replaceMissing = repo.importProfileBackup(nonExistentBackup, ImportMode.REPLACE_EXISTING)
        assertTrue(replaceMissing.isFailure)
    }
}
