package se.lixo.app.social

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * Regression tests for temp/verify/030_review.md H4/H5 (offline must never delete a friend; a
 * server-confirmed Gone must), M2 (removal tombstones), M3 (profile switch mid-refresh), M8
 * (ensureMyCard concurrency) and M4 (deleteMyCard cleans up readers/invites too).
 */
class FriendRobustnessTest {

    private fun fakeGateway(uid: String): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
        override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
    }

    private fun buildRepo(uid: String, store: FakeFriendCloudStore, friendCloud: FakeFriendCloud = FakeFriendCloud(uid, store) { 0L }): LixoRepository {
        val repo = LixoRepository(
            fakeGateway(uid),
            friendCloud = friendCloud,
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
    fun h5_offlineCardState_doesNotDeleteFriend() = runTest {
        val store = FakeFriendCloudStore()
        val cloudA = FakeFriendCloud("uidA", store) { 0L }
        val repoA = buildRepo("uidA", store, cloudA)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        repoA.refreshFriends()
        assertEquals(1, repoA.friendsOfActiveProfile().size)

        // B's card still exists and A is still a reader -- but A's device goes offline: observeCard
        // must report Unknown (inconclusive), never Gone, and refreshFriends must NOT drop the friend.
        cloudA.offline = true
        repoA.refreshFriends()
        assertEquals(1, repoA.friendsOfActiveProfile().size, "offline/Unknown must never delete a friend")

        cloudA.offline = false
        repoA.refreshFriends()
        assertEquals(1, repoA.friendsOfActiveProfile().size, "still a friend once back online")
    }

    @Test
    fun h4h5_serverConfirmedGone_doesDeleteFriend() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        repoA.refreshFriends()
        assertEquals(1, repoA.friendsOfActiveProfile().size)

        // B unfriends A directly at the cloud layer (simulating B's own removeFriend call):
        // myCardId = B's own card, friendUid = A's uid, friendCardId = A's card.
        val bOwnCardId = repoB.ensureMyCard().getOrThrow()
        val aCardIdAsSeenByB = repoA.ensureMyCard().getOrThrow()
        FakeFriendCloud("uidB", store) { 0L }.removeFriend(bOwnCardId, "uidA", aCardIdAsSeenByB)

        repoA.refreshFriends()
        assertTrue(repoA.friendsOfActiveProfile().isEmpty(), "a server-confirmed Gone card must drop the friend")
    }

    @Test
    fun m2_tombstone_survivesAndPreventsReAddUntilCloudDeleteSucceeds() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        repoA.refreshFriends()
        val bUid = repoA.friendsOfActiveProfile().first().userId

        repoA.removeFriend(bUid)
        assertTrue(repoA.friendsOfActiveProfile().isEmpty())

        // Even before the fire-and-forget cloud delete has had a chance to run, a refresh must
        // not re-discover B's still-live reader doc on A's card and resurrect the friendship.
        repoA.refreshFriends()
        assertTrue(repoA.friendsOfActiveProfile().isEmpty(), "a tombstoned uid must not be re-added by refreshFriends")
    }

    @Test
    fun m3_profileSwitchMidRefresh_doesNotMixProfiles() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val profile1 = repoA.profiles.value.first()
        val profile2 = repoA.createProfile("Sibling", 7)

        val invite = repoB.createFriendCode().getOrThrow()
        repoA.selectProfile(profile1.id)
        assertTrue(repoA.addFriendByCode(invite.code, fromQr = false).isSuccess)
        assertEquals(1, repoA.friendsOfActiveProfile().size)

        // Switch active profile and make sure the OTHER profile's friend list is untouched by a
        // refresh that targets the new active profile (which has no friends/card at all).
        repoA.selectProfile(profile2.id)
        repoA.refreshFriends()
        assertTrue(repoA.friendsOfActiveProfile().isEmpty(), "the newly active profile has no friends")

        repoA.selectProfile(profile1.id)
        assertEquals(1, repoA.friendsOfActiveProfile().size, "profile1's friend must survive the other profile's refresh")
    }

    @Test
    fun m8_concurrentEnsureMyCard_createsExactlyOneCard() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)

        val results = coroutineScope {
            (1..5).map { async { repoA.ensureMyCard() } }.map { it.await() }
        }
        val cardIds = results.map { it.getOrThrow() }.toSet()
        assertEquals(1, cardIds.size, "every concurrent ensureMyCard call must resolve to the SAME card id")
        assertEquals(1, store.cardCountForTest(), "only one card document must have been created")
    }

    @Test
    fun m4_deleteMyCard_removesReadersAndInvitesToo() = runTest {
        val store = FakeFriendCloudStore()
        val repoA = buildRepo("uidA", store)
        val repoB = buildRepo("uidB", store)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        val cardId = repoA.ensureMyCard().getOrThrow()

        assertTrue(store.hasAnyReaderForTest(cardId))
        assertTrue(store.hasAnyInviteForTest(cardId))

        val cloudA = FakeFriendCloud("uidA", store) { 0L }
        assertTrue(cloudA.deleteMyCard(cardId).isSuccess)

        assertFalse(store.hasAnyReaderForTest(cardId), "readers of the deleted card must be gone too")
        assertFalse(store.hasAnyInviteForTest(cardId), "invites for the deleted card must be gone too")
        assertFalse(store.hasCardForTest(cardId))
    }
}
