package se.lixo.app.verify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.QrScanResult
import se.lixo.app.ui.theme.LixoStrings

/**
 * doc/plans/030 §7 `FriendCodeFlowTest`: the QR + typed-code add-friend flow, "no stars"/"no Share
 * button" cleanup, and the multi-use 10-minute invite window (multiple scans while the inviter's
 * sheet is open, reopening the sheet, and expiry), all against a real
 * [se.lixo.app.data.repository.LixoRepository] + Room + [FakeFriendCloudStore].
 */
class FriendCodeFlowTest {

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

    private fun assertNoStarsAnywhere() {
        compose.onNodeWithText("★", substring = true).assertDoesNotExist()
        compose.onNodeWithText("stjärn", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /** Decodes a QR from a captured bitmap with ZXing (pure Java, no Play Services/ML Kit). */
    private fun decodeQr(captured: Bitmap): String {
        val src = if (captured.config == Bitmap.Config.HARDWARE) captured.copy(Bitmap.Config.ARGB_8888, false) else captured
        val pad = 40
        val padded = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        }
        val pixels = IntArray(padded.width * padded.height)
        padded.getPixels(pixels, 0, padded.width, 0, 0, padded.width, padded.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(padded.width, padded.height, pixels)))
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        return QRCodeReader().decode(binary, hints).text
    }

