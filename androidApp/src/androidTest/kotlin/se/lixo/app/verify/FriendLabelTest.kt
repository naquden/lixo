package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.theme.LixoStrings

/**
 * doc/plans/030 §7 `FriendLabelTest`: "Hoppa över" keeps only the generated nickname; "Byt namn"
 * sets a private label that survives a cold start (real Room, not the in-memory [ProfileStore]
 * used by the other friend tests); and the parent oversight list shows `"Olle (@nick)"`.
 */
class FriendLabelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    companion object {
        private const val TEST_DB = "lixo_test_030_label.db"
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
        outDir = File(baseDir, "lixo_screenshots/friends").apply { mkdirs() }
        ctx.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** "Hoppa över" on the name step keeps only the generated nickname -- no label is set. */
    @Test
    fun skippingNameStep_showsOnlyNickname() {
        val store = FakeFriendCloudStore()
        val repoA = friendRepoFor("uidA_skip", store)
        val repoB = friendRepoFor("uidB_skip", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        val aNick = "@" + repoA.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner(), prefilledFriendCode = invite.code)
        }
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIENDS_ADD_BTN).performClick()
        compose.waitUntil(6_000) { compose.hasNodeWithText(LixoStrings.FRIENDS_NAME_STEP_TITLE) }
        compose.onNodeWithText(LixoStrings.FRIENDS_NAME_SKIP_BTN).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(aNick).assertExists()
        assertEquals(null, repoB.friendsOfActiveProfile().first().label)
        shot("label_skip_only_nickname")
    }

    /** "Byt namn" -> "Olle"; a cold-start repo on the same Room DB still shows "Olle". */
    @Test
    fun changeNameToOlle_survivesColdStart_andShowsInParentList() {
        val store = FakeFriendCloudStore()
        val aUid = "uidA_olle"
        val bUid = "uidB_olle"
        val repoA = friendRepoFor(aUid, store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        val profileStoreFirstBoot = createAndroidProfileStore(ctx, TEST_DB)
        val repoBFirstBoot = friendRepoFor(bUid, store, profileStore = profileStoreFirstBoot, autoCreateProfile = false)
        runBlocking {
            var waitedMs = 0
            while (!repoBFirstBoot.profilesLoaded.value && waitedMs < 5_000) {
                delay(50)
                waitedMs += 50
            }
        }
        if (repoBFirstBoot.profiles.value.isEmpty()) {
            repoBFirstBoot.createProfile("Child_$bUid", 8)
        }
        runBlocking { repoBFirstBoot.addFriendByCode(invite.code, fromQr = false).getOrThrow() }

        var hostRepo by mutableStateOf(repoBFirstBoot)
        var fullApp by mutableStateOf(false)
        compose.setContent {
            key(hostRepo, fullApp) {
                if (fullApp) LixoApp(repository = hostRepo)
                else FriendVillageTestHost(repository = hostRepo, qrScanner = FakeQrScanner())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("friend_plot_$aUid").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.FRIEND_SHEET_CHANGE_NAME_BTN).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Olle")
        compose.onNodeWithText(LixoStrings.FRIENDS_NAME_SAVE_BTN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Olle").assertExists()
        shot("label_change_to_olle")

        // Let the device-only label write (doc/plans/030 §5) land in Room before "restarting".
        runBlocking { delay(500) }

        // Cold start: a brand-new LixoRepository on the same Room DB file, same uid/identity.
        val profileStoreColdStart = createAndroidProfileStore(ctx, TEST_DB)
        val repoBColdStart = friendRepoFor(bUid, store, profileStore = profileStoreColdStart, autoCreateProfile = false)
        runBlocking {
            var waitedMs = 0
            while (!repoBColdStart.profilesLoaded.value && waitedMs < 5_000) {
                delay(50)
                waitedMs += 50
            }
        }
        assertEquals("Olle", repoBColdStart.friendsOfActiveProfile().first { it.userId == aUid }.label)

        val coldStartProfile = repoBColdStart.profiles.value.first()
        repoBColdStart.updateProfile(coldStartProfile.copy(tutorialCompleted = true))

        compose.runOnIdle { hostRepo = repoBColdStart }
        compose.waitForIdle()
        compose.onNodeWithText("Olle").assertExists()
        shot("label_cold_start_olle")

        // Parent list shows "Olle (@nick)" (doc/plans/030 §7).
        val aNick = repoA.profiles.value.first().nickname!!.render(TestContent.active.nicknameWords)
        compose.runOnIdle { fullApp = true }
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.MAP_PLAY_LEVEL_BTN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.NAV_PARENT_SETTINGS).performClick()
        compose.waitForIdle()
        // ParentGateAndDashboardScreen starts at challengeSeed = 7 (same as ScreenshotTest).
        val gateAnswer = MathEngine().generateParentGateChallenge(7).second.toString()
        compose.onNodeWithText(LixoStrings.PARENT_GATE_INPUT_LABEL).performTextInput(gateAnswer)
        compose.onNodeWithText(LixoStrings.PARENT_GATE_OPEN_BTN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Olle (@$aNick)").performScrollTo().assertExists()
        shot("label_parent_list_olle_at_nick")
    }
}
