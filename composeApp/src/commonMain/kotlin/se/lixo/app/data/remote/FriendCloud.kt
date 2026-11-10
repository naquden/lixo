package se.lixo.app.data.remote

import kotlinx.coroutines.flow.Flow
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.social.Nickname

/** One `friend_cards/{cardId}` document (doc/plans/030 §2/§4). */
data class FriendCard(
    val cardId: String,
    val ownerUid: String,
    val nick: Nickname,
    val appearance: AvatarAppearance
)

/** A short-lived, multi-use invite (doc/plans/030 §2/§4): `friend_invites/{code}`. */
data class FriendInvite(
    val code: String,
    val cardId: String,
    val ownerUid: String,
    val expiresAtEpochMillis: Long,
    val uses: Int
)

/** One `friend_cards/{cardId}/readers/{readerUid}` presence row, as seen by the card's owner. */
data class ReaderRef(val readerUid: String, val readerCardId: String)

/**
 * Firestore auto-generated document ids are 20 chars from a 62-char alphanumeric alphabet (M1 in
 * temp/verify/030_review.md, matching `firestore.rules`' own `readerCardId.matches(...)` check).
 * A [ReaderRef.readerCardId] ultimately comes from another client's Firestore write and is
 * passed straight into `collection(...).document(cardId)`; a malformed value (e.g. containing
 * `/`) throws there, so every caller must check this first and skip the entry rather than crash.
 */
fun isValidFriendCardId(id: String): Boolean =
    id.length == 20 && id.all { it.isLetterOrDigit() }

/**
 * Tri-state result of observing a friend's card (H4/H5 in temp/verify/030_review.md). Before
 * this, [FriendCloud.observeCard] collapsed "the card is gone / I was unfriended" and "I'm
 * offline / the cached read was inconclusive / some other transient error" into the same `null`,
 * which made [se.lixo.app.data.repository.LixoRepository.refreshFriends] delete a friend (and
 * their private label) from Room the moment the device went offline. A friend may only be
 * removed on [Gone] -- a server-confirmed permission-denied/not-found -- never on [Unknown].
 */
sealed class CardState {
    data class Present(val card: FriendCard) : CardState()

    /** The card no longer exists, or I was unfriended (permission denied), confirmed by the server. */
    data object Gone : CardState()

    /** Offline, a from-cache non-existent snapshot, or any other inconclusive/transient error. */
    data object Unknown : CardState()
}

/**
 * Cloud operations for the friends feature (doc/plans/030 §4), kept separate from
 * [CloudSyncGateway] (which still owns [CloudSyncGateway.ensureSignedIn]) so
 * [se.lixo.app.data.repository.LixoRepository] can take a
 * `se.lixo.app.data.remote.fake.FakeFriendCloud` in tests while [FirebaseSyncService] implements
 * both interfaces in production. All uids are resolved internally from the signed-in Firebase
 * Auth user (or, for a fake, the uid the fake instance was constructed with) — callers never pass
 * one. Every method must map a thrown exception to `Result.failure`, never to a false success.
 */
interface FriendCloud {
    /** Creates (when [cardId] is null) or overwrites my own card. Returns the card id. */
    suspend fun upsertMyCard(cardId: String?, nick: Nickname, appearance: AvatarAppearance): Result<String>

    /** A fresh 10-minute, multi-use (<=30) invite for [cardId], which I must own. */
    suspend fun createInvite(cardId: String): Result<FriendInvite>

    /** "Stoppa koden": deletes the invite early. */
    suspend fun deleteInvite(code: String): Result<Unit>

    /**
     * Redeems [code] for my own [myCardId], making the friendship mutual immediately (no accept
     * step). Fails with [se.lixo.app.domain.model.AddFriendError.NOT_FOUND] /
     * `EXPIRED` / `SELF` / `FULL` / `ALREADY_FRIEND` as appropriate; never a false success.
     */
    suspend fun redeemInvite(code: String, myCardId: String): Result<FriendCard>

    /** The current readers of my own [myCardId] (people who redeemed my invite). */
    suspend fun listMyReaders(myCardId: String): Result<List<ReaderRef>>

    /**
     * Live updates to the readers of my own [myCardId], for the "Nya kompisar" list. Never
     * throws: an offline/transient error is swallowed to an empty list rather than crashing the
     * collector (H4 in temp/verify/030_review.md).
     */
    fun observeMyReaders(myCardId: String): Flow<List<ReaderRef>>

    /**
     * Live updates to [cardId] as a [CardState]: [CardState.Present] while readable,
     * [CardState.Gone] once the server confirms it's deleted or access was revoked, and
     * [CardState.Unknown] for anything inconclusive (offline, from-cache miss, other error) --
     * never throws (H4/H5 in temp/verify/030_review.md).
     */
    fun observeCard(cardId: String): Flow<CardState>

    /** Deletes both reader docs between my card ([myCardId]) and the friend's ([friendCardId]). */
    suspend fun removeFriend(myCardId: String, friendUid: String, friendCardId: String): Result<Unit>

    /** Deletes my card, its readers and any invites pointing at it. */
    suspend fun deleteMyCard(cardId: String): Result<Unit>
}
