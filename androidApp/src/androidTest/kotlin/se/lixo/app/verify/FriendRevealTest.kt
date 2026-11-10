package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.feedback.LocalReducedMotion
import se.lixo.app.ui.theme.LixoStrings

/**
 * doc/plans/030 §7 "kompis-kistan", verified against a real [se.lixo.app.data.repository.LixoRepository]
 * + Room + [FakeFriendCloudStore]: the reveal plays on the scanner side right after a redeem
 * (before the "Vad heter din kompis?" name step), and on the inviter side -- queued in order, one
 * per newly-seen friend -- when the village is opened after friends were added while it was shut.
 */
class FriendRevealTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {
        }
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots/friends").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** `B redeems A's code -> the stage shows "Ny kompis!" and A's nickname; +2.5s -> label step.` */
    @Test
    fun scannerSideRedeem_playsReveal_thenShowsNameStep() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_reveal1", store)
        val repoB = friendRepoFor("uidB_reveal1", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        val aNickText = "@" + repoA.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner(), prefilledFriendCode = invite.code)
        }
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("friend_reveal") and hasText(aNickText)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("friend_reveal")
            .assert(hasText(LixoStrings.FRIEND_REVEAL_TITLE))
            .assert(hasText(aNickText))
        compose.onNodeWithTag("confetti", useUnmergedTree = true).assertExists()
        shot("reveal_scanner_side_popped")

        // Auto-finishes after 2.5s (doc/plans/030 §7), moving on to "Vad heter din kompis?".
        compose.waitUntil(6_000) {
            compose.onAllNodesWithText(LixoStrings.FRIENDS_NAME_STEP_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(LixoStrings.FRIENDS_NAME_STEP_TITLE).assertExists()
        shot("reveal_name_step")
    }

    /** `A has 2 friends added while away -> open the village -> the reveal plays twice, in order.` */
    @Test
    fun inviterSide_twoFriendsAddedWhileAway_revealPlaysTwiceInOrder_thenBadgeGone() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_reveal2", store)
        val repoB = friendRepoFor("uidB_reveal2", store)
        val repoC = friendRepoFor("uidC_reveal2", store)

        // Both B and C redeem A's code while A's village is not open at all (no refreshFriends()
        // call has happened yet on A's side): the inviter picks them up only once it opens.
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        runBlocking {
            repoB.addFriendByCode(invite.code, fromQr = false).getOrThrow()
            repoC.addFriendByCode(invite.code, fromQr = false).getOrThrow()
        }

        val bNick = "@" + repoB.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)
        val cNick = "@" + repoC.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)

        // "Opening the village": FriendVillageTestHost calls refreshFriends() on first composition,
        // exactly like LixoApp does when the Avatar tab opens (doc/plans/030 §4).
        compose.setContent {
            FriendVillageTestHost(repository = repoA, qrScanner = FakeQrScanner())
        }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("friend_reveal") and hasText(bNick)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("friend_reveal")
            .assert(hasText(LixoStrings.FRIEND_REVEAL_TITLE))
            .assert(hasText(bNick))
        shot("reveal_inviter_side_first")

        // First reveal auto-finishes after 2.5s and the second one starts immediately.
        compose.waitUntil(6_000) {
            compose.onAllNodes(hasTestTag("friend_reveal") and hasText(cNick)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("friend_reveal")
            .assert(hasText(LixoStrings.FRIEND_REVEAL_TITLE))
            .assert(hasText(cNick))
        shot("reveal_inviter_side_second")

        // Once both are revealed the "+" plot's unseen badge disappears.
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("friend_reveal").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(LixoStrings.friendsUnrevealedBadge(1)).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.friendsUnrevealedBadge(2)).assertDoesNotExist()
    }

    /** Captures frames mid-animation as visual evidence of the reveal (doc/plans/030 §7). */
    @Test
    fun scannerSideRedeem_capturesFramesDuringReveal() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_reveal3", store)
        val repoB = friendRepoFor("uidB_reveal3", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner(), prefilledFriendCode = invite.code)
        }
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()

        // The reveal's phases are driven by real (not virtual) coroutine delays (doc/plans/030 §7:
        // envelope 0-0.8s, then popped + confetti, auto-finish at 2.5s), so real sleeps -- not
        // compose.mainClock, which only virtualizes animation frame callbacks -- land mid-phase.
        Thread.sleep(300)
        compose.waitForIdle()
        shot("reveal_0.3s")
        Thread.sleep(700) // total ~1.0s: popped, light burst + confetti started
        compose.waitForIdle()
        shot("reveal_1.0s")
        Thread.sleep(800) // total ~1.8s
        compose.waitForIdle()
        shot("reveal_1.8s")

        compose.waitUntil(6_000) {
            compose.onAllNodesWithText(LixoStrings.FRIENDS_NAME_STEP_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Reduced motion skips the confetti overlay (`testTag("confetti")` has no node). */
    @Test
    fun reducedMotion_noConfettiOverlayNode() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_reveal4", store)
        val repoB = friendRepoFor("uidB_reveal4", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner(), prefilledFriendCode = invite.code)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(LixoStrings.FRIEND_REVEAL_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("confetti", useUnmergedTree = true).assertDoesNotExist()
    }
}
