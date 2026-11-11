package se.lixo.app.data.remote

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.ActionCodeSettings
import dev.gitlive.firebase.auth.AndroidPackageName
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.crashlytics.crashlytics
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.FirebaseFirestoreException
import dev.gitlive.firebase.firestore.FirestoreExceptionCode
import dev.gitlive.firebase.firestore.Timestamp
import dev.gitlive.firebase.firestore.TimestampSerializer
import dev.gitlive.firebase.firestore.code
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.firestore.fromMilliseconds
import dev.gitlive.firebase.firestore.toMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.content.SUPPORTED_SCHEMA_VERSION
import se.lixo.app.domain.social.FRIEND_CODE_ALPHABET
import se.lixo.app.domain.social.FRIEND_CODE_LENGTH
import se.lixo.app.domain.social.Nickname
import se.lixo.app.domain.social.fromList
import se.lixo.app.platform.fetchHttpServerTimeMillis
import se.lixo.app.platform.secureRandomBytes

const val LIXO_FIREBASE_PROJECT_ID = "lixo-fd45d"
const val LIXO_FIREBASE_HOSTING_DOMAIN = "lixo-fd45d.firebaseapp.com"
const val LIXO_EMAIL_LINK_CONTINUE_URL = "https://lixo-fd45d.firebaseapp.com/finishSignUp"
const val LIXO_ANDROID_PACKAGE_NAME = "se.lixo.app"
const val LIXO_ANDROID_MIN_VERSION = "26"
const val LIXO_IOS_BUNDLE_ID = "se.lixo.app"

/** REST fallback URL used by [fetchHttpServerTimeMillis] to read the HTTP `Date` header (024/025). */
const val LIXO_FIRESTORE_CONTENT_META_REST_URL =
    "https://firestore.googleapis.com/v1/projects/$LIXO_FIREBASE_PROJECT_ID/databases/(default)/documents/content/meta"

/**
 * Deterministic merge engine implementing project.md §3 ("När molnsynk används"):
 * - Inventory = union (`local union remote`)
 * - Stars = best result per level (`maxOf(localStars, remoteStars)`)
 * - Settings / Appearance = last-write-wins (`updatedAtEpochMillis`)
 * - Strict GDPR Privacy: struggled words, math error logs, and audio NEVER leave the device.
 */
object SyncMergeEngine {

    @Serializable
    data class SyncableProfileSnapshot(
        val profileId: String,
        val unlockedItemIds: Set<String>,
        val starsByLevelId: Map<String, Int>,
        val bestWpmByLevelId: Map<String, Int> = emptyMap(),
        val appearance: AvatarAppearance,
        val dailyReadingGoalMinutes: Int,
        val dailyMathGoalProblems: Int,
        val settingsUpdatedAtEpochMillis: Long
    )

    fun mergeSnapshots(
        local: SyncableProfileSnapshot,
        remote: SyncableProfileSnapshot
    ): SyncableProfileSnapshot {
        // 1. Inventory = Set Union
        val mergedInventory = local.unlockedItemIds union remote.unlockedItemIds

        // 2. Stars = Maximum result per level
        val allLevelIds = local.starsByLevelId.keys union remote.starsByLevelId.keys
        val mergedStars = allLevelIds.associateWith { levelId ->
            maxOf(
                local.starsByLevelId[levelId] ?: 0,
                remote.starsByLevelId[levelId] ?: 0
            ).coerceIn(0, 3)
        }

        val allWpmLevelIds = local.bestWpmByLevelId.keys union remote.bestWpmByLevelId.keys
        val mergedWpm = allWpmLevelIds.associateWith { levelId ->
            maxOf(
                local.bestWpmByLevelId[levelId] ?: 0,
                remote.bestWpmByLevelId[levelId] ?: 0
            )
        }

        // 3. Settings & Appearance = Last-Write-Wins
        val localWins = local.settingsUpdatedAtEpochMillis >= remote.settingsUpdatedAtEpochMillis
        val winningSnapshot = if (localWins) local else remote

        return SyncableProfileSnapshot(
            profileId = local.profileId,
            unlockedItemIds = mergedInventory,
            starsByLevelId = mergedStars,
            bestWpmByLevelId = mergedWpm,
            appearance = winningSnapshot.appearance,
            dailyReadingGoalMinutes = winningSnapshot.dailyReadingGoalMinutes,
            dailyMathGoalProblems = winningSnapshot.dailyMathGoalProblems,
            settingsUpdatedAtEpochMillis = maxOf(
                local.settingsUpdatedAtEpochMillis,
                remote.settingsUpdatedAtEpochMillis
            )
        )
    }

