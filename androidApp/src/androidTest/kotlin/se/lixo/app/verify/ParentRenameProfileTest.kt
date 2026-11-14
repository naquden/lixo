package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.parent.ParentGateAndDashboardScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies doc/plans/050: the parent renames the active child's profile from the real
 * [ParentGateAndDashboardScreen], against a real [LixoRepository] on a Room DB.
 */
class ParentRenameProfileTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    companion object {
        private const val TEST_DB = "lixo_test_050.db"
    }

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
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }
        ctx.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "050_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun newRepo(uid: String) = LixoRepository(
        FakeCloudGateway(),
        friendCloud = FakeFriendCloud(uid),
        lexiconCache = null,
        isDebugBuild = false,
        profileStore = createAndroidProfileStore(ctx, TEST_DB),
        content = TestContent.repository
    )

    @Composable
    private fun RenameTestHost(repo: LixoRepository) {
        val profiles by repo.profiles.collectAsState()
        val activeProfileId by repo.activeProfileId.collectAsState()
        val content by repo.content.active.collectAsState()
        val friendsByProfile by repo.friendsByProfile.collectAsState()
        val privacySettings by repo.privacySettings.collectAsState()
        val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()

        if (activeProfile == null) return

        LixoTheme {
            ParentGateAndDashboardScreen(
                profiles = profiles,
                activeProfile = activeProfile,
                content = content,
                dailyActivity = emptyMap(),
                struggledWords = emptyList(),
                mathErrors = emptyList(),
                friends = friendsByProfile[activeProfile.id].orEmpty(),
                privacySettings = privacySettings,
                mathEngine = MathEngine(),
                onSelectProfile = { repo.selectProfile(it.id) },
                onUpdateProfileGoalsAndSettings = { repo.updateProfile(it) },
                onRenameProfile = { id, n -> repo.renameProfile(id, n) },
                onRemoveFriend = { repo.removeFriend(it) },
                onConnectOptionalEmailAccount = { _, _ -> Result.failure(UnsupportedOperationException()) },
                onSwitchToLocalOnly = {},
                onDeleteCloudAccount = { Result.failure(UnsupportedOperationException()) },
                onToggleCrashlyticsConsent = {}
            )
        }
    }

    private fun unlockGate() {
        val answer = MathEngine().generateParentGateChallenge(7).second.toString()
        compose.onNodeWithText(LixoStrings.PARENT_GATE_INPUT_LABEL).performTextInput(answer)
        compose.onNodeWithText(LixoStrings.PARENT_GATE_OPEN_BTN).performClick()
        compose.waitForIdle()
    }

    /** Creates the given profiles in order (the last one becomes active) and returns their ids. */
    private fun seed(repo: LixoRepository, vararg names: String): List<String> {
        var ids = emptyList<String>()
        compose.runOnUiThread { ids = names.map { repo.createProfile(it, 8).id } }
        compose.waitForIdle()
        return ids
    }

    private fun startEditing() {
        compose.onNodeWithTag("parent_rename_btn").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun parentRenamesProfile_namePersists() {
        val repo = newRepo("uid_050_persist")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        val ellaId = seed(repo, "Ella").single()

        compose.setContent { RenameTestHost(repo) }
        compose.waitForIdle()
        unlockGate()

        startEditing()
        compose.onNodeWithTag("parent_name_field").assertIsFocused()
        compose.onNodeWithTag("parent_name_field").performTextClearance()
        compose.onNodeWithTag("parent_name_field").performTextInput("Elsa")
        compose.onNodeWithTag("parent_name_save").performScrollTo().performClick()
        compose.waitForIdle()

        // Sibling switcher chip + name card.
        compose.onAllNodesWithText("Elsa").assertCountEquals(2)
        compose.onAllNodesWithText("Ella").assertCountEquals(0)
        compose.onNodeWithTag("parent_name_field").assertDoesNotExist()
        shot("renamed")

        compose.waitUntil(5_000) {
            runBlocking { createAndroidProfileStore(ctx, TEST_DB).loadProfiles() }
                .any { it.id == ellaId && it.displayName == "Elsa" }
        }
        val repo2 = newRepo("uid_050_persist_2")
        compose.waitUntil(5_000) { repo2.profilesLoaded.value }
        assertEquals("Elsa", repo2.profiles.value.first { it.id == ellaId }.displayName)
    }

    @Test
    fun rename_imeDone_saves() {
        val repo = newRepo("uid_050_ime")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        seed(repo, "Ella")

        compose.setContent { RenameTestHost(repo) }
        compose.waitForIdle()
        unlockGate()

        startEditing()
        compose.onNodeWithTag("parent_name_field").performTextClearance()
        compose.onNodeWithTag("parent_name_field").performTextInput("Alva")
        compose.onNodeWithTag("parent_name_field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("parent_name_field").assertDoesNotExist()
        compose.onAllNodesWithText("Alva").assertCountEquals(2)
        assertEquals("Alva", repo.profiles.value.single().displayName)
    }

    @Test
    fun rename_blank_saveDisabled_cancelRestores() {
        val repo = newRepo("uid_050_blank")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        seed(repo, "Ella")

        compose.setContent { RenameTestHost(repo) }
        compose.waitForIdle()
        unlockGate()

        startEditing()
        compose.onNodeWithTag("parent_name_field").performTextClearance()
        compose.onNodeWithTag("parent_name_save").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithText(LixoStrings.PARENT_RENAME_CANCEL_BTN).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("parent_name_field").assertDoesNotExist()
        compose.onAllNodesWithText("Ella").assertCountEquals(2)
        assertEquals("Ella", repo.profiles.value.single().displayName)
    }

    @Test
    fun rename_switchSibling_resetsEditState() {
        val repo = newRepo("uid_050_switch")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        val (ellaId, _) = seed(repo, "Ella", "Olle")
        compose.runOnUiThread { repo.selectProfile(ellaId) }
        compose.waitForIdle()

        compose.setContent { RenameTestHost(repo) }
        compose.waitForIdle()
        unlockGate()

        startEditing()
        compose.onNodeWithTag("parent_name_field").assertExists()

        // Ella is active, so the "Olle" text is only the sibling chip.
        compose.onNodeWithText("Olle").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("parent_name_field").assertDoesNotExist()
        // Sibling chip + name card now both show "Olle".
        compose.onAllNodesWithText("Olle").assertCountEquals(2)
    }

    @Test
    fun rename_toSiblingName_showsErrorAndBlocksSave() {
        val repo = newRepo("uid_050_taken")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        val (ellaId, _) = seed(repo, "Ella", "Olle")
        compose.runOnUiThread { repo.selectProfile(ellaId) }
        compose.waitForIdle()

        compose.setContent { RenameTestHost(repo) }
        compose.waitForIdle()
        unlockGate()

        startEditing()
        compose.onNodeWithTag("parent_name_field").performTextClearance()
        compose.onNodeWithTag("parent_name_field").performTextInput("olle")
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.PROFILE_NAME_TAKEN).assertExists()
        compose.onNodeWithTag("parent_name_save").performScrollTo().assertIsNotEnabled()
        shot("name_taken")

        compose.onNodeWithTag("parent_name_field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("parent_name_field").assertExists()
        assertEquals("Ella", repo.profiles.value.first { it.id == ellaId }.displayName)
    }
}
