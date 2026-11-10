package se.lixo.app.data.remote.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import se.lixo.app.data.remote.CardState
import se.lixo.app.data.remote.FriendCard
import se.lixo.app.data.remote.FriendCloud
import se.lixo.app.data.remote.FriendInvite
import se.lixo.app.data.remote.ReaderRef
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.social.FRIEND_CODE_ALPHABET
import se.lixo.app.domain.social.FRIEND_CODE_LENGTH
import se.lixo.app.domain.social.Nickname

private const val FRIEND_INVITE_WINDOW_MILLIS = 10 * 60 * 1000L
private const val FRIEND_INVITE_MAX_USES = 30

internal data class FriendCardRecord(val ownerUid: String, var nick: Nickname, var appearance: AvatarAppearance)

internal data class InviteRecord(val cardId: String, val ownerUid: String, val expiresAtEpochMillis: Long, var uses: Int)

/**
 * The in-memory backing store shared by every [FakeFriendCloud] "device" in a test (doc/plans/030
 * §8): construct one [FakeFriendCloudStore] per test and pass it to a [FakeFriendCloud] per
 * simulated device/uid, so e.g. `FakeFriendCloud("uidA", store)` and `FakeFriendCloud("uidB",
 * store)` see each other's cards/invites exactly like two phones talking to the same Firestore
 * project. [recordedWrites] captures every write (never including [se.lixo.app.domain.model.FriendProfile.label],
 * which this layer never even sees) for the label-privacy unit test.
 */
class FakeFriendCloudStore {
    internal val cardsByCardId = mutableMapOf<String, FriendCardRecord>()
    internal val readersByCardId = mutableMapOf<String, MutableMap<String, ReaderRef>>()
    internal val invitesByCode = mutableMapOf<String, InviteRecord>()

    /** Every write this store has ever recorded, verbatim, for the label-privacy unit test. */
    val recordedWrites: MutableList<String> = mutableListOf()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 256)
    internal val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    internal fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    private var cardIdCounter = 0

    /**
     * doc/plans/030 review M1: production card ids are 20-char Firestore auto-ids
     * (`^[A-Za-z0-9]{20}$`, see [se.lixo.app.data.remote.isValidFriendCardId] / `firestore.rules`'
     * `validCardId`). The fake must produce ids in that SAME shape -- a fake id containing `_`
     * (the old `"card_${uid}_$n"` shape) would get silently filtered out by that validation in
     * [se.lixo.app.data.repository.LixoRepository], hiding real bugs instead of exercising them.
     */
    internal fun nextCardId(uid: String): String {
        val sanitizedUid = uid.filter { it.isLetterOrDigit() }.ifEmpty { "uid" }
        val suffix = (cardIdCounter++).toString()
        return ("card$sanitizedUid$suffix").padEnd(20, '0').take(20)
    }

    // --- Test-only public accessors (doc/plans/030 review M4/M8) -------------------------------
    // `internal` members above aren't guaranteed visible from every test source set, so these
    // small public wrappers let a unit test assert on the store's shape without reaching into
    // production-only internals.

    /** Number of `friend_cards` documents currently in the store. */
    fun cardCountForTest(): Int = cardsByCardId.size

    fun hasCardForTest(cardId: String): Boolean = cardsByCardId.containsKey(cardId)

    /** True if [cardId] has at least one `readers/{uid}` row. */
    fun hasAnyReaderForTest(cardId: String): Boolean = readersByCardId[cardId].orEmpty().isNotEmpty()

    /** True if any `friend_invites/{code}` document still points at [cardId]. */
    fun hasAnyInviteForTest(cardId: String): Boolean = invitesByCode.values.any { it.cardId == cardId }
}

/**
 * A [FriendCloud] fake bound to one uid ("device"), backed by a [FakeFriendCloudStore] shared
 * across every device in the test (doc/plans/030 §8). Enforces the same access checks as
 * `firebase/firestore.rules`: [observeCard] emits `null` once access is denied, [redeemInvite]
 * denies a second redeem by the same uid (`ALREADY_FRIEND`), caps uses at 30 (`FULL`), and denies
 * an expired invite (`EXPIRED`) using [clockMillis] rather than a real wall clock, so tests can
 * fast-forward time deterministically.
 */
