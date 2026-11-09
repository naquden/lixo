package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import se.lixo.app.data.local.ContentPackChunkEntity
import se.lixo.app.data.local.ContentPackMetaEntity
import se.lixo.app.data.local.RoomContentPackCache
import se.lixo.app.domain.content.ContentPackChunks
import se.lixo.app.domain.content.Sha256

/**
 * Uses [FakeContentPackDao] rather than an in-memory Room database: Room on the Android host
 * test JVM needs a `Context`, which isn't available there.
 */
class ContentPackCacheTest {

    @Test
    fun replaceAndLoad_roundTripsALargeNonAsciiPack() = runTest {
        val dao = FakeContentPackDao()
        val cache = RoomContentPackCache(dao)

        val json = "{\"sv\":\"åäö🙂 \"}".repeat(1) + "x".repeat(2_000_000) + "åäö🙂"
        val chunks = ContentPackChunks.split(json)
        check(chunks.size >= 3) { "expected >= 3 chunks for a ~2 MB pack, got ${chunks.size}" }
        val sha = Sha256.hex(json)

        cache.replace(schema = 1, version = 7, sha256 = sha, chunks = chunks)

        assertEquals(json, cache.load())
    }

    @Test
    fun load_wrongSha_givesNull() = runTest {
        val dao = FakeContentPackDao()
        val cache = RoomContentPackCache(dao)
        cache.replace(schema = 1, version = 1, sha256 = "0".repeat(64), chunks = listOf("hello"))
        assertNull(cache.load())
    }

    @Test
    fun load_missingChunkRow_givesNull() = runTest {
        val dao = FakeContentPackDao()
        dao.upsertMeta(ContentPackMetaEntity(id = 0, schema = 1, version = 1, sha256 = Sha256.hex("ab"), chunkCount = 2))
        dao.insertChunks(listOf(ContentPackChunkEntity(version = 1, idx = 0, data = "a")))
        val cache = RoomContentPackCache(dao)
        assertNull(cache.load())
    }

    @Test
    fun load_wrongSchema_givesNull() = runTest {
        val dao = FakeContentPackDao()
        val json = "hello"
        dao.upsertMeta(ContentPackMetaEntity(id = 0, schema = 2, version = 1, sha256 = Sha256.hex(json), chunkCount = 1))
        dao.insertChunks(listOf(ContentPackChunkEntity(version = 1, idx = 0, data = json)))
        val cache = RoomContentPackCache(dao)
        assertNull(cache.load())
    }

    @Test
    fun replace_twice_leavesOnlyTheLatestVersionRows() = runTest {
        val dao = FakeContentPackDao()
        val cache = RoomContentPackCache(dao)

        cache.replace(schema = 1, version = 2, sha256 = Sha256.hex("v2"), chunks = listOf("v2"))
        cache.replace(schema = 1, version = 3, sha256 = Sha256.hex("v3"), chunks = listOf("v3"))

        assertEquals(0, dao.getChunks(2).size)
        assertEquals(1, dao.getChunks(3).size)
        assertEquals("v3", cache.load())
    }

    @Test
    fun load_emptyCache_givesNull() = runTest {
        val cache = RoomContentPackCache(FakeContentPackDao())
        assertNull(cache.load())
    }

    @Test
    fun split_neverExceedsMaxBytesAndKeepsSurrogatePairsIntact() {
        val json = "x".repeat(2_000_000) + "🙂" // trailing surrogate pair emoji
        val chunks = ContentPackChunks.split(json)
        for (chunk in chunks) {
            assertTrue(chunk.encodeToByteArray().size <= ContentPackChunks.MAX_CHUNK_BYTES)
        }
        assertEquals(json, chunks.joinToString(""))
    }
}