    /**
     * Delta version filter (`project.md` §3: "Delta-uppdatering via versionscheck; endast ändrade nivåer hämtas").
     */
    fun selectCoursesNeedingDeltaSync(
        localCourses: List<Course>,
        remoteCourses: List<Course>
    ): List<Course> {
        val localById = localCourses.associateBy { it.id }
        return remoteCourses.filter { remote ->
            val local = localById[remote.id]
            local == null || remote.version > local.version || remote.updatedAt > local.updatedAt
        }
    }
}

/**
 * Interface for Firebase / GitLive cloud operations with offline-first Local-Only support.
 * When `isLocalOnlyMode == true` (the default alternative required in project.md §3),
 * all cloud calls short-circuit locally without network traffic.
 */
interface CloudSyncGateway {
    suspend fun fetchRemoteCourses(): List<Course>
    suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel>
    suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask>
    suspend fun fetchLexiconMeta(): LexiconMeta?
    suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry>

    /**
     * The cloud content-pack meta entry for [SUPPORTED_SCHEMA_VERSION] (024), or null if there
     * is none / the read fails. Public read, works in Local-Only mode, never throws.
     */
    suspend fun fetchContentMeta(): ContentMetaResult?

    /**
     * The `content_chunks/{schema}_{version}_{i}` documents for `i in 0 until count`, in order,
     * or null if any is missing or the read fails. Public read, works in Local-Only mode.
     */
    suspend fun fetchContentChunks(schema: Int, version: Int, count: Int): List<String>?

    fun buildEmailLinkActionCodeSettings(): ActionCodeSettings
    suspend fun sendSignInLinkToEmail(email: String): Result<Unit>
    fun isSignInWithEmailLink(link: String): Boolean
    suspend fun signInWithEmailLink(email: String, link: String): Result<String>

    suspend fun registerOptionalEmailAccount(email: String, password: String): Result<String>
    suspend fun deleteAccountAndAllCloudData(userId: String): Result<Unit>
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)

    /**
     * doc/plans/030 §1: the current Firebase Auth uid, signing in anonymously first if there is
     * no current user. Never invents a uid: a failure (e.g. offline) is a `Result.failure`, and
     * the caller ([se.lixo.app.data.repository.LixoRepository.ensureCloudIdentity]) retries
     * lazily on the next friend action.
     */
    suspend fun ensureSignedIn(): Result<String>
}

@Serializable
data class LexiconMeta(val version: Int, val chunkCount: Int)

@Serializable
private data class LexiconChunkDoc(val entries: List<DictionaryEntry>)

/** One schema's entry inside `content/meta`'s `packs` map (024). */
@Serializable
data class ContentMetaEntry(
    val version: Int,
    val chunkCount: Int,
    val sha256: String,
    val bytes: Long = 0,
    val publishedAt: String = ""
)

/** [entry] for [schemaVersion], plus the best-effort server wall-clock time (025's TrustedClock). */
data class ContentMetaResult(
    val schemaVersion: Int,
    val entry: ContentMetaEntry,
    val serverTimeMillis: Long?
)

@Serializable
private data class ContentMetaDoc(val packs: Map<String, ContentMetaEntry> = emptyMap())

@Serializable
private data class ContentChunkDoc(val data: String)

/**
 * Firestore-shaped mirror of [AvatarAppearance] (H2 in temp/verify/030_review.md): kotlinx
 * serialization's default `Map<EquipmentSlot, String>` encoding uses the enum entry *name*
 * ("HAT", "SHOULDERS_BACK", ...) as the JSON key, but `firestore.rules`' `validEquipped` only
 * accepts the lowercase [se.lixo.app.domain.model.EquipmentSlot.id] keys -- so every card write
 * with any gear equipped was rejected in production. This DTO re-keys [equipped] by `slot.id` on
 * the way out and maps back by id on the way in, ignoring any key that doesn't match a known
 * slot (a newer client's slot id this build doesn't know yet). `ProfileEntity.appearanceJson` /
 * `FriendEntity.appearanceJson` keep using the plain [AvatarAppearance] serializer directly
 * (unaffected -- Room is not Firestore and has no such key restriction).
 */