class FakeFriendCloud(
    private val myUid: String,
    private val store: FakeFriendCloudStore = FakeFriendCloudStore(),
    private val clockMillis: () -> Long = { 0L }
) : FriendCloud {

    /**
     * Set by a test to simulate this "device" being offline (H4/H5 in temp/verify/030_review.md):
     * while `true`, [observeCard] emits [CardState.Unknown] regardless of real access, instead of
     * [CardState.Gone]/[CardState.Present] -- modelling a from-cache miss / no-network read, so a
     * test can assert that going offline does NOT drop a friend from Room (only a server-confirmed
     * [CardState.Gone] may).
     */
    var offline: Boolean = false

    private fun hasAccess(cardId: String): Boolean {
        val card = store.cardsByCardId[cardId] ?: return false
        if (card.ownerUid == myUid) return true
        return store.readersByCardId[cardId]?.containsKey(myUid) == true
    }

    private fun readCardIfAllowed(cardId: String): FriendCard? {
        if (!hasAccess(cardId)) return null
        val card = store.cardsByCardId[cardId] ?: return null
        return FriendCard(cardId = cardId, ownerUid = card.ownerUid, nick = card.nick, appearance = card.appearance)
    }

    private fun cardState(cardId: String): CardState {
        if (offline) return CardState.Unknown
        val card = readCardIfAllowed(cardId) ?: return CardState.Gone
        return CardState.Present(card)
    }

    override suspend fun upsertMyCard(cardId: String?, nick: Nickname, appearance: AvatarAppearance): Result<String> {
        val id = cardId ?: store.nextCardId(myUid)
        val existing = store.cardsByCardId[id]
        if (existing != null && existing.ownerUid != myUid) {
            return Result.failure(IllegalStateException("Card $id is not owned by $myUid"))
        }
        store.cardsByCardId[id] = FriendCardRecord(ownerUid = myUid, nick = nick, appearance = appearance)
        store.recordedWrites += "upsertCard cardId=$id ownerUid=$myUid nick=${nick.toList()} avatarId=${appearance.avatarId} equipped=${appearance.equipped}"
        store.notifyChanged()
        return Result.success(id)
    }

    override suspend fun createInvite(cardId: String): Result<FriendInvite> {
        val card = store.cardsByCardId[cardId]
        if (card == null || card.ownerUid != myUid) {
            return Result.failure(IllegalStateException("Card $cardId is not owned by $myUid"))
        }
        var code: String
        do {
            code = (1..FRIEND_CODE_LENGTH).map { FRIEND_CODE_ALPHABET.random(Random) }.joinToString("")
        } while (store.invitesByCode.containsKey(code))
        val expiresAt = clockMillis() + FRIEND_INVITE_WINDOW_MILLIS
        store.invitesByCode[code] = InviteRecord(cardId = cardId, ownerUid = myUid, expiresAtEpochMillis = expiresAt, uses = 0)
        store.recordedWrites += "createInvite code=$code cardId=$cardId ownerUid=$myUid expiresAt=$expiresAt"
        return Result.success(FriendInvite(code, cardId, myUid, expiresAt, 0))
    }

    override suspend fun deleteInvite(code: String): Result<Unit> {
        val invite = store.invitesByCode[code] ?: return Result.success(Unit)
        if (invite.ownerUid != myUid) return Result.failure(IllegalStateException("Invite $code is not owned by $myUid"))
        store.invitesByCode.remove(code)
        store.recordedWrites += "deleteInvite code=$code"
        return Result.success(Unit)
    }

    override suspend fun redeemInvite(code: String, myCardId: String): Result<FriendCard> {
        val invite = store.invitesByCode[code] ?: return Result.failure(AddFriendException(AddFriendError.NOT_FOUND))
        if (invite.ownerUid == myUid) return Result.failure(AddFriendException(AddFriendError.SELF))
        if (clockMillis() >= invite.expiresAtEpochMillis) return Result.failure(AddFriendException(AddFriendError.EXPIRED))
        val existingReader = store.readersByCardId[invite.cardId]?.get(myUid)
        if (existingReader != null) return Result.failure(AddFriendException(AddFriendError.ALREADY_FRIEND))
        if (invite.uses >= FRIEND_INVITE_MAX_USES) return Result.failure(AddFriendException(AddFriendError.FULL))

        invite.uses += 1
        store.readersByCardId.getOrPut(invite.cardId) { mutableMapOf() }[myUid] = ReaderRef(myUid, myCardId)
        store.readersByCardId.getOrPut(myCardId) { mutableMapOf() }[invite.ownerUid] = ReaderRef(invite.ownerUid, invite.cardId)
        store.recordedWrites += "redeem code=$code cardId=${invite.cardId} readerUid=$myUid readerCardId=$myCardId uses=${invite.uses}"
        store.notifyChanged()

        val card = store.cardsByCardId[invite.cardId] ?: return Result.failure(AddFriendException(AddFriendError.NOT_FOUND))
        return Result.success(FriendCard(invite.cardId, card.ownerUid, card.nick, card.appearance))
    }

    override suspend fun listMyReaders(myCardId: String): Result<List<ReaderRef>> {
        val card = store.cardsByCardId[myCardId]
        if (card == null || card.ownerUid != myUid) return Result.success(emptyList())
        return Result.success(store.readersByCardId[myCardId]?.values?.toList().orEmpty())
    }

    override fun observeMyReaders(myCardId: String): Flow<List<ReaderRef>> = flow {
        emit(listMyReaders(myCardId).getOrDefault(emptyList()))
        store.changes.collect { emit(listMyReaders(myCardId).getOrDefault(emptyList())) }
    }

    override fun observeCard(cardId: String): Flow<CardState> = flow {
        emit(cardState(cardId))
        store.changes.collect { emit(cardState(cardId)) }
    }

    override suspend fun removeFriend(myCardId: String, friendUid: String, friendCardId: String): Result<Unit> {
        store.readersByCardId[myCardId]?.remove(friendUid)
        store.readersByCardId[friendCardId]?.remove(myUid)
        store.recordedWrites += "removeFriend myCardId=$myCardId friendUid=$friendUid friendCardId=$friendCardId"
        store.notifyChanged()
        return Result.success(Unit)
    }

    override suspend fun deleteMyCard(cardId: String): Result<Unit> {
        val card = store.cardsByCardId[cardId] ?: return Result.success(Unit)
        if (card.ownerUid != myUid) return Result.failure(IllegalStateException("Card $cardId is not owned by $myUid"))
        store.cardsByCardId.remove(cardId)
        store.readersByCardId.remove(cardId)
        store.invitesByCode.entries.removeAll { it.value.cardId == cardId }
        store.recordedWrites += "deleteCard cardId=$cardId"
        store.notifyChanged()
        return Result.success(Unit)
    }
}
