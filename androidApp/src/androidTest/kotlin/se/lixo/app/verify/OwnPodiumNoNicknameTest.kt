package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.theme.LixoStrings

/**
 * Verifies plan 044 (doc/plans/044-remove-own-nickname-row.md): the own avatar podium no longer
 * shows "Ditt kompisnamn: @…" or a "Slumpa" button, and tapping the own name pill still opens the
 * wardrobe.
 */
class OwnPodiumNoNicknameTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
            instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots/044").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun ownPodium_hasNoNicknameRowOrSlumpa_pillStillOpensWardrobe() {
        val store = FakeFriendCloudStore()
        val repo = friendRepoFor("uid_own044", store, displayName = "Lisa")
        runBlocking { repo.ensureMyCard().getOrThrow() }

        val nick = repo.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)
        assertTrue(nick.isNotBlank())

        var wardrobeOpened = 0
        compose.setContent {
            FriendVillageTestHost(
                repository = repo,
                qrScanner = FakeQrScanner(),
                onOpenWardrobe = { wardrobeOpened++ }
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("${LixoStrings.AVATAR_MAP_ME_PREFIX} · Lisa").assertExists()

        compose.onAllNodesWithText("Ditt kompisnamn", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Slumpa").assertCountEquals(0)
        compose.onAllNodesWithText(nick, substring = true).assertCountEquals(0)

        shot("own_podium")

        compose.onNodeWithText("${LixoStrings.AVATAR_MAP_ME_PREFIX} · Lisa").performClick()
        compose.waitForIdle()
        assertEquals(1, wardrobeOpened)
    }
}
