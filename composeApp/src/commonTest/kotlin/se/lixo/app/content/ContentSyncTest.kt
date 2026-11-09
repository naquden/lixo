package se.lixo.app.content

import dev.gitlive.firebase.auth.ActionCodeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import se.lixo.app.data.local.RoomContentPackCache
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.ContentMetaEntry
import se.lixo.app.data.remote.ContentMetaResult
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.domain.content.ContentJson
import se.lixo.app.domain.content.ContentPackChunks
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.content.Sha256
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.data.remote.LexiconMeta

/** v43 of [SampleContentPack.JSON]: one extra item (a copy of `item_shoes_test_boot`) plus its sv name. */
private val V43_JSON: String = TestPackFixture.edit {
    this["version"] = JsonPrimitive(43)

    val items = (this["items"] as JsonArray).toMutableList()
    val boot = items.first { (it.jsonObject["id"] as? JsonPrimitive)?.content == "item_shoes_test_boot" }.jsonObject
    val newItem = LinkedHashMap(boot).apply {
        this["id"] = JsonPrimitive("item_shoes_test_synced")
        this["nameKey"] = JsonPrimitive("item_shoes_test_synced.name")
    }
    items.add(JsonObject(newItem))
    this["items"] = JsonArray(items)

    val strings = (this["strings"] as JsonObject).jsonObject
    val sv = LinkedHashMap((strings["sv"] as JsonObject).jsonObject)
    sv["item_shoes_test_synced.name"] = JsonPrimitive("Synkad testsko")
    this["strings"] = JsonObject(mapOf("sv" to JsonObject(sv)))
}

/** v41: an older version than the v42 bundled/sample pack, used for the `loadInitial` cache-loses test. */
private val V41_JSON: String = TestPackFixture.edit { this["version"] = JsonPrimitive(41) }

/** v43 whose own `version` field says 44 -- a lying meta vs. decoded-pack mismatch. */
private val V43_CLAIMING_44_JSON: String = TestPackFixture.edit { this["version"] = JsonPrimitive(44) }

/** Removes `item_shoes_test_boot` from v43 (should be rejected: an owned item id disappeared). */
private val V43_REMOVES_BOOT_JSON: String = TestPackFixture.edit {
    this["version"] = JsonPrimitive(43)
    val items = (this["items"] as JsonArray).filterNot {
        (it.jsonObject["id"] as? JsonPrimitive)?.content == "item_shoes_test_boot"
    }
    this["items"] = JsonArray(items)
}

private class FakeGateway(
    var metaResult: ContentMetaResult? = null,
    var chunksResult: List<String>? = null
) : CloudSyncGateway by FirebaseSyncService() {
    var metaCalls = 0
    var chunksCalls = 0

    override suspend fun fetchContentMeta(): ContentMetaResult? {
        metaCalls++
        return metaResult
    }

    override suspend fun fetchContentChunks(schema: Int, version: Int, count: Int): List<String>? {
        chunksCalls++
        return chunksResult
    }

    // Unused by these tests; delegated to a real FirebaseSyncService instance that never touches
    // the network because these calls aren't exercised.
    override suspend fun fetchRemoteCourses(): List<Course> = emptyList()
    override suspend fun fetchLevelsForCourse(courseId: String): List<CampaignLevel> = emptyList()
    override suspend fun fetchTasksForCourse(courseId: String): List<HomeworkTask> = emptyList()
    override suspend fun fetchLexiconMeta(): LexiconMeta? = null
    override suspend fun fetchLexiconEntries(chunkCount: Int): List<DictionaryEntry> = emptyList()
    override fun buildEmailLinkActionCodeSettings(): ActionCodeSettings = error("not used")
    override suspend fun sendSignInLinkToEmail(email: String): Result<Unit> = Result.success(Unit)
    override fun isSignInWithEmailLink(link: String): Boolean = false
    override suspend fun signInWithEmailLink(email: String, link: String): Result<String> = Result.success("u")
    override suspend fun registerOptionalEmailAccount(email: String, password: String): Result<String> =
        Result.success("u")

    override suspend fun publishPublicFriendAvatar(profile: FriendProfile): Result<Unit> = Result.success(Unit)
    override suspend fun lookupFriendByUsernameOrQr(query: String): Result<FriendProfile?> = Result.success(null)
    override suspend fun deleteAccountAndAllCloudData(userId: String): Result<Unit> = Result.success(Unit)
    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit
}

private fun metaFor(json: String, serverTimeMillis: Long? = null, schemaVersion: Int = 1): ContentMetaResult {
    val pack = ContentJson.parseToJsonElement(json).jsonObject
    val version = (pack["version"] as JsonPrimitive).int
    val chunks = ContentPackChunks.split(json)
    return ContentMetaResult(
        schemaVersion = schemaVersion,
        entry = ContentMetaEntry(version = version, chunkCount = chunks.size, sha256 = Sha256.hex(json)),
        serverTimeMillis = serverTimeMillis
    )
}

private val Any?.int: Int
    get() = ((this as JsonPrimitive).content).toInt()

class ContentSyncTest {

