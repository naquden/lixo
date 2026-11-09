package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.content.BundledContentPack
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.ContentMetaEntry
import se.lixo.app.data.remote.ContentMetaResult
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.ContentPackChunks
import se.lixo.app.domain.content.Sha256
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.NoOpTextToSpeechEngine
import se.lixo.app.platform.createAndroidContentPackCache
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen

/**
 * Verifies 024 end to end with the real [ContentRepository] + Room cache (no mocks of the sync
 * pipeline): a fake gateway serves the bundled pack plus one new LEGENDARY hat at `version + 1`;
 * [ContentRepository.sync] downloads, verifies and hot-swaps it into a live [WardrobeScreen]
 * without a restart, and a later "offline restart" with a fresh [ContentRepository] + a new Room
 * instance (same DB file) still serves the cached pack.
 */
class ContentHotSwapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dbFileName = "content_hotswap_test.db"
    private val newItemId = "item_hat_cloud_sync_crown"
    private lateinit var outDir: File
    private lateinit var ctx: android.content.Context
    private lateinit var newJson: String

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        ctx.deleteDatabase(dbFileName)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
            instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }

        val bundledJson = runBlocking { BundledContentPack.readJson() }
        newJson = buildNewPackJson(bundledJson)
    }

    private fun buildNewPackJson(bundledJson: String): String {
        val root = JSONObject(bundledJson)
        val newVersion = root.getInt("version") + 1
        root.put("version", newVersion)

        val items = root.getJSONArray("items")
        var template: JSONObject? = null
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("id") == "item_hat_starter_explorer") {
                template = item
                break
            }
        }
        requireNotNull(template) { "item_hat_starter_explorer not found in the bundled pack" }

        val newItem = JSONObject(template.toString()).apply {
            put("id", newItemId)
            put("nameKey", "$newItemId.name")
            put("descKey", "$newItemId.desc")
            put("rarity", "LEGENDARY")
            put("pool", "standard")
            remove("eventId")
        }
        items.put(newItem)

        val strings = root.getJSONObject("strings")
        val sv = strings.getJSONObject("sv")
        sv.put("$newItemId.name", "Molnkronan")
        sv.put("$newItemId.desc", "En krona nedladdad från molnet.")

        return root.toString()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun servingGateway(): CloudSyncGateway {
        val chunks = ContentPackChunks.split(newJson)
        val sha = Sha256.hex(newJson)
        val bytes = newJson.encodeToByteArray().size.toLong()
        return object : CloudSyncGateway by FakeCloudGateway() {
            override suspend fun fetchContentMeta(): ContentMetaResult {
                val version = JSONObject(newJson).getInt("version")
                return ContentMetaResult(
                    schemaVersion = 1,
                    entry = ContentMetaEntry(version = version, chunkCount = chunks.size, sha256 = sha, bytes = bytes),
                    serverTimeMillis = null
                )
            }

            override suspend fun fetchContentChunks(schema: Int, version: Int, count: Int): List<String> = chunks
        }
    }

    @Test
    fun sync_hotSwapsNewItemIntoWardrobe() {
        val cache = createAndroidContentPackCache(ctx, dbFileName)
        val contentRepo = runBlocking { ContentRepository.loadInitial(servingGateway(), cache, isDebugBuild = true) }
        val repo = LixoRepository(
            FakeCloudGateway(),
            lexiconCache = null,
            isDebugBuild = true,
            profileStore = null,
            content = contentRepo
        )
        repo.unlockWardrobeItem(newItemId)

        compose.setContent {
            val content = contentRepo.active.collectAsState().value
            val profiles = repo.profiles.collectAsState().value
            val unlocked = repo.unlockedItemIds.collectAsState().value
            WardrobeScreen(
                profile = profiles.first(),
                content = content,
                unlockedItemIds = unlocked,
                ttsEngine = NoOpTextToSpeechEngine,
                showSwedishTtsButtons = false,
                onSelectAvatar = repo::selectAvatarOnActiveProfile,
                onEquipSlotItem = repo::equipItemOnActiveProfile,
                onCustomizeBaseTraits = { h, e, s -> repo.customizeBaseAppearanceOnActiveProfile(h, e, s) },
                onBack = {}
            )
        }

        compose.onAllNodesWithText("Molnkronan").assertCountEquals(0)
        shot("024_before_sync")

        val bundledVersion = contentRepo.active.value.version
        assertTrue(runBlocking { contentRepo.sync() })
        compose.waitForIdle()

        compose.onAllNodesWithText("Molnkronan").onFirst().assertExists()
        assertEquals(bundledVersion + 1, contentRepo.active.value.version)
        shot("024_after_sync")
    }

    @Test
    fun restart_offlineGateway_keepsCachedPack() {
        val cache1 = createAndroidContentPackCache(ctx, dbFileName)
        val repo1 = runBlocking { ContentRepository.loadInitial(servingGateway(), cache1, isDebugBuild = true) }
        assertTrue(runBlocking { repo1.sync() })

        // Simulate an app restart: a new ContentRepository + a new Room instance over the same DB file.
        val cache2 = createAndroidContentPackCache(ctx, dbFileName)
        val repo2 = runBlocking { ContentRepository.loadInitial(FakeCloudGateway(), cache2, isDebugBuild = true) }
        assertEquals(repo1.active.value.version, repo2.active.value.version)
        assertFalse(runBlocking { repo2.sync() })

        val repo = LixoRepository(
            FakeCloudGateway(),
            lexiconCache = null,
            isDebugBuild = true,
            profileStore = null,
            content = repo2
        )
        repo.unlockWardrobeItem(newItemId)

        compose.setContent {
            val content = repo2.active.collectAsState().value
            val profiles = repo.profiles.collectAsState().value
            val unlocked = repo.unlockedItemIds.collectAsState().value
            WardrobeScreen(
                profile = profiles.first(),
                content = content,
                unlockedItemIds = unlocked,
                ttsEngine = NoOpTextToSpeechEngine,
                showSwedishTtsButtons = false,
                onSelectAvatar = repo::selectAvatarOnActiveProfile,
                onEquipSlotItem = repo::equipItemOnActiveProfile,
                onCustomizeBaseTraits = { h, e, s -> repo.customizeBaseAppearanceOnActiveProfile(h, e, s) },
                onBack = {}
            )
        }

        compose.onAllNodesWithText("Molnkronan").onFirst().assertExists()
        shot("024_restart_cached")
    }
}
