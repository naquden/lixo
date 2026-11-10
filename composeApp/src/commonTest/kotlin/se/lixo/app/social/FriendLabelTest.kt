package se.lixo.app.social

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.SampleContentPack

/** doc/plans/030 §5: the private label is shown locally, survives a card refresh, and never reaches the cloud. */
class FriendLabelTest {

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
    fun label_isShownAndSurvivesACardRefresh() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)
        val friendUid = repoB.friendsOfActiveProfile().first().userId

        repoB.setFriendLabel(friendUid, "Hemligt-Olle")
        assertEquals("Hemligt-Olle", repoB.friendsOfActiveProfile().first().label)

        // A refresh re-reads the card but must never clobber the local label.
        repoB.refreshFriends()
        assertEquals("Hemligt-Olle", repoB.friendsOfActiveProfile().first().label)
    }

    @Test
    fun label_privacy_neverAppearsInAnyCloudWrite() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)
        val friendUid = repoB.friendsOfActiveProfile().first().userId

        val secretLabel = "SuperHemligtSmeknamn42"
        repoB.setFriendLabel(friendUid, secretLabel)

        assertFalse(store.recordedWrites.any { it.contains(secretLabel) })
    }

    @Test
    fun label_blankClearsToNickname() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        repoB.addFriendByCode(invite.code, fromQr = false)
        val friendUid = repoB.friendsOfActiveProfile().first().userId

        repoB.setFriendLabel(friendUid, "Olle")
        assertTrue(repoB.friendsOfActiveProfile().first().label == "Olle")

        repoB.setFriendLabel(friendUid, "   ")
        assertEquals(null, repoB.friendsOfActiveProfile().first().label)
    }
}
