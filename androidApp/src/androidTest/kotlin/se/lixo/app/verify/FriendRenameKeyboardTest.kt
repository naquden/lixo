package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.theme.LixoStrings

/**
 * doc/plans/049: the friend detail sheet must stay usable when the window is shrunk by the
 * keyboard. A 420dp-high host stands in for that shrunk window: the rename field must be focused
 * and visible, "Done" on the keyboard saves, and the sheet scrolls down to "Ta bort kompis".
 */
class FriendRenameKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = instrumentation.targetContext
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

    /** Hosts the friend village in a 420dp-high box, with one friend (B's view of A) added. */
    private fun hostShortWindow(aUid: String, bUid: String): Pair<se.lixo.app.data.repository.LixoRepository, String> {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor(aUid, store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        val repoB = friendRepoFor(bUid, store)
        runBlocking { repoB.addFriendByCode(invite.code, fromQr = false).getOrThrow() }
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner())
            }
        }
        compose.waitForIdle()
        return repoB to aUid
    }

    @Test
    fun renameField_reachableOnShortWindow() {
        val (repo, aUid) = hostShortWindow("uidA_049_rename", "uidB_049_rename")

        compose.onNodeWithTag("friend_plot_$aUid").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIEND_SHEET_CHANGE_NAME_BTN).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("friend_label_field").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("friend_label_field").performTextInput("Olle")
        compose.onNodeWithTag("friend_label_field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithText("Olle", substring = true).assertExists()
        assertEquals("Olle", repo.friendsOfActiveProfile().first().label)
        shot("049_rename_short_window")
    }

    @Test
    fun detailSheet_scrollsToRemoveButton_onShortWindow() {
        val (_, aUid) = hostShortWindow("uidA_049_scroll", "uidB_049_scroll")

        compose.onNodeWithTag("friend_plot_$aUid").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.FRIEND_SHEET_REMOVE_BTN).performScrollTo().assertIsDisplayed()
    }
}
