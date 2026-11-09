package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.screens.map.MAP_LEVEL_NODE_TAG_PREFIX
import se.lixo.app.ui.theme.LixoStrings

/**
 * Verifies feature 008 (doc/plans/008-persist-profiles.md): profiles, the active profile id and
 * the universal unlocked-item pool survive a cold start, via a real Room [ProfileStore] backed
 * by a dedicated test database file (never the app's real `lixo.db`). Also verifies feature 010
 * (doc/plans/010-persist-level-progress.md): per-level progress survives a cold start too, so
 * the map does not relock an already-cleared level.
 */
class ProfilePersistenceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: android.content.Context
    private lateinit var store: ProfileStore

    companion object {
        private const val TEST_DB = "lixo_test_008.db"
    }

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
            instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }

        ctx.deleteDatabase(TEST_DB)
        store = createAndroidProfileStore(ctx, TEST_DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun newRepo(isDebugBuild: Boolean) =
        LixoRepository(
            FakeCloudGateway(),
            lexiconCache = null,
            isDebugBuild = isDebugBuild,
            profileStore = store,
            wardrobeCache = null
        )

    @Test
    fun releaseColdStart_keepsCreatedProfile() {
        val repoA = newRepo(isDebugBuild = false)
        var repo by mutableStateOf<LixoRepository>(repoA)
        compose.setContent { key(repo) { LixoApp(repository = repo) } }

        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("Testa")
        compose.onNodeWithText("7").performClick()
        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.TUTORIAL_WELCOME_TITLE).assertExists()

        compose.waitUntil(5_000) { runBlocking { store.loadProfiles() }.any { it.displayName == "Testa" } }

        val repoB = newRepo(isDebugBuild = false)
        repo = repoB
        compose.waitUntil(5_000) { repo.profilesLoaded.value }

        compose.onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.PROFILE_SELECT_TITLE).assertExists()
        compose.onNodeWithText("Testa").assertExists()

        val testaId = repoB.profiles.value.single { it.displayName == "Testa" }.id
        assertEquals(testaId, repoB.activeProfileId.value)

        shot("008_01_cold_start_testa")
    }

    @Test
    fun debugColdStart_keepsUserProfileAndSaga() {
        val repoA = newRepo(isDebugBuild = false)
        var repo by mutableStateOf<LixoRepository>(repoA)
        compose.setContent { key(repo) { LixoApp(repository = repo) } }
        compose.waitUntil(5_000) { repoA.profilesLoaded.value }

        compose.runOnUiThread { repoA.createProfile("Testa", 8) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { runBlocking { store.loadProfiles() }.any { it.displayName == "Testa" } }

        val repoB = newRepo(isDebugBuild = true)
        repo = repoB
        compose.waitUntil(5_000) { repo.profilesLoaded.value }

        compose.onNodeWithText(LixoStrings.PROFILE_SELECT_TITLE).assertExists()
        compose.onNodeWithText("Saga").assertExists()
        compose.onNodeWithText("Testa").assertExists()

        val sagaCount = repoB.profiles.value.count { it.id == LixoRepository.DEBUG_SAGA_PROFILE_ID }
        assertEquals(1, sagaCount)
        val testa = repoB.profiles.value.single { it.displayName == "Testa" }
        assertEquals(testa.id, repoB.activeProfileId.value)

        val saga = repoB.profiles.value.single { it.id == LixoRepository.DEBUG_SAGA_PROFILE_ID }
        compose.runOnUiThread { repoB.updateProfile(saga.copy(totalStars = 7)) }
        compose.waitUntil(5_000) {
            runBlocking { store.loadProfiles() }.any { it.id == LixoRepository.DEBUG_SAGA_PROFILE_ID && it.totalStars == 7 }
        }

        val repoC = newRepo(isDebugBuild = true)
        repo = repoC
        compose.waitUntil(5_000) { repo.profilesLoaded.value }

        val sagaC = repoC.profiles.value.single { it.id == LixoRepository.DEBUG_SAGA_PROFILE_ID }
        assertEquals(7, sagaC.totalStars)

        shot("008_02_debug_saga_and_testa")
    }

    @Test
    fun coldStart_keepsLevelProgress() {
        val repoA = newRepo(isDebugBuild = false)
        var repo by mutableStateOf<LixoRepository>(repoA)
        compose.setContent { key(repo) { LixoApp(repository = repo) } }
        compose.waitUntil(5_000) { repoA.profilesLoaded.value }

        val swedishLevels = repoA.levels.value.filter { it.subject == Subject.SWEDISH }.sortedBy { it.order }
        val l1 = swedishLevels[0]
        val l2 = swedishLevels[1]
        val l3 = swedishLevels[2]

        var testaId = ""
        compose.runOnUiThread {
            val c = repoA.createProfile("Testa", 8)
            testaId = c.id
            repoA.updateProfile(c.copy(tutorialCompleted = true))
            repoA.saveLevelProgress(
                LevelProgress(
                    profileId = c.id,
                    levelId = l1.id,
                    levelOrder = l1.order,
                    starsEarned = 2,
                    completedInAiMode = false,
                    bestTimeSeconds = 30,
                    bestWpm = 40,
                    updatedAtEpochMillis = 1L
                )
            )
        }

        compose.waitUntil(5_000) {
            runBlocking { store.loadLevelProgress() }.any { it.profileId == testaId && it.levelId == l1.id }
        }
        compose.waitUntil(5_000) {
            runBlocking { store.loadProfiles() }.any { it.id == testaId && it.tutorialCompleted }
        }

        val repoB = newRepo(isDebugBuild = false)
        repo = repoB
        compose.waitUntil(5_000) { repo.profilesLoaded.value }

        assertEquals(2, repoB.levelProgressByProfile.value[testaId]?.get(l1.id)?.starsEarned)

        compose.onNodeWithText(LixoStrings.PROFILE_SELECT_TITLE).assertExists()
        compose.onNodeWithText(LixoStrings.MAP_PLAY_LEVEL_BTN).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + l1.id).assertIsEnabled()
        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + l2.id).assertIsEnabled()
        compose.onNodeWithTag(MAP_LEVEL_NODE_TAG_PREFIX + l3.id).assertIsNotEnabled()

        shot("010_01_cold_start_level2_unlocked")
    }
}
