package se.lixo.app.social

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.SampleContentPack

/**
 * doc/plans/030 §7 ("kompis-kistan"): `unrevealedFriends` surfaces new readers found by
 * `refreshFriends` until `markRevealed` is called; a scanner-side add is revealed immediately.
 */
class FriendRevealTest {

    private fun fakeGateway(uid: String): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
        override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
    }

    private fun buildRepo(uid: String, store: FakeFriendCloudStore): LixoRepository {
        val repo = LixoRepository(
            fakeGateway(uid),
            friendCloud = FakeFriendCloud(uid, store) { 0L },
            clockMillis = { 0L },
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            content = ContentRepository(SampleContentPack.activeContent())
        )
        repo.createProfile("Child_$uid", 8)
        return repo
    }

    @Test
    fun scannerSideAdd_isAlreadyRevealed() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)

        val profileId = repoB.activeProfileId.value!!
        assertTrue(repoB.unrevealedFriends(profileId).first().isEmpty())
    }

    @Test
    fun inviterSideAdd_isUnrevealedUntilMarked() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)

        val profileId = repoA.activeProfileId.value!!
        assertTrue(repoA.unrevealedFriends(profileId).first().isEmpty())

        repoA.refreshFriends()
        val unrevealed = repoA.unrevealedFriends(profileId).first()
        assertEquals(1, unrevealed.size)

        repoA.markRevealed(profileId, unrevealed.first().userId)
        assertTrue(repoA.unrevealedFriends(profileId).first().isEmpty())
    }

    @Test
    fun severalNewFriendsAtOnce_allUnrevealedUntilEachIsMarked() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)
        val repoC = buildRepo("uidC", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)
        repoC.addFriendByCode(invite.code, fromQr = false)

        val profileId = repoA.activeProfileId.value!!
        repoA.refreshFriends()
        val unrevealed = repoA.unrevealedFriends(profileId).first()
        assertEquals(2, unrevealed.size)

        unrevealed.forEach { repoA.markRevealed(profileId, it.userId) }
        assertTrue(repoA.unrevealedFriends(profileId).first().isEmpty())
    }
}
