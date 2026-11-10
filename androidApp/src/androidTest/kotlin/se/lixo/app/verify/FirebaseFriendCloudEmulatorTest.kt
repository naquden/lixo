package se.lixo.app.verify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseApp
import dev.gitlive.firebase.app
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.Timestamp
import dev.gitlive.firebase.firestore.TimestampSerializer
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.initialize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import se.lixo.app.data.remote.CardState
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.social.FRIEND_CODE_ALPHABET
import se.lixo.app.domain.social.Nickname
import java.net.InetSocketAddress
import java.net.Socket

/**
 * doc/plans/030-friends-that-work.md: exercises the REAL production [FirebaseSyncService]
 * (implementing [se.lixo.app.data.remote.FriendCloud]) against local Firebase emulators (Auth +
 * Firestore, evaluating the real `firebase/firestore.rules`), not `FakeFriendCloud`. The fake
 * hand-mirrors the rules' access checks; only a real rules evaluation can catch a mismatch
 * between what the production client sends on the wire and what the rules actually accept (e.g.
 * the Firestore key-casing fix in `FirestoreAppearanceDoc`/`validEquipped`, H2 in
 * temp/verify/030_review.md).
 *
 * Three "devices" (A, B, C) are simulated as three secondary [FirebaseApp]s sharing the default
 * app's [dev.gitlive.firebase.FirebaseOptions] (same project), each bound to the local emulator
 * and each with its own anonymous Firebase Auth user -- mirroring three separate installs talking
 * to one backend. [FirebaseSyncService] normally binds to the global default
 * `Firebase.auth`/`Firebase.firestore`; its `authInstance`/`firestoreInstance` constructor
 * parameters (added for this test, default unchanged) let each simulated device get its own.
 *
 * Needs the emulators running first (see doc/friends.md "Testing"):
 * ```
 * firebase emulators:exec --project lixo-fd45d --only auth,firestore \
 *   "ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.FirebaseFriendCloudEmulatorTest"
 * ```
 * Skips (via [Assume], not a failure) if the emulator isn't reachable at `10.0.2.2:8080`, so a
 * normal `connectedDebugAndroidTest` run (no emulator started) is unaffected.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseFriendCloudEmulatorTest {

    companion object {
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_PORT = 9099

        @JvmStatic
        @BeforeClass
        fun checkEmulatorReachable() {
            val reachable = try {
                Socket().use { it.connect(InetSocketAddress(EMULATOR_HOST, FIRESTORE_PORT), 1500) }
                true
            } catch (_: Exception) {
                false
            }
            Assume.assumeTrue(
                "Firestore emulator not reachable at $EMULATOR_HOST:$FIRESTORE_PORT -- start it " +
                    "with `firebase emulators:exec --project lixo-fd45d --only auth,firestore " +
                    "\"...\"` (doc/friends.md 'Testing') before running this class.",
                reachable
            )
        }
    }

    /** Local mirror of production's private `FirestoreInviteDoc` shape, for test-only reads. */
    @Serializable
    private data class InviteMirror(
        val cardId: String,
        val ownerUid: String,
        @Serializable(with = TimestampSerializer::class) val expiresAt: Timestamp,
        val uses: Int
    )

    private val createdApps = mutableListOf<FirebaseApp>()

    /** One simulated "device": a secondary [FirebaseApp] on the same project, bound to the emulator. */
    private fun newDeviceApp(name: String): FirebaseApp {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val options = Firebase.app.options
        val app = Firebase.initialize(ctx, options, name)
        Firebase.auth(app).useEmulator(EMULATOR_HOST, AUTH_PORT)
        Firebase.firestore(app).useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        createdApps += app
        return app
    }

    @After
    fun tearDown(): Unit = runBlocking {
        createdApps.forEach { app ->
            try {
                Firebase.auth(app).signOut()
            } catch (_: Throwable) {
                // best-effort cleanup
            }
            try {
                app.delete()
            } catch (_: Throwable) {
                // best-effort cleanup
            }
        }
        createdApps.clear()
    }

    @Test
    fun realFirebaseSyncServiceAgainstEmulator(): Unit = runBlocking {
        val appA = newDeviceApp("friend-test-a-${System.nanoTime()}")
        val appB = newDeviceApp("friend-test-b-${System.nanoTime()}")
        val appC = newDeviceApp("friend-test-c-${System.nanoTime()}")

        val serviceA = FirebaseSyncService(
            isLocalOnlyMode = false,
            explicitAuth = Firebase.auth(appA),
            explicitFirestore = Firebase.firestore(appA)
        )
        val serviceB = FirebaseSyncService(
            isLocalOnlyMode = false,
            explicitAuth = Firebase.auth(appB),
            explicitFirestore = Firebase.firestore(appB)
        )
        val serviceC = FirebaseSyncService(
            isLocalOnlyMode = false,
            explicitAuth = Firebase.auth(appC),
            explicitFirestore = Firebase.firestore(appC)
        )

        val uidA = serviceA.ensureSignedIn().getOrThrow()
        val uidB = serviceB.ensureSignedIn().getOrThrow()
        val uidC = serviceC.ensureSignedIn().getOrThrow()
        assertTrue(uidA.isNotBlank() && uidB.isNotBlank() && uidC.isNotBlank() && uidA != uidB && uidB != uidC)

        // 1. A upsertMyCard with a real appearance with >=2 equipped slots (real serializer path).
        val appearanceA = AvatarAppearance(
            avatarId = "avatar_cat",
            tintHex = 0xFF00FFL,
            equipped = mapOf(
                EquipmentSlot.HAT to "item_hat_starter_explorer",
                EquipmentSlot.HAND_RIGHT to "item_hand_right_math_sword_1"
            )
        )
        val nickA = Nickname(adj = 1, animal = 2, num = 42)
        val cardIdA = serviceA.upsertMyCard(null, nickA, appearanceA).getOrThrow()
        assertEquals("Firestore auto doc id should be 20 chars: $cardIdA", 20, cardIdA.length)

        // 2. B upsertMyCard.
        val appearanceB = AvatarAppearance(
            avatarId = "avatar_cat",
            equipped = mapOf(EquipmentSlot.TAIL to "item_tail_regnbagssvans_skogsgron")
        )
        val nickB = Nickname(adj = 3, animal = 4, num = 7)
        val cardIdB = serviceB.upsertMyCard(null, nickB, appearanceB).getOrThrow()
        assertEquals(20, cardIdB.length)

        // 3. A createInvite: code matches the alphabet, expiresAt <= now + 10 min.
        val invite = serviceA.createInvite(cardIdA).getOrThrow()
        assertEquals(8, invite.code.length)
        assertTrue("code '${invite.code}' not in alphabet", invite.code.all { it in FRIEND_CODE_ALPHABET })
        val nowAtCreate = System.currentTimeMillis()
        assertTrue(
            "expiresAt ${invite.expiresAtEpochMillis} should be <= now+10min ($nowAtCreate)",
            invite.expiresAtEpochMillis <= nowAtCreate + 10 * 60 * 1000L
        )

        // 4. B redeemInvite(code, bCardId) -> success, A's card with equipped decoded == A's.
        val redeemedByB = serviceB.redeemInvite(invite.code, cardIdB).getOrThrow()
        assertEquals(cardIdA, redeemedByB.cardId)
        assertEquals(uidA, redeemedByB.ownerUid)
        assertEquals(nickA, redeemedByB.nick)
        assertEquals(appearanceA.equipped, redeemedByB.appearance.equipped)
        assertEquals(appearanceA.avatarId, redeemedByB.appearance.avatarId)

        // 5. A listMyReaders contains B with readerCardId = bCardId.
        val readersOfA = serviceA.listMyReaders(cardIdA).getOrThrow()
        assertTrue("readers=$readersOfA", readersOfA.any { it.readerUid == uidB && it.readerCardId == cardIdB })

        // 6. A observeCard(bCardId) -> Present (the reciprocal reader doc B's redeem created).
        val aSeesB = withTimeout(10_000) {
            serviceA.observeCard(cardIdB).first { it !is CardState.Unknown }
        }
        assertTrue("expected Present, got $aSeesB", aSeesB is CardState.Present)
        assertEquals(cardIdB, (aSeesB as CardState.Present).card.cardId)

        // 7. B observeCard(aCardId) -> Present.
        val bSeesA = withTimeout(10_000) {
            serviceB.observeCard(cardIdA).first { it !is CardState.Unknown }
        }
        assertTrue("expected Present, got $bSeesA", bSeesA is CardState.Present)

        // 8. A updates equipped -> B's observeCard(aCardId) emits the new gear within 10s.
        val updatedAppearanceA = appearanceA.copy(
            equipped = appearanceA.equipped + (EquipmentSlot.SHOULDERS_BACK to "item_shoulders_back_sv_cape_1")
        )
        serviceA.upsertMyCard(cardIdA, nickA, updatedAppearanceA).getOrThrow()
        val bSeesUpdatedA = withTimeout(10_000) {
            serviceB.observeCard(cardIdA).first {
                it is CardState.Present && it.card.appearance.equipped.containsKey(EquipmentSlot.SHOULDERS_BACK)
            }
        }
        assertEquals(
            updatedAppearanceA.equipped,
            (bSeesUpdatedA as CardState.Present).card.appearance.equipped
        )

        // 9. B redeems again -> fails with ALREADY_FRIEND (mapped error), not a crash.
        val secondRedeemByB = serviceB.redeemInvite(invite.code, cardIdB)
        assertTrue(secondRedeemByB.isFailure)
        val secondRedeemError = secondRedeemByB.exceptionOrNull()
        assertTrue(
            "expected ALREADY_FRIEND, got $secondRedeemError",
            secondRedeemError is AddFriendException && secondRedeemError.reason == AddFriendError.ALREADY_FRIEND
        )

        // 10. Third user C redeems the same code -> success, uses == 2 (read raw via A's firestore).
        val cardIdCFirst = serviceC.upsertMyCard(
            null,
            Nickname(adj = 5, animal = 6, num = 9),
            AvatarAppearance(avatarId = "avatar_cat")
        ).getOrThrow()
        val redeemedByC = serviceC.redeemInvite(invite.code, cardIdCFirst)
        assertTrue("C's redeem should succeed: ${redeemedByC.exceptionOrNull()}", redeemedByC.isSuccess)
        val inviteDocAfterC = Firebase.firestore(appA).collection("friend_invites").document(invite.code)
            .get().data<InviteMirror>()
        assertEquals("expected uses==2 after B and C redeemed", 2, inviteDocAfterC.uses)

        // 11. A removeFriend(B) -> B's observeCard(aCardId) emits Gone (not exception, not Unknown) within 10s.
        serviceA.removeFriend(cardIdA, uidB, cardIdB).getOrThrow()
        val bSeesARemoved = withTimeout(10_000) {
            serviceB.observeCard(cardIdA).first { it is CardState.Gone }
        }
        assertEquals(CardState.Gone, bSeesARemoved)

        // 12. A deleteInvite.
        serviceA.deleteInvite(invite.code).getOrThrow()

        // 13. C tries a fresh redeem of the deleted code -> NOT_FOUND.
        val freshRedeemByC = serviceC.redeemInvite(invite.code, cardIdCFirst)
        assertTrue(freshRedeemByC.isFailure)
        val freshRedeemError = freshRedeemByC.exceptionOrNull()
        assertTrue(
            "expected NOT_FOUND after deleteInvite, got $freshRedeemError",
            freshRedeemError is AddFriendException && freshRedeemError.reason == AddFriendError.NOT_FOUND
        )

        // 14. A deleteMyCard -> card, readers and invites gone; verify via a would-be read that is
        // Gone. C still held a reader doc on A's card from step 10's redeem, so C's observeCard is
        // the right witness that the card (and its readers) are actually gone server-side.
        serviceA.deleteMyCard(cardIdA).getOrThrow()
        val cSeesADeleted = withTimeout(10_000) {
            serviceC.observeCard(cardIdA).first { it is CardState.Gone }
        }
        assertEquals(CardState.Gone, cSeesADeleted)
    }
}
