package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.local.ImportMode
import se.lixo.app.data.local.ProfileBackup
import se.lixo.app.data.local.decodeProfileBackup
import se.lixo.app.data.local.encodeProfileBackup
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.FileOpenResult
import se.lixo.app.platform.FileSaveResult
import se.lixo.app.platform.ProfileFilePicker
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.parent.ParentGateAndDashboardScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies doc/plans/045 (profile export/import): exporting the active child profile to a
 * file through [ProfileFilePicker], and importing it back (replace existing / add as new),
 * against the real [ParentGateAndDashboardScreen] and a real [LixoRepository].
 */
class ProfileBackupTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    companion object {
        private const val TEST_DB = "lixo_test_045.db"
        private const val TEST_DB_FRESH = "lixo_test_045_fresh.db"
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
        ctx.deleteDatabase(TEST_DB_FRESH)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
        ctx.deleteDatabase(TEST_DB_FRESH)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "045_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** In-memory [ProfileFilePicker] fake: the system picker UI is outside the app under test. */
    private class InMemoryProfileFilePicker(var nextOpen: FileOpenResult = FileOpenResult.Cancelled) : ProfileFilePicker {
        var saved: String? = null
        var savedName: String? = null

        override suspend fun saveText(suggestedFileName: String, mimeType: String, text: String): FileSaveResult {
            saved = text
            savedName = suggestedFileName
            return FileSaveResult.Saved
        }

        override suspend fun openText(mimeTypes: List<String>): FileOpenResult = nextOpen
    }

    private fun newRepo(dbFile: String, uid: String) = LixoRepository(
        FakeCloudGateway(),
        friendCloud = FakeFriendCloud(uid),
        lexiconCache = null,
        isDebugBuild = false,
        profileStore = createAndroidProfileStore(ctx, dbFile),
        content = TestContent.repository
    )

