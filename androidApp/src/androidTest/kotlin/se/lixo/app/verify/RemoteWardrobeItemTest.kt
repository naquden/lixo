package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.local.CachedWardrobeVersion
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.WardrobeMeta
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.platform.createAndroidWardrobeCatalogCache
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.wardrobe.WardrobeScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 015 (doc/plans/015-remote-wardrobe-items.md): a remote wardrobe item downloaded
 * from Firestore renders in the real [WardrobeScreen] once synced, survives a cold start via the
 * Room-backed [se.lixo.app.data.local.WardrobeCatalogCache], and that the Room 6 -> 7 migration
 * (`MIGRATION_6_7`) keeps existing profiles/inventory intact while adding the new columns/table.
 */
class RemoteWardrobeItemTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    companion object {
        private const val TEST_DB = "lixo_test_015.db"
        private const val MIG_DB = "lixo_test_015_mig.db"

        private const val REMOTE_ID = "item_hat_remote_test_kosmisk"
        private const val REMOTE_NAME = "Testkometens Kosmiska Hatt"

        private val REMOTE_JSON = """
            {
              "id": "$REMOTE_ID",
              "nameSv": "$REMOTE_NAME",
              "descriptionSv": "En testhatt för plan 015-verifiering.",
              "slot": "HAT",
              "rarity": "LEGENDARY",
              "subjectTheme": "ENGLISH",
              "meshAssetPath": "3d_sample.glb",
              "meshNodeName": "socket_hat_test_kosmisk",
              "scaleMultiplier": 1.15,
              "unlockStarRequirement": 0,
              "unlockAchievementId": null,
              "isWeeklyStreakSetPiece": false,
              "primaryColorHex": 4278911744,
              "styleIndex": 1,
              "secondaryColorHex": 4278247679,
              "pattern": "STARS",
              "ornament": "SPARKLE",
              "retired": false,
              "minAppVersionCode": 0
            }
        """.trimIndent()

        private fun remoteGateway(): CloudSyncGateway = object : CloudSyncGateway by FakeCloudGateway() {
            override suspend fun fetchWardrobeMeta(): WardrobeMeta? = WardrobeMeta(version = 1, chunkCount = 1)
            override suspend fun fetchWardrobeItems(chunkCount: Int): List<String> = listOf(REMOTE_JSON)
        }
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
        ctx.getDatabasePath(TEST_DB).delete()
        ctx.getDatabasePath(MIG_DB).delete()
    }

    @After
    fun tearDown() {
        ctx.getDatabasePath(TEST_DB).delete()
        ctx.getDatabasePath(MIG_DB).delete()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun awaitComposeHierarchy() {
        var lastError: IllegalStateException? = null
        repeat(20) {
            try {
                compose.waitForIdle()
                compose.onRoot().assertExists()
                return
            } catch (e: IllegalStateException) {
                lastError = e
                Thread.sleep(100)
            }
        }
        lastError?.let { throw it }
    }

    private fun renderWardrobe(repo: LixoRepository, profileId: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LixoTheme {
                val profiles by repo.profiles.collectAsState()
                val profile = profiles.first { it.id == profileId }
                WardrobeScreen(
                    profile = profile,
                    allWardrobeItems = repo.wardrobeCatalog.collectAsState().value,
                    unlockedItemIds = repo.unlockedItemIds.collectAsState().value,
                    ttsEngine = remember { createPlatformTextToSpeechEngine() },
                    showSwedishTtsButtons = false,
                    onSelectAvatarType = {},
                    onEquipSlotItem = { _, _ -> },
                    onCustomizeBaseTraits = { _, _, _ -> },
                    onBack = {}
                )
            }
        }
        awaitComposeHierarchy()
        compose.mainClock.advanceTimeBy(700)
    }

    @Test
    fun remoteLegendary_appearsInWardrobeWhenOwned() {
        val repo = LixoRepository(
            remoteGateway(),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            wardrobeCache = createAndroidWardrobeCatalogCache(ctx, TEST_DB)
        )
        val created = repo.createProfile("Testa", 7)

        runBlocking {
            repo.loadCachedWardrobe()
            repo.syncCurriculumFromCloud()
        }

        assertTrue(repo.wardrobeCatalog.value.any { it.id == REMOTE_ID })

        repo.unlockWardrobeItem(REMOTE_ID)
        repo.equipItemOnActiveProfile(EquipmentSlot.HAT, REMOTE_ID)

        renderWardrobe(repo, created.id)

        compose.onAllNodesWithText(REMOTE_NAME)[0].assertExists()
        shot("015_01_remote_legendary_wardrobe")
    }

    @Test
    fun remoteItem_survivesColdStart() {
        val repoA = LixoRepository(
            remoteGateway(),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            wardrobeCache = createAndroidWardrobeCatalogCache(ctx, TEST_DB)
        )
        runBlocking {
            repoA.loadCachedWardrobe()
            repoA.syncCurriculumFromCloud()
        }

        val cachedVersion = runBlocking { createAndroidWardrobeCatalogCache(ctx, TEST_DB).loadVersion() }
        assertEquals(1, cachedVersion?.version)

        val repoB = LixoRepository(
            FakeCloudGateway(),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            wardrobeCache = createAndroidWardrobeCatalogCache(ctx, TEST_DB)
        )
        runBlocking {
            repoB.loadCachedWardrobe()
            repoB.syncCurriculumFromCloud()
        }

        assertTrue(repoB.wardrobeCatalog.value.any { it.id == REMOTE_ID })

        val created = repoB.createProfile("Testa", 7)
        repoB.unlockWardrobeItem(REMOTE_ID)
        repoB.equipItemOnActiveProfile(EquipmentSlot.HAT, REMOTE_ID)

        renderWardrobe(repoB, created.id)

        compose.onAllNodesWithText(REMOTE_NAME)[0].assertExists()
        shot("015_02_remote_item_cold_start")
    }

    @Test
    fun dbMigration6to7_keepsProfilesAndInventory() {
        val v7 = createAndroidProfileStore(ctx, MIG_DB)
        runBlocking {
            v7.saveProfile(ChildProfile(id = "profile_mig", displayName = "Migra"))
            v7.saveUnlockedItem("item_hat_starter_explorer", "TEST")
        }

        rewindAndroidTestDatabaseToVersion6(ctx, MIG_DB)

        val migrated = createAndroidProfileStore(ctx, MIG_DB)
        val profiles = runBlocking { migrated.loadProfiles() }
        val unlockedIds = runBlocking { migrated.loadUnlockedItemIds() }
        assertTrue(profiles.any { it.id == "profile_mig" })
        assertTrue(unlockedIds.contains("item_hat_starter_explorer"))

        val cache = createAndroidWardrobeCatalogCache(ctx, MIG_DB)
        assertEquals(null, runBlocking { cache.loadVersion() })
        assertTrue(runBlocking { cache.loadItems() }.isEmpty())

        val item = WardrobeItem(
            id = "item_hat_remote_migration_test",
            nameSv = "Migrationstest",
            descriptionSv = "Testitem för migrationsverifiering.",
            slot = EquipmentSlot.HAT,
            rarity = Rarity.LEGENDARY,
            subjectTheme = Subject.ENGLISH,
            retired = true,
            minAppVersionCode = 3
        )
        runBlocking { cache.upsert(1, 7, listOf(item)) }
        val reloaded = runBlocking { cache.loadItems() }.first { it.id == item.id }
        assertEquals(true, reloaded.retired)
        assertEquals(3, reloaded.minAppVersionCode)
        assertEquals(CachedWardrobeVersion(1, 7), runBlocking { cache.loadVersion() })
    }
}