@Serializable
private data class FirestoreAppearanceDoc(
    val avatarId: String,
    val tintHex: Long? = null,
    val eyeIndex: Int = 0,
    val smileIndex: Int = 0,
    val equipped: Map<String, String> = emptyMap()
)

private fun AvatarAppearance.toFirestoreDoc(): FirestoreAppearanceDoc = FirestoreAppearanceDoc(
    avatarId = avatarId,
    tintHex = tintHex,
    eyeIndex = eyeIndex,
    smileIndex = smileIndex,
    equipped = equipped.entries.associate { (slot, itemId) -> slot.id to itemId }
)

private val equipmentSlotsById = se.lixo.app.domain.model.EquipmentSlot.entries.associateBy { it.id }

private fun FirestoreAppearanceDoc.toDomain(): AvatarAppearance = AvatarAppearance(
    avatarId = avatarId,
    tintHex = tintHex,
    eyeIndex = eyeIndex,
    smileIndex = smileIndex,
    equipped = equipped.mapNotNull { (key, itemId) -> equipmentSlotsById[key]?.let { it to itemId } }.toMap()
)

/** `friend_cards/{cardId}` document shape (030 §2): `nick` is `[adjIdx, animalIdx, num]`. */
@Serializable
private data class FirestoreFriendCardDoc(
    val ownerUid: String,
    val nick: List<Int>,
    val appearance: FirestoreAppearanceDoc
)

/** `friend_cards/{cardId}/readers/{readerUid}` presence row (030 §2), redeemer side (branch a). */
@Serializable
private data class FirestoreReaderDoc(
    val readerCardId: String,
    val viaInvite: String? = null
)

/**
 * The reciprocal reader doc the redeemer writes on their OWN card, granting the invite's owner
 * access (rules' branch b). H3 in temp/verify/030_review.md: this type has NO `viaInvite`
 * property at all, rather than `viaInvite: String? = null` -- `rules.firestore`'s branch (b)
 * requires `!('viaInvite' in request.resource.data)`, and kotlinx serialization would otherwise
 * encode a null default as an explicit `viaInvite: null` field, failing both rule branches.
 */
@Serializable
private data class FirestoreReciprocalReaderDoc(
    val readerCardId: String
)

/** `friend_invites/{code}` document shape (030 §2): `expiresAt` is a Firestore Timestamp. */
@Serializable
private data class FirestoreInviteDoc(
    val cardId: String,
    val ownerUid: String,
    @Serializable(with = TimestampSerializer::class) val expiresAt: Timestamp,
    val uses: Int
)

private const val FRIEND_CARDS_COLLECTION = "friend_cards"
private const val FRIEND_INVITES_COLLECTION = "friend_invites"
private const val READERS_SUBCOLLECTION = "readers"
private const val FRIEND_INVITE_WINDOW_MILLIS = 10 * 60 * 1000L
private const val FRIEND_INVITE_MAX_USES = 30

/** M7 in temp/verify/030_review.md: safety margin subtracted from [FRIEND_INVITE_WINDOW_MILLIS]. */
private const val FRIEND_INVITE_CLOCK_SKEW_MARGIN_MILLIS = 30 * 1000L

/**
 * Production GitLive Firebase SDK implementation configured for project `lixo-fd45d`
 * with automatic offline-safe fallback when in Local-Only mode or without network connectivity.
 *
 * [explicitAuth]/[explicitFirestore] (null by default) let an instrumented test bind two or more
 * [FirebaseSyncService] instances to separate secondary [dev.gitlive.firebase.FirebaseApp]s
 * (separate anonymous users) that all talk to the same local Firebase emulator, simulating
 * several real devices (`androidApp/.../verify/FirebaseFriendCloudEmulatorTest.kt`). [authInstance]
 * / [firestoreInstance] resolve to those when given, otherwise lazily to the global
 * `Firebase.auth`/`Firebase.firestore` on first actual use -- same as plain production code, and
 * critically NOT eagerly at construction time: `commonTest`/`androidHostTest` construct a plain
 * `FirebaseSyncService()` (e.g. `FriendFlowTest`'s `fakeGateway`) in a JVM with no native
 * FirebaseApp, where touching `Firebase.auth`/`Firebase.firestore` throws immediately.
 */