    @Composable
    private fun BackupTestHost(
        repo: LixoRepository,
        picker: ProfileFilePicker,
        onImportBackup: suspend (ProfileBackup, ImportMode) -> Result<ChildProfile> = { backup, mode ->
            repo.importProfileBackup(backup, mode)
        }
    ) {
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
                onRemoveFriend = { repo.removeFriend(it) },
                onConnectOptionalEmailAccount = { _, _ -> Result.failure(UnsupportedOperationException()) },
                onSwitchToLocalOnly = {},
                onDeleteCloudAccount = { Result.failure(UnsupportedOperationException()) },
                onToggleCrashlyticsConsent = {},
                filePicker = picker,
                onBuildBackup = {
                    repo.buildProfileBackup(repo.activeProfileId.value ?: activeProfile.id)?.let(::encodeProfileBackup)
                },
                onPrepareImport = ::decodeProfileBackup,
                onImportBackup = onImportBackup,
                backupConflicts = repo::backupConflictsWithExisting
            )
        }
    }

    private fun unlockGate() {
        val answer = MathEngine().generateParentGateChallenge(7).second.toString()
        compose.onNodeWithText(LixoStrings.PARENT_GATE_INPUT_LABEL).performTextInput(answer)
        compose.onNodeWithText(LixoStrings.PARENT_GATE_OPEN_BTN).performClick()
        compose.waitForIdle()
    }

    private fun seedElla(repo: LixoRepository): String {
        var ellaId = ""
        compose.runOnUiThread {
            val ella = repo.createProfile("Ella", 8)
            ellaId = ella.id
            repo.updateProfile(ella.copy(tutorialCompleted = true, totalStars = 7))
            repo.saveLevelProgress(
                LevelProgress(
                    profileId = ella.id,
                    levelId = repo.levels.value.first().id,
                    levelOrder = repo.levels.value.first().order,
                    starsEarned = 3,
                    completedInAiMode = false,
                    bestTimeSeconds = 30,
                    bestWpm = 0
                )
            )
            repo.unlockWardrobeItem(TestContent.active.itemsById.keys.first())
        }
        compose.waitForIdle()
        return ellaId
    }

    @Test
    fun exportProfile_writesValidBackup() {
        val repo = newRepo(TEST_DB, "uid_045_export")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        val ellaId = seedElla(repo)
        val picker = InMemoryProfileFilePicker()

        compose.setContent { BackupTestHost(repo, picker) }
        compose.waitForIdle()
        unlockGate()

        compose.onNodeWithTag("parent_export_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { picker.saved != null }

        val decoded = decodeProfileBackup(picker.saved!!).getOrThrow()
        assertEquals(ellaId, decoded.profile.id)
        assertEquals(7, decoded.profile.totalStars)
        assertTrue(decoded.levelProgress.any { it.levelId == repo.levels.value.first().id })
        assertTrue(decoded.unlockedItems.any { it.itemId == TestContent.active.itemsById.keys.first() })
        assertFalse(picker.saved!!.contains("friendCardId"))
        assertTrue(picker.savedName!!.startsWith("lixo-Ella-"))

        compose.onNodeWithText(LixoStrings.PARENT_BACKUP_EXPORT_DONE).performScrollTo().assertExists()
        shot("export_done")
    }

    @Test
    fun importBackup_replacesExistingProfile() {
        val repo = newRepo(TEST_DB, "uid_045_replace")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        seedElla(repo)
        val picker = InMemoryProfileFilePicker()

        compose.setContent { BackupTestHost(repo, picker) }
        compose.waitForIdle()
        unlockGate()

        compose.onNodeWithTag("parent_export_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { picker.saved != null }
        val savedJson = picker.saved!!

        compose.runOnUiThread {
            val ella = repo.profiles.value.first()
            repo.updateProfile(ella.copy(totalStars = 0))
            repo.saveLevelProgress(
                LevelProgress(
                    profileId = ella.id,
                    levelId = repo.levels.value.first().id,
                    levelOrder = repo.levels.value.first().order,
                    starsEarned = 0,
                    completedInAiMode = false,
                    bestTimeSeconds = 0,
                    bestWpm = 0
                )
            )
        }
        compose.waitForIdle()

        picker.nextOpen = FileOpenResult.Opened(savedJson)
        compose.onNodeWithTag("parent_import_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCompat("Återställ Ella?") }

        compose.onNodeWithText("Återställ Ella?").assertExists()
        compose.onNode(isDialog()).captureToImage()
        compose.onNodeWithText(LixoStrings.parentBackupReplaceBtn("Ella")).performClick()
        compose.waitForIdle()

        compose.waitUntil(5_000) { repo.profiles.value.first { it.displayName == "Ella" }.totalStars == 7 }
        compose.onNodeWithText(LixoStrings.parentBackupRestored("Ella")).performScrollTo().assertExists()
        compose.onNodeWithText("7").assertExists()
        compose.waitUntil(5_000) {
            runBlocking { createAndroidProfileStore(ctx, TEST_DB).loadLevelProgress() }
                .any { it.levelId == repo.levels.value.first().id && it.starsEarned == 3 }
        }
        shot("import_replace_restored")
    }

    @Test
    fun importBackup_asNewProfile_onFreshRepository() {
        val repoA = newRepo(TEST_DB, "uid_045_asnew_a")
        compose.waitUntil(5_000) { repoA.profilesLoaded.value }
        seedElla(repoA)
        val pickerA = InMemoryProfileFilePicker()

        var hostRepo by mutableStateOf(repoA)
        var hostPicker by mutableStateOf<ProfileFilePicker>(pickerA)
        compose.setContent { key(hostRepo, hostPicker) { BackupTestHost(hostRepo, hostPicker) } }
        compose.waitForIdle()
        unlockGate()
        compose.onNodeWithTag("parent_export_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { pickerA.saved != null }
        val savedJson = pickerA.saved!!

        val repoB = newRepo(TEST_DB_FRESH, "uid_045_asnew_b")
        compose.waitUntil(5_000) { repoB.profilesLoaded.value }
        compose.runOnUiThread { repoB.updateProfile(repoB.createProfile("Tom", 7).copy(tutorialCompleted = true)) }
        compose.waitForIdle()

        val pickerB = InMemoryProfileFilePicker(nextOpen = FileOpenResult.Opened(savedJson))
        compose.runOnIdle {
            hostRepo = repoB
            hostPicker = pickerB
        }
        compose.waitForIdle()
        unlockGate()

        compose.onNodeWithTag("parent_import_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCompat("Återställ Ella?") }
        compose.onNodeWithText(LixoStrings.PARENT_BACKUP_ADD_BTN).performClick()
        compose.waitForIdle()

        val ellaId = repoA.profiles.value.first { it.displayName == "Ella" }.id
        compose.waitUntil(5_000) { repoB.activeProfileId.value == ellaId }
        compose.onNodeWithText("Tom").assertExists()
        compose.onNodeWithText("Ella").assertExists()
        compose.onNodeWithText("7").assertExists()
        shot("import_as_new")

        val repoC = newRepo(TEST_DB_FRESH, "uid_045_asnew_c")
        compose.waitUntil(5_000) { repoC.profilesLoaded.value }
        val ellaC = repoC.profiles.value.first { it.id == ellaId }
        assertEquals(7, ellaC.totalStars)
        assertTrue(repoC.levelProgressByProfile.value[ellaId]?.values.orEmpty().any { it.starsEarned == 3 })
    }

    @Test
    fun importInvalidFile_showsError() {
        val repo = newRepo(TEST_DB, "uid_045_invalid")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        seedElla(repo)
        val picker = InMemoryProfileFilePicker(nextOpen = FileOpenResult.Opened("{}"))

        compose.setContent { BackupTestHost(repo, picker) }
        compose.waitForIdle()
        unlockGate()

        compose.onNodeWithTag("parent_import_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCompat(LixoStrings.PARENT_BACKUP_NOT_A_BACKUP) }
        compose.onNodeWithText(LixoStrings.PARENT_BACKUP_NOT_A_BACKUP).performScrollTo().assertExists()
        shot("import_invalid")
    }

    @Test
    fun importConfirm_whenImportCancelled_reEnablesBackupButtons() {
        val repo = newRepo(TEST_DB, "uid_047_cancel")
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        seedElla(repo)
        val picker = InMemoryProfileFilePicker()

        compose.setContent {
            BackupTestHost(
                repo,
                picker,
                onImportBackup = { _, _ -> throw kotlinx.coroutines.CancellationException("test") }
            )
        }
        compose.waitForIdle()
        unlockGate()

        compose.onNodeWithTag("parent_export_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { picker.saved != null }

        picker.nextOpen = FileOpenResult.Opened(picker.saved!!)
        compose.onNodeWithTag("parent_import_profile").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCompat("Återställ Ella?") }
        compose.onNodeWithTag("parent_import_confirm").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("parent_export_profile").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("parent_import_profile").performScrollTo().assertIsEnabled()
        shot("import_cancelled_reenabled")
    }
}

/** `waitUntil` needs a boolean probe; this avoids a flaky `assertExists` inside the predicate. */
private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCompat(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
