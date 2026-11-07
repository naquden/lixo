package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.theme.LixoStrings

/**
 * Verifies feature 006 (no dummy profile in release, doc/plans/006-no-dummy-profile-in-release.md):
 * a release-style repo starts on [se.lixo.app.ui.screens.onboarding.FirstProfileSetupScreen], and
 * creating a profile there starts the tutorial immediately, while a debug-style repo still seeds
 * "Saga" and skips the setup screen entirely.
 */
class FirstProfileSetupTest {

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

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun releaseRepo_showsSetupScreen_createProfileStartsTutorial() {
        val repo = LixoRepository(FakeCloudGateway(), lexiconCache = null, isDebugBuild = false, profileStore = null)
        compose.setContent { LixoApp(repository = repo) }

        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertExists()
        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_SUBTITLE).assertExists()
        compose.onNodeWithText("Saga").assertDoesNotExist()

        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).assertIsNotEnabled()
        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).performClick()
        assertTrue(repo.profiles.value.isEmpty())
        shot("006_01_first_profile_setup")

        compose.onNode(hasSetTextAction()).performTextInput("Ella")
        compose.onNodeWithText("7").performClick()
        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).assertIsEnabled().performClick()
        compose.waitForIdle()

        val created = repo.profiles.value.single()
        assertEquals("Ella", created.displayName)
        assertEquals(7, created.ageYears)
        assertTrue(!created.tutorialCompleted)
        assertEquals(created.id, repo.activeProfileId.value)

        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.TUTORIAL_WELCOME_TITLE).assertExists()
        compose.onNodeWithText(LixoStrings.TUTORIAL_ALLOW_MIC_BTN.uppercase()).assertExists()
        shot("006_02_tutorial_started")

        pressBack()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.PROFILE_SELECT_TITLE).assertExists()
        compose.onNodeWithText("Ella").assertExists()
        compose.onNodeWithText("Saga").assertDoesNotExist()
        shot("006_03_profile_list_ella")
    }

    @Test
    fun debugRepo_seedsSaga_skipsSetupScreen() {
        val repo = LixoRepository(FakeCloudGateway(), lexiconCache = null, isDebugBuild = true, profileStore = null)
        compose.setContent { LixoApp(repository = repo) }

        compose.onNodeWithText(LixoStrings.PROFILE_SELECT_TITLE).assertExists()
        compose.onNodeWithText("Saga").assertExists()
        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertDoesNotExist()

        assertTrue(repo.profiles.value.single().tutorialCompleted)
        assertTrue(repo.unlockedItemIds.value.contains(BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID))
        shot("006_04_debug_saga")
    }
}
