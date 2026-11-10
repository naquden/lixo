package se.lixo.app.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.data.remote.CardState
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.social.buildFriendPayload

/**
 * doc/plans/030 §4/§8: the friend flow (invite window, redeem, no accept step, live gear,
 * unfriending) against two or more [LixoRepository] instances sharing one [FakeFriendCloudStore].
 */
class FriendFlowTest {

    private fun fakeGateway(uid: String): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
        override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
    }

    private fun buildRepo(uid: String, store: FakeFriendCloudStore, clock: () -> Long): LixoRepository {
        val repo = LixoRepository(
            fakeGateway(uid),
            friendCloud = FakeFriendCloud(uid, store, clock),
            clockMillis = clock,
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            content = ContentRepository(SampleContentPack.activeContent())
        )
        repo.createProfile("Child_$uid", 8)
        return repo
    }

    @Test
    fun redeemByBareCodeAndByQrPayload_bothSeeEachOtherAfterRefresh() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoB = buildRepo("uidB", store, clock)
        val repoC = buildRepo("uidC", store, clock)

        val inviteForB = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(inviteForB.code, fromQr = false).isSuccess)

        val inviteForC = repoA.createFriendCode().getOrThrow()
        assertTrue(repoC.addFriendByCode(buildFriendPayload(inviteForC.code), fromQr = true).isSuccess)

        // No accept step: both sides already see each other right after redeem, before any refresh.
        assertEquals(1, repoB.friendsOfActiveProfile().size)
        assertEquals(1, repoC.friendsOfActiveProfile().size)

        repoA.refreshFriends()
        assertEquals(2, repoA.friendsOfActiveProfile().size)
    }

    @Test
    fun oneCodeManyFriends_allThreeRedeemersBecomeFriendsOfTheOwner() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoB = buildRepo("uidB", store, clock)
        val repoC = buildRepo("uidC", store, clock)
        val repoD = buildRepo("uidD", store, clock)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        assertTrue(repoC.addFriendByCode(invite.code, fromQr = false).isSuccess)
        assertTrue(repoD.addFriendByCode(invite.code, fromQr = false).isSuccess)

        repoA.refreshFriends()
        assertEquals(3, repoA.friendsOfActiveProfile().size)
        assertEquals(1, repoB.friendsOfActiveProfile().size)
        assertEquals(1, repoC.friendsOfActiveProfile().size)
        assertEquals(1, repoD.friendsOfActiveProfile().size)
    }

    @Test
    fun windowExpiry_deniesRedeemAfterTenMinutes() = runTest {
        val store = FakeFriendCloudStore()
        var now = 0L
        val clock = { now }
        val repoA = buildRepo("uidA", store, clock)
        val repoE = buildRepo("uidE", store, clock)
        val repoF = buildRepo("uidF", store, clock)

        val invite = repoA.createFriendCode().getOrThrow()

        now = 9 * 60_000L
        assertTrue(repoE.addFriendByCode(invite.code, fromQr = false).isSuccess)

        now = 11 * 60_000L
        val result = repoF.addFriendByCode(invite.code, fromQr = false)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.EXPIRED, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun stopFriendCode_deletesInvite_laterRedeemFailsWithNotFound() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoG = buildRepo("uidG", store, clock)

        val invite = repoA.createFriendCode().getOrThrow()
        repoA.stopFriendCode()

        val result = repoG.addFriendByCode(invite.code, fromQr = false)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.NOT_FOUND, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun fullInvite_thirtyFirstRedeemFailsWithFull() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val invite = repoA.createFriendCode().getOrThrow()

        repeat(30) { i ->
            val redeemer = buildRepo("uid_redeemer_$i", store, clock)
            assertTrue(redeemer.addFriendByCode(invite.code, fromQr = false).isSuccess, "redeemer $i should succeed")
        }

        val overflow = buildRepo("uid_redeemer_overflow", store, clock)
        val result = overflow.addFriendByCode(invite.code, fromQr = false)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.FULL, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun selfScan_failsWithSelf() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val invite = repoA.createFriendCode().getOrThrow()

        val result = repoA.addFriendByCode(invite.code, fromQr = false)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.SELF, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun secondRedeemBySameDevice_failsWithAlreadyFriend() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoB = buildRepo("uidB", store, clock)
        val invite = repoA.createFriendCode().getOrThrow()

        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        val second = repoB.addFriendByCode(invite.code, fromQr = false)
        assertTrue(second.isFailure)
        assertEquals(AddFriendError.ALREADY_FRIEND, (second.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun nonLixoPayload_failsWithInvalidQr() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoB = buildRepo("uidB", store, clock)

        val result = repoB.addFriendByCode("https://example.com/not-a-friend-code", fromQr = true)
        assertTrue(result.isFailure)
        assertEquals(AddFriendError.INVALID_QR, (result.exceptionOrNull() as? AddFriendException)?.reason)
    }

    @Test
    fun createFriendCode_twiceWithinWindow_returnsSameCode() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)

        val first = repoA.createFriendCode().getOrThrow()
        val second = repoA.createFriendCode().getOrThrow()
        assertEquals(first.code, second.code)
    }

    @Test
    fun equipItem_afterDebounce_friendSeesUpdatedCard() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoB = buildRepo("uidB", store, clock)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        val aCardId = repoB.friendsOfActiveProfile().first().cardId

        val hatId = repoA.content.active.value.items.first { it.slot == EquipmentSlot.HAT }.id
        repoA.unlockWardrobeItem(hatId)
        repoA.equipItemOnActiveProfile(EquipmentSlot.HAT, hatId)

        // The upsert is debounced by 2 s on a real dispatcher (doc/plans/030 §4); wait for it on
        // the same real dispatcher rather than the test's virtual clock.
        withContext(Dispatchers.Default) { delay(2500) }

        val observerOnB = FakeFriendCloud("uidB", store, clock)
        val cardAfter = observerOnB.observeCard(aCardId).first() as? CardState.Present
        assertEquals(hatId, cardAfter?.card?.appearance?.equipped?.get(EquipmentSlot.HAT))
    }

    @Test
    fun removeFriend_friendsCardFlowEmitsNullAndTheyDropEachOther() = runTest {
        val store = FakeFriendCloudStore()
        val clock = { 0L }
        val repoA = buildRepo("uidA", store, clock)
        val repoB = buildRepo("uidB", store, clock)

        val invite = repoA.createFriendCode().getOrThrow()
        assertTrue(repoB.addFriendByCode(invite.code, fromQr = false).isSuccess)
        repoA.refreshFriends()
        val bUid = repoA.friendsOfActiveProfile().first().userId
        val aCardId = repoB.friendsOfActiveProfile().first().cardId

        repoA.removeFriend(bUid)
        assertTrue(repoA.friendsOfActiveProfile().isEmpty())

        // removeFriend's gateway call is best-effort/async (doc/plans/030 §4); give it a moment
        // to land on its real dispatcher before asserting the other side's view.
        withContext(Dispatchers.Default) { delay(200) }

        val observerOnB = FakeFriendCloud("uidB", store, clock)
        assertEquals(CardState.Gone, observerOnB.observeCard(aCardId).first())

        repoB.refreshFriends()
        assertTrue(repoB.friendsOfActiveProfile().isEmpty())
    }
}
