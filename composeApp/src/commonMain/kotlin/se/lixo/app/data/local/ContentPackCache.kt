package se.lixo.app.data.local

import se.lixo.app.domain.content.ContentPackChunks
import se.lixo.app.domain.content.SUPPORTED_SCHEMA_VERSION
import se.lixo.app.platform.createPlatformLixoDatabase

/**
 * Local on-device cache of the last content pack downloaded from the cloud (024). Backed by Room
 * when a platform database is available ([RoomContentPackCache]), and absent (`null`) otherwise,
 * in which case [se.lixo.app.data.repository.ContentRepository] simply never has a cache to load
 * from or write to -- mirrors [LexiconCache].
 */
interface ContentPackCache {
    /** The cached pack's JSON, or null if there is no cache, it is for another schema, a chunk
     * row is missing, or the joined chunks fail their sha256 check. */
    suspend fun load(): String?

    /** Replaces the whole cache with [chunks] (in order) for [schema]/[version], verified by [sha256]. */
    suspend fun replace(schema: Int, version: Int, sha256: String, chunks: List<String>)
}

class RoomContentPackCache(private val dao: ContentPackDao) : ContentPackCache {

    override suspend fun load(): String? {
        val meta = dao.getMeta() ?: return null
        if (meta.schema != SUPPORTED_SCHEMA_VERSION) return null
        val chunks = dao.getChunks(meta.version)
        if (chunks.map { it.idx } != (0 until meta.chunkCount).toList()) return null
        return ContentPackChunks.joinVerified(chunks.map { it.data }, meta.sha256)
    }

    override suspend fun replace(schema: Int, version: Int, sha256: String, chunks: List<String>) {
        dao.replace(
            ContentPackMetaEntity(id = 0, schema = schema, version = version, sha256 = sha256, chunkCount = chunks.size),
            chunks.mapIndexed { i, data -> ContentPackChunkEntity(version = version, idx = i, data = data) }
        )
    }
}

fun createPlatformContentPackCache(): ContentPackCache? =
    createPlatformLixoDatabase()?.let { RoomContentPackCache(it.contentPackDao()) }
