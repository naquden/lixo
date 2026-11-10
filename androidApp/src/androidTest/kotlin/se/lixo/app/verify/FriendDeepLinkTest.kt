package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.ui.LixoApp

/**
 * doc/plans/030 §7 `FriendDeepLinkTest`: `ACTION_VIEW lixo://friend/<code>` only routes to the
 * Avatar tab with the code prefilled into "Lägg till kompis" -- nothing is ever redeemed
 * automatically. Drives [FriendDeepLinkDispatcher] directly (the same object `MainActivity`'s
 * `onCreate`/`onNewIntent` feed) and renders the real [se.lixo.app.ui.LixoApp], rather than
 * launching `MainActivity` itself (which would also touch Firebase/App Check init).
 */
class FriendDeepLinkTest {

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

    @After
    fun tearDown() {
        // FriendDeepLinkDispatcher is a process-wide singleton; never leak state into other tests.
        FriendDeepLinkDispatcher.consumeIncomingFriendCode()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun incomingDeepLink_opensAvatarTabWithCodePrefilled_redeemsNothing() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_deeplink", store)
        val repoB = friendRepoFor("uidB_deeplink", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        FriendDeepLinkDispatcher.onIncomingLink(buildFriendPayload(invite.code))

        compose.setContent { LixoApp(repository = repoB) }
        compose.waitUntil(5_000) { compose.hasNodeWithText(se.lixo.app.ui.theme.LixoStrings.FRIENDS_TAB_ADD_FRIEND) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("friend_code_field").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("friend_code_field").assert(hasText(invite.code))
        shot("deep_link_prefilled_code")

        // Nothing was redeemed: B has no friends, and A's card has no new reader.
        assertTrue(repoB.friendsOfActiveProfile().isEmpty())
        assertTrue(store.recordedWrites.none { it.startsWith("redeem") })
    }
}
