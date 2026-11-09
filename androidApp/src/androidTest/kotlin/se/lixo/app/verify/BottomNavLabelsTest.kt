package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.theme.LixoStrings

/**
 * Verifies feature 013 (bottom nav relabel, doc/plans/013-nav-learn-and-avatar-tabs.md) against
 * the real [LixoApp]: the bottom bar shows "Lär dig"/"Avatar" instead of "Karta"/"Kompisar", and
 * tapping each tab navigates to the right screen.
 */
class BottomNavLabelsTest {

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
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun enterMainScaffold() {
        compose.setContent { LixoApp(repository = DummyData.populatedRepository()) }
        compose.waitForIdle()
        compose.onAllNodesWithText(LixoStrings.MAP_PLAY_LEVEL_BTN, ignoreCase = true).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun bottomNav_showsLearnAndAvatarLabels() {
        enterMainScaffold()

        compose.onNodeWithText(LixoStrings.NAV_LEARN).assertExists()
        compose.onNodeWithText(LixoStrings.NAV_AVATAR).assertExists()
        compose.onNodeWithText("Karta").assertDoesNotExist()
        compose.onNodeWithText("Kompisar").assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.NAV_TROPHIES).assertExists()
        compose.onNodeWithText(LixoStrings.NAV_PARENT_SETTINGS).assertExists()

        shot("013_bottom_nav")
    }

    @Test
    fun avatarTab_opensAvatarMap_learnTab_returns() {
        enterMainScaffold()

        compose.onNodeWithText(LixoStrings.NAV_AVATAR).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.AVATAR_MAP_TITLE).assertExists()
        shot("013_avatar_tab")

        compose.onNodeWithText(LixoStrings.NAV_LEARN).performClick()
        compose.waitForIdle()
        assertTrue(
            compose.onAllNodesWithText(LixoStrings.WORLD_SELECTOR_HEADER_PREFIX, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText(LixoStrings.AVATAR_MAP_TITLE).assertDoesNotExist()
        shot("013_learn_tab")
    }
}