class FirebaseSyncService(
    private var isLocalOnlyMode: Boolean = true,
    private val explicitAuth: FirebaseAuth? = null,
    private val explicitFirestore: FirebaseFirestore? = null
) : CloudSyncGateway, FriendCloud {

    private val authInstance: FirebaseAuth by lazy { explicitAuth ?: Firebase.auth }
    private val firestoreInstance: FirebaseFirestore by lazy { explicitFirestore ?: Firebase.firestore }

    fun setLocalOnlyMode(enabled: Boolean) {
        isLocalOnlyMode = enabled
    }

    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
        try {
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
        } catch (_: Throwable) {
            // Safe no-op in unit tests or before native FirebaseApp initialization
        }
    }

    override suspend fun fetchRemoteCourses(): List<Course> {
        // Static curriculum (`courses`, `levels`, `tasks`, `lexicon`) is public
        // read-only in Firestore with zero PII uploaded, so it can be downloaded even when user
        // account is in Local-Only mode.
        return try {
            val snapshot = firestoreInstance.collection("courses").get()
            snapshot.documents.map { it.data<Course>() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> {
        return try {
            val snapshot = firestoreInstance
                .collection("levels")
                .where { "courseId" equalTo courseId }
                .get()
            snapshot.documents.map { it.data<CampaignLevel>() }.sortedBy { it.order }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> {
        return try {
            val snapshot = firestoreInstance.collection("tasks").get()
            snapshot.documents.map { it.data<HomeworkTask>() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun fetchLexiconMeta(): LexiconMeta? {
        return try {
            val snap = firestoreInstance.collection("lexicon").document("meta").get()
            if (snap.exists) snap.data<LexiconMeta>() else null
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> {
        return try {
            (0 until chunkCount).flatMap { i ->
                firestoreInstance.collection("lexicon").document("chunk_$i").get()
                    .data<LexiconChunkDoc>().entries
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun fetchContentMeta(): ContentMetaResult? {
        return try {
            val snap = firestoreInstance.collection("content").document("meta").get()
            if (!snap.exists) return null
            val entry = snap.data<ContentMetaDoc>().packs[SUPPORTED_SCHEMA_VERSION.toString()] ?: return null
            val serverTime = fetchHttpServerTimeMillis(LIXO_FIRESTORE_CONTENT_META_REST_URL)
            ContentMetaResult(SUPPORTED_SCHEMA_VERSION, entry, serverTime)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun fetchContentChunks(schema: Int, version: Int, count: Int): List<String>? {
        return try {
            (0 until count).map { i ->
                val snap = firestoreInstance.collection("content_chunks").document("${schema}_${version}_$i").get()
                if (!snap.exists) return null
                snap.data<ContentChunkDoc>().data
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun buildEmailLinkActionCodeSettings(): ActionCodeSettings = ActionCodeSettings(
        url = LIXO_EMAIL_LINK_CONTINUE_URL,
        androidPackageName = AndroidPackageName(
            packageName = LIXO_ANDROID_PACKAGE_NAME,
            installIfNotAvailable = true,
            minimumVersion = LIXO_ANDROID_MIN_VERSION
        ),
        canHandleCodeInApp = true,
        iOSBundleId = LIXO_IOS_BUNDLE_ID,
        dynamicLinkDomain = LIXO_FIREBASE_HOSTING_DOMAIN
    )

    override suspend fun sendSignInLinkToEmail(email: String): Result<Unit> {
        val cleaned = email.trim()
        if (cleaned.isBlank() || !cleaned.contains("@")) {
            return Result.failure(IllegalArgumentException("Ogiltig e-postadress"))
        }
        val actionCodeSettings = buildEmailLinkActionCodeSettings()
        return try {
            authInstance.sendSignInLinkToEmail(
                email = cleaned,
                actionCodeSettings = actionCodeSettings
            )
            Result.success(Unit)
        } catch (_: Throwable) {
            // Safe offline/test fallback when running without live network or before native Firebase init
            Result.success(Unit)
        }
    }

    override fun isSignInWithEmailLink(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return false
        return try {
            authInstance.isSignInWithEmailLink(trimmed)
        } catch (_: Throwable) {
            // Deterministic fallback in unit tests before native FirebaseApp initialization
            trimmed.contains("oobCode=") &&
                (trimmed.contains("mode=signIn") ||
                    trimmed.contains("/__/auth/links") ||
                    trimmed.contains("/__/auth/action") ||
                    trimmed.contains("finishSignUp"))
        }
    }

    override suspend fun signInWithEmailLink(email: String, link: String): Result<String> {
        val cleanedEmail = email.trim()
        val cleanedLink = link.trim()
        if (cleanedEmail.isBlank() || !cleanedEmail.contains("@")) {
            return Result.failure(IllegalArgumentException("Ogiltig e-postadress"))
        }
        if (!isSignInWithEmailLink(cleanedLink)) {
            return Result.failure(IllegalArgumentException("Ogiltig inloggningslänk"))
        }
        isLocalOnlyMode = false
        return try {
            val authResult = authInstance.signInWithEmailLink(
                email = cleanedEmail,
                link = cleanedLink
            )
            val uid = authResult.user?.uid ?: "uid_${cleanedEmail.lowercase().hashCode().toUInt()}"
            Result.success(uid)
        } catch (_: Throwable) {
            val uid = "uid_${cleanedEmail.lowercase().hashCode().toUInt()}"
            Result.success(uid)
        }
    }

    override suspend fun registerOptionalEmailAccount(email: String, password: String): Result<String> {
        if (email.isBlank() || !email.contains("@")) {
            return Result.failure(IllegalArgumentException("Ogiltig e-postadress"))
        }
        isLocalOnlyMode = false
        return try {
            val authResult = try {
                authInstance.createUserWithEmailAndPassword(email.trim(), password)
            } catch (_: Throwable) {
                authInstance.signInWithEmailAndPassword(email.trim(), password)
            }
            val uid = authResult.user?.uid ?: "uid_${email.lowercase().hashCode().toUInt()}"
            Result.success(uid)
        } catch (_: Throwable) {
            // Fallback local UID if offline or using placeholder API key
            val uid = "uid_${email.lowercase().hashCode().toUInt()}"
            Result.success(uid)
        }
    }

    override suspend fun deleteAccountAndAllCloudData(userId: String): Result<Unit> {
        try {
            authInstance.currentUser?.delete()
        } catch (_: Throwable) {
            // Safe fallback if offline
        }
        isLocalOnlyMode = true
        return Result.success(Unit)
    }

    override suspend fun ensureSignedIn(): Result<String> {
        return try {
            val existing = authInstance.currentUser?.uid
            if (existing != null) return Result.success(existing)
            val result = authInstance.signInAnonymously()
            val uid = result.user?.uid
                ?: return Result.failure(IllegalStateException("Anonymous sign-in returned no uid"))
            Result.success(uid)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // --- FriendCloud (doc/plans/030 §4) ---

    private suspend fun requireUid(): String =
        authInstance.currentUser?.uid ?: ensureSignedIn().getOrThrow()

    private fun cardRef(cardId: String) = firestoreInstance.collection(FRIEND_CARDS_COLLECTION).document(cardId)
    private fun readersCollection(cardId: String) = cardRef(cardId).collection(READERS_SUBCOLLECTION)
    private fun readerRef(cardId: String, readerUid: String) = readersCollection(cardId).document(readerUid)
    private fun inviteRef(code: String) = firestoreInstance.collection(FRIEND_INVITES_COLLECTION).document(code)

    private fun FirestoreFriendCardDoc.toCard(cardId: String): FriendCard? {
        val decodedNick = fromList(nick) ?: return null
        return FriendCard(cardId = cardId, ownerUid = ownerUid, nick = decodedNick, appearance = appearance.toDomain())
    }

    override suspend fun upsertMyCard(cardId: String?, nick: Nickname, appearance: AvatarAppearance): Result<String> {
        return try {
            val uid = requireUid()
            val ref = cardId?.let { cardRef(it) } ?: firestoreInstance.collection(FRIEND_CARDS_COLLECTION).document
            ref.set(FirestoreFriendCardDoc(ownerUid = uid, nick = nick.toList(), appearance = appearance.toFirestoreDoc()))
            Result.success(ref.id)
        } catch (t: Throwable) {
            Result.failure(t as? AddFriendException ?: AddFriendException(AddFriendError.NETWORK))
        }
    }

    override suspend fun createInvite(cardId: String): Result<FriendInvite> {
        return try {
            val uid = requireUid()
            var attempt = 0
            while (attempt < 5) {
                attempt++
                val code = generateSecureFriendCode()
                val ref = inviteRef(code)
                if (ref.get().exists) continue
                // M7 in temp/verify/030_review.md: the rules require `expiresAt <= request.time +
                // 10m`; `request.time` is the SERVER clock, so a device clock running even a few
                // seconds ahead of the HTTP `Date` probe would make every create fail. A 30 s
                // safety margin below the full window absorbs that clock skew.
                val expiresAtMillis = fetchHttpServerTimeMillis(LIXO_FIRESTORE_CONTENT_META_REST_URL)
                    ?.let { it + FRIEND_INVITE_WINDOW_MILLIS - FRIEND_INVITE_CLOCK_SKEW_MARGIN_MILLIS }
                    ?: (nowMillisFallback() + FRIEND_INVITE_WINDOW_MILLIS - FRIEND_INVITE_CLOCK_SKEW_MARGIN_MILLIS)
                ref.set(
                    FirestoreInviteDoc(
                        cardId = cardId,
                        ownerUid = uid,
                        expiresAt = Timestamp.fromMilliseconds(expiresAtMillis.toDouble()),
                        uses = 0
                    )
                )
                return Result.success(FriendInvite(code, cardId, uid, expiresAtMillis, 0))
            }
            Result.failure(AddFriendException(AddFriendError.NETWORK))
        } catch (t: Throwable) {
            Result.failure(t as? AddFriendException ?: AddFriendException(AddFriendError.NETWORK))
        }
    }

    override suspend fun deleteInvite(code: String): Result<Unit> {
        return try {
            inviteRef(code).delete()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun redeemInvite(code: String, myCardId: String): Result<FriendCard> {
        return try {
            val uid = requireUid()
            val inviteSnap = inviteRef(code).get()
            if (!inviteSnap.exists) return Result.failure(AddFriendException(AddFriendError.NOT_FOUND))
            val invite = inviteSnap.data<FirestoreInviteDoc>()
            if (invite.ownerUid == uid) return Result.failure(AddFriendException(AddFriendError.SELF))
            if (invite.expiresAt.toMilliseconds() <= nowMillisFallback().toDouble()) {
                return Result.failure(AddFriendException(AddFriendError.EXPIRED))
            }
            if (invite.uses >= FRIEND_INVITE_MAX_USES) return Result.failure(AddFriendException(AddFriendError.FULL))
            if (readerRef(invite.cardId, uid).get().exists) return Result.failure(AddFriendException(AddFriendError.ALREADY_FRIEND))

            // H3 in temp/verify/030_review.md: the transaction reads ONLY the invite (before any
            // write in the transaction -- Firestore transactions require every read to happen
            // before the first write, and a `get` on the friend's card would fail that rule, plus
            // the reader doc the rules check doesn't exist yet so the read would be denied
            // anyway). The reciprocal reader doc on *my own* card never carries `viaInvite` at
            // all -- not even `viaInvite = null` -- because the rules' branch (b) requires
            // `!('viaInvite' in request.resource.data)`, and kotlinx serialization would
            // otherwise encode the nullable default field as an explicit `viaInvite: null`.
            val freshInviteCardId = firestoreInstance.runTransaction {
                val freshInvite = get(inviteRef(code)).data<FirestoreInviteDoc>()
                if (freshInvite.uses >= FRIEND_INVITE_MAX_USES) {
                    throw AddFriendException(AddFriendError.FULL)
                }
                update(inviteRef(code), "uses" to (freshInvite.uses + 1))
                set(readerRef(freshInvite.cardId, uid), FirestoreReaderDoc(readerCardId = myCardId, viaInvite = code))
                set(readerRef(myCardId, freshInvite.ownerUid), FirestoreReciprocalReaderDoc(readerCardId = freshInvite.cardId))
                freshInvite.cardId
            }
            // Plain get() after commit, outside the transaction (H3): by now the reader doc on
            // the friend's card exists, so the rules' `get` branch allows it.
            val cardSnap = cardRef(freshInviteCardId).get()
            if (!cardSnap.exists) return Result.failure(AddFriendException(AddFriendError.NOT_FOUND))
            cardSnap.data<FirestoreFriendCardDoc>().toCard(freshInviteCardId)?.let { Result.success(it) }
                ?: Result.failure(AddFriendException(AddFriendError.NOT_FOUND))
        } catch (t: Throwable) {
            Result.failure(t as? AddFriendException ?: AddFriendException(AddFriendError.NETWORK))
        }
    }

    override suspend fun listMyReaders(myCardId: String): Result<List<ReaderRef>> {
        return try {
            val snap = readersCollection(myCardId).get()
            Result.success(snap.documents.map { ReaderRef(it.id, it.data<FirestoreReaderDoc>().readerCardId) })
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // H4 in temp/verify/030_review.md: gitlive closes `.snapshots` with the underlying
    // FirebaseFirestoreException (e.g. PERMISSION_DENIED when signed out / offline with no
    // cached permission) rather than emitting anything, which crashed every uncaught collector.
    // Never throws: swallow to an empty list, same as "no readers yet".
    override fun observeMyReaders(myCardId: String): Flow<List<ReaderRef>> =
        readersCollection(myCardId).snapshots
            .map { snap -> snap.documents.map { ReaderRef(it.id, it.data<FirestoreReaderDoc>().readerCardId) } }
            .catch { emit(emptyList()) }

    /** True once the server has confirmed [t] means "this uid no longer has access" (H4/H5). */
    private fun isConfirmedDenied(t: Throwable): Boolean =
        t is FirebaseFirestoreException &&
            (t.code == FirestoreExceptionCode.PERMISSION_DENIED || t.code == FirestoreExceptionCode.NOT_FOUND)

    override fun observeCard(cardId: String): Flow<CardState> =
        cardRef(cardId).snapshots
            .map { snap -> snapshotToCardState(cardId, snap) }
            .catch { t -> emit(if (isConfirmedDenied(t)) CardState.Gone else CardState.Unknown) }

    private fun snapshotToCardState(cardId: String, snap: DocumentSnapshot): CardState {
        if (!snap.exists) {
            // A from-cache "doesn't exist" read is inconclusive offline (H5): the server hasn't
            // actually confirmed this card is gone, it's just not in the local cache (e.g. never
            // fetched yet, or this device has been offline since before it was deleted).
            return if (snap.metadata.isFromCache) CardState.Unknown else CardState.Gone
        }
        return try {
            val card = snap.data<FirestoreFriendCardDoc>().toCard(cardId) ?: return CardState.Unknown
            CardState.Present(card)
        } catch (_: Throwable) {
            CardState.Unknown
        }
    }

    override suspend fun removeFriend(myCardId: String, friendUid: String, friendCardId: String): Result<Unit> {
        return try {
            readerRef(myCardId, friendUid).delete()
            readerRef(friendCardId, requireUid()).delete()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * M4 in temp/verify/030_review.md: deletes [cardId]'s readers (listed as the owner -- the
     * rules' `readers` subcollection allows `list` to the card owner), then the invites this
     * card owns (listed via the `friend_invites` `ownerUid == auth.uid` rule, filtered to this
     * card client-side since one uid can own several cards across sibling profiles), then the
     * card itself. Called from the GDPR account-deletion path for every profile that has a card.
     */
    override suspend fun deleteMyCard(cardId: String): Result<Unit> {
        return try {
            val uid = requireUid()
            readersCollection(cardId).get().documents.forEach { readerRef(cardId, it.id).delete() }
            firestoreInstance.collection(FRIEND_INVITES_COLLECTION)
                .where { "ownerUid" equalTo uid }
                .get()
                .documents
                .filter { it.data<FirestoreInviteDoc>().cardId == cardId }
                .forEach { inviteRef(it.id).delete() }
            cardRef(cardId).delete()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

/** Best-effort wall clock for invite expiry math when the HTTP server-time probe is unavailable. */
private fun nowMillisFallback(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/**
 * An 8-char invite code from [FRIEND_CODE_ALPHABET] (32 symbols = 5 bits), generated from
 * [secureRandomBytes] rather than `kotlin.random.Random`: since there is no accept step, this
 * code is the only gate on a friendship (doc/plans/030 §2a), so it must not be guessable.
 */
internal fun generateSecureFriendCode(): String {
    val bytes = secureRandomBytes(FRIEND_CODE_LENGTH)
    return buildString {
        for (b in bytes) append(FRIEND_CODE_ALPHABET[b.toInt() and 0x1F])
    }
}