    private fun newRepo(gateway: CloudSyncGateway?, cache: RoomContentPackCache? = FakeCache()): ContentRepository =
        ContentRepository(
            initial = SampleContentPack.activeContent(),
            gateway = gateway,
            cache = cache,
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS }
        )

    private fun FakeCache() = RoomContentPackCache(FakeContentPackDao())

    @Test
    fun sync_newerVersion_activatesAndCaches() = runTest {
        val cache = FakeCache()
        val gw = FakeGateway(metaFor(V43_JSON), ContentPackChunks.split(V43_JSON))
        val repo = ContentRepository(
            initial = SampleContentPack.activeContent(),
            gateway = gw,
            cache = cache,
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS }
        )

        assertTrue(repo.sync())
        assertEquals(43, repo.active.value.version)
        assertTrue("item_shoes_test_synced" in repo.active.value.itemsById)
        assertEquals(V43_JSON, cache.load())
    }

    @Test
    fun sync_sameVersion_neverFetchesChunks() = runTest {
        val gw = FakeGateway(metaFor(SampleContentPack.JSON), null)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(0, gw.chunksCalls)
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_nullChunks_keeps42() = runTest {
        val gw = FakeGateway(metaFor(V43_JSON), null)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_shortChunkList_keeps42() = runTest {
        val allChunks = ContentPackChunks.split(V43_JSON)
        val gw = FakeGateway(metaFor(V43_JSON), allChunks.dropLast(1))
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_badSha_keeps42AndCacheEmpty() = runTest {
        val cache = FakeCache()
        val meta = metaFor(V43_JSON).let { it.copy(entry = it.entry.copy(sha256 = "0".repeat(64))) }
        val gw = FakeGateway(meta, ContentPackChunks.split(V43_JSON))
        val repo = ContentRepository(
            initial = SampleContentPack.activeContent(),
            gateway = gw,
            cache = cache,
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS }
        )
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
        assertNull(cache.load())
    }

    @Test
    fun sync_removedOwnedItem_isRejected_keeps42() = runTest {
        val gw = FakeGateway(metaFor(V43_REMOVES_BOOT_JSON), ContentPackChunks.split(V43_REMOVES_BOOT_JSON))
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_otherSchema_neverFetchesChunks_keeps42() = runTest {
        val gw = FakeGateway(metaFor(V43_JSON, schemaVersion = 2), null)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(0, gw.chunksCalls)
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_nullMeta_offline_noChange() = runTest {
        val gw = FakeGateway(null, null)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_packVersionMismatch_isRejected() = runTest {
        // The meta entry claims v43 (matches the chunk content's OWN hash), but the decoded
        // pack's `version` field says 44 -- the kind of mismatch a buggy uploader could produce.
        val chunks = ContentPackChunks.split(V43_CLAIMING_44_JSON)
        val meta = ContentMetaResult(
            schemaVersion = 1,
            entry = ContentMetaEntry(version = 43, chunkCount = chunks.size, sha256 = Sha256.hex(V43_CLAIMING_44_JSON)),
            serverTimeMillis = null
        )
        val gw = FakeGateway(meta, chunks)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun sync_recordsServerTimeEvenWhenNoDownloadFollows() = runTest {
        val gw = FakeGateway(metaFor(SampleContentPack.JSON, serverTimeMillis = 123456L), null)
        val repo = newRepo(gw)
        assertFalse(repo.sync())
        assertEquals(123456L, repo.lastServerTimeMillis)
    }

    @Test
    fun sync_feedsTrustedClock() = runTest {
        val gw = FakeGateway(metaFor(SampleContentPack.JSON, serverTimeMillis = 123456L), null)
        val clock = se.lixo.app.domain.engine.TrustedClock(
            deviceNow = { 0L },
            monotonicNow = { 500L },
            bootId = { "b" }
        )
        val repo = ContentRepository(
            initial = SampleContentPack.activeContent(),
            gateway = gw,
            cache = FakeCache(),
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS },
            trustedClock = clock
        )
        assertFalse(repo.sync())
        assertEquals(123456L, repo.trustedClock.now())
    }

    @Test
    fun loadInitial_withoutGateway_newerCacheWins() = runTest {
        val cache = FakeCache()
        cache.replace(1, 43, Sha256.hex(V43_JSON), ContentPackChunks.split(V43_JSON))
        val repo = ContentRepository.loadInitial(
            gateway = null,
            cache = cache,
            isDebugBuild = true,
            bundledJson = { SampleContentPack.JSON },
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS }
        )
        assertEquals(43, repo.active.value.version)
    }

    @Test
    fun loadInitial_olderCache_bundledWins() = runTest {
        val cache = FakeCache()
        cache.replace(1, 41, Sha256.hex(V41_JSON), ContentPackChunks.split(V41_JSON))
        val repo = ContentRepository.loadInitial(
            gateway = null,
            cache = cache,
            isDebugBuild = true,
            bundledJson = { SampleContentPack.JSON },
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS }
        )
        assertEquals(42, repo.active.value.version)
    }

    @Test
    fun syncIfDue_throttlesWithin15MinutesThenAllowsAfter() = runTest {
        var now = 0L
        val gw = FakeGateway(metaFor(SampleContentPack.JSON), null)
        val repo = ContentRepository(
            initial = SampleContentPack.activeContent(),
            gateway = gw,
            cache = FakeCache(),
            appAchievementIds = { SampleContentPack.ACHIEVEMENT_IDS },
            nowMillis = { now }
        )

        assertFalse(repo.syncIfDue())
        assertEquals(1, gw.metaCalls)

        now += 5 * 60 * 1000L
        assertFalse(repo.syncIfDue())
        assertEquals(1, gw.metaCalls)

        now += 11 * 60 * 1000L // total 16 min since the first attempt
        repo.syncIfDue()
        assertEquals(2, gw.metaCalls)
    }
}