    /** A shows a code + `friend_qr`; the QR bitmap decodes (ZXing) to the invite's payload. */
    @Test
    fun myCodeTab_showsQr_decodesToInvitePayload() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_qr", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        compose.setContent {
            FriendVillageTestHost(repository = repoA, qrScanner = FakeQrScanner())
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_STOP_CODE_BTN) }
        shot("code_sheet_qr")

        val bitmap = compose.onNodeWithTag("friend_qr", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val decoded = decodeQr(bitmap)
        assertEquals(buildFriendPayload(invite.code), decoded)

        // doc/plans/030 §7: no Share button on "Min kompiskod", and no star text/icon anywhere.
        compose.onNodeWithText("Dela", substring = true, ignoreCase = true).assertDoesNotExist()
        assertNoStarsAnywhere()
    }

    /** B scans (FakeQrScanner) -> name step -> "Kalle" -> the plot shows "Kalle" + "@<A's nick>". */
    @Test
    fun scanningQr_thenNameStep_setsLabel_plotShowsLabelAndNick() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_scan", store)
        val repoB = friendRepoFor("uidB_scan", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        val aNick = "@" + repoA.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)
        val scanner = FakeQrScanner(QrScanResult.Success(buildFriendPayload(invite.code)))

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = scanner)
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.clickLast(LixoStrings.FRIENDS_TAB_ADD_FRIEND)
        compose.onNodeWithText(LixoStrings.FRIENDS_SCAN_QR_BTN).performClick()

        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIEND_REVEAL_TITLE) }
        // Auto-finishes after 2.5s into the "Vad heter din kompis?" name step.
        compose.waitUntil(6_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_NAME_STEP_TITLE) }
        compose.onNode(hasSetTextAction()).performTextInput("Kalle")
        compose.onNodeWithText(LixoStrings.FRIENDS_NAME_SAVE_BTN).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Kalle").assertExists()
        compose.onNodeWithText(aNick).assertExists()
        shot("plot_label_and_nick")
    }

    /** Typing a valid code adds the friend. */
    @Test
    fun typedCode_addsFriend() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_typed", store)
        val repoB = friendRepoFor("uidB_typed", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner())
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.clickLast(LixoStrings.FRIENDS_TAB_ADD_FRIEND)
        compose.onNodeWithTag("friend_code_field").performTextInput(invite.code)
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIEND_REVEAL_TITLE) }
        shot("typed_code_success")
    }

    /** An expired code shows an error. */
    @Test
    fun expiredCode_showsError() {
        val store = FakeFriendCloudStore()
        var now = 0L
        val repoA = friendRepoFor("uidA_typed_expired", store, clock = { now })
        val repoC = friendRepoFor("uidC_typed_expired", store, clock = { now })
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        // C's device clock is 11 minutes past the invite's creation time.
        now = 11 * 60_000L
        compose.setContent {
            FriendVillageTestHost(repository = repoC, qrScanner = FakeQrScanner(), nowMillis = { now })
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.clickLast(LixoStrings.FRIENDS_TAB_ADD_FRIEND)
        compose.onNodeWithTag("friend_code_field").performTextInput(invite.code)
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_ERROR_EXPIRED) }
        shot("typed_code_expired")
    }

    /** An `Unavailable` scanner falls back to the code field. */
    @Test
    fun unavailableScanner_fallsBackToField() {
        val store = FakeFriendCloudStore()
        val repoC = friendRepoFor("uidC_typed_unavailable", store)
        val unavailableScanner = FakeQrScanner(QrScanResult.Unavailable)

        compose.setContent {
            FriendVillageTestHost(repository = repoC, qrScanner = unavailableScanner)
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.clickLast(LixoStrings.FRIENDS_TAB_ADD_FRIEND)
        compose.onNodeWithText(LixoStrings.FRIENDS_SCAN_QR_BTN).performClick()
        compose.waitUntil(3_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_SCANNER_UNAVAILABLE) }
        shot("scanner_unavailable_fallback")
    }

    /** No star text/icon anywhere in the village or the detail sheet (doc/plans/030 §7). */
    @Test
    fun noStarsAnywhereInVillageOrDetailSheet() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_nostars", store)
        val repoB = friendRepoFor("uidB_nostars", store)
        val bUid = "uidB_nostars"
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        runBlocking { repoB.addFriendByCode(invite.code, fromQr = false).getOrThrow() }
        runBlocking { repoA.refreshFriends() }

        compose.setContent {
            FriendVillageTestHost(repository = repoA, qrScanner = FakeQrScanner())
        }
        compose.waitForIdle()
        assertNoStarsAnywhere()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("friend_reveal").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("friend_plot_$bUid").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIEND_SHEET_CHANGE_NAME_BTN).assertExists()
        assertNoStarsAnywhere()
        shot("detail_sheet_no_stars")
    }

    /**
     * Multi-scan: while A's sheet is open, B and C both redeem the shown code -> A's "Nya
     * kompisar" list shows both nicknames and the code text does not change; removing C with ✕
     * drops C from A's village. Closing and reopening the sheet within the window shows the same
     * code; a fake-clock jump of 10 minutes shows "Visa ny kod", and tapping it shows a different
     * code.
     */
    @Test
    fun multiScanWhileSheetOpen_removeViaX_reopenSameCode_expiryShowsNewCode() {
        val store = FakeFriendCloudStore()
        var now = 0L
        val repoA = friendRepoFor("uidA_multi", store, clock = { now })
        val repoB = friendRepoFor("uidB_multi", store, clock = { now })
        val repoC = friendRepoFor("uidC_multi", store, clock = { now })
        val cUid = "uidC_multi"

        compose.setContent {
            FriendVillageTestHost(repository = repoA, qrScanner = FakeQrScanner(), nowMillis = { now })
        }
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_STOP_CODE_BTN) }
        val shownCodeBefore = compose.onNodeWithTag("friend_code_text", useUnmergedTree = true).readText()
        val codeDigits = shownCodeBefore.replace("-", "")

        // B and C redeem A's currently-shown code directly; they are separate devices/UIs.
        runBlocking {
            repoB.addFriendByCode(codeDigits, fromQr = false).getOrThrow()
            repoC.addFriendByCode(codeDigits, fromQr = false).getOrThrow()
        }
        val bNick = "@" + repoB.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)
        val cNick = "@" + repoC.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)

        compose.waitUntil(5_000) { compose.hasNodeWithText(bNick) }
        compose.waitUntil(5_000) { compose.hasNodeWithText(cNick) }
        val shownCodeAfterScans = compose.onNodeWithTag("friend_code_text", useUnmergedTree = true).readText()
        assertEquals(shownCodeBefore, shownCodeAfterScans)
        shot("multi_scan_new_friends_list")

        // ✕ on C removes C from A's village. Wait for the reveal overlay to clear first --
        // it sits on top of the sheet and would otherwise swallow the click.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("friend_reveal").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("new_friend_remove_$cUid").performClick()
        compose.waitForIdle()
        assertTrue(repoA.friendsOfActiveProfile().none { it.userId == cUid })
        compose.onNodeWithTag("friend_plot_$cUid").assertDoesNotExist()

        // Close (system back) and reopen the sheet within the window: same code shown.
        Espresso.pressBack()
        compose.waitForIdle()
        compose.clickFirst(LixoStrings.AVATAR_MAP_ADD_FRIEND)
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_STOP_CODE_BTN) }
        val shownCodeReopened = compose.onNodeWithTag("friend_code_text", useUnmergedTree = true).readText()
        assertEquals(shownCodeBefore, shownCodeReopened)

        // Fake clock +10 minutes: the window expired -> "Visa ny kod" is shown.
        now = 10 * 60_000L + 1_000L
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_SHOW_NEW_CODE_BTN) }
        shot("code_expired_show_new")

        compose.onNodeWithText(LixoStrings.FRIENDS_SHOW_NEW_CODE_BTN).performClick()
        compose.waitUntil(5_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_STOP_CODE_BTN) }
        val shownCodeNew = compose.onNodeWithTag("friend_code_text", useUnmergedTree = true).readText()
        assertNotEquals(shownCodeBefore, shownCodeNew)
    }
}
