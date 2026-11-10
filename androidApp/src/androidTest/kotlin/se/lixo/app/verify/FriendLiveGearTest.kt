package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext

/**
 * doc/plans/030 §6 "live avatar + current gear": B's village is open and A equips a hat; per the
 * plan ("while the Avatar tab is open, the app listens live to each friend's card, so gear
 * changes show up right away"), B's friend plot and detail-sheet gear list should update without
 * B closing/reopening anything. Verified against a real
 * [se.lixo.app.data.repository.LixoRepository] + Room + [FakeFriendCloudStore].
 * [FriendVillageTestHost] mirrors `LixoApp.kt`'s real wiring, including collecting
 * [se.lixo.app.data.repository.LixoRepository.observeFriendCards] via `AvatarMapScreen`'s
 * `observeFriendCards` parameter while the screen stays in composition.
 */
class FriendLiveGearTest {

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

    @Test
    fun equipHatOnA_whileBVillageStaysOpen_bFriendPlotUpdatesLive() {
        val store = FakeFriendCloudStore()
        val aUid = "uidA_gear"
        val repoA = friendRepoFor(aUid, store)
        val repoB = friendRepoFor("uidB_gear", store)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }
        runBlocking { repoB.addFriendByCode(invite.code, fromQr = false).getOrThrow() }

        compose.setContent {
            FriendVillageTestHost(repository = repoB, qrScanner = FakeQrScanner())
        }
        compose.waitForIdle()
        val plotBefore = compose.onNodeWithTag("friend_plot_$aUid").captureToImage().asAndroidBitmap()
        shot("live_gear_before")

        val hat = repoA.content.active.value.items.first { it.slot == EquipmentSlot.HAT }
        repoA.unlockWardrobeItem(hat.id)
        repoA.equipItemOnActiveProfile(EquipmentSlot.HAT, hat.id)

        // The card upsert is debounced 2s on a real dispatcher (doc/plans/030 §4); a generous
        // timeout covers the debounce plus the round trip through FakeFriendCloud, assuming B's
        // village is listening live (doc/plans/030 §6) while it stays open -- B's village is never
        // closed/reopened anywhere in this test.
        compose.waitUntil(10_000) {
            repoB.friendsOfActiveProfile().first { it.userId == aUid }.appearance.equipped[EquipmentSlot.HAT] == hat.id
        }
        compose.waitForIdle()
        val plotAfter = compose.onNodeWithTag("friend_plot_$aUid").captureToImage().asAndroidBitmap()
        shot("live_gear_after")
        assertFalse("friend plot should re-render once the hat shows up", plotBefore.sameAs(plotAfter))

        compose.onNodeWithTag("friend_plot_$aUid").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(repoA.content.active.value.itemName(hat)).assertExists()
        shot("live_gear_detail_sheet")
    }
}
