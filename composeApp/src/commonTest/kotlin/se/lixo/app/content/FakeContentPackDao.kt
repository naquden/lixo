package se.lixo.app.content

import se.lixo.app.data.local.ContentPackChunkEntity
import se.lixo.app.data.local.ContentPackDao
import se.lixo.app.data.local.ContentPackMetaEntity

/**
 * In-memory [ContentPackDao] fake shared by [ContentPackCacheTest] and [ContentSyncTest]:
 * `replace` comes from the interface's default [ContentPackDao.replace] implementation, exactly
 * as the Room-generated class would run it.
 */
class FakeContentPackDao : ContentPackDao {
    private var meta: ContentPackMetaEntity? = null
    private val chunks = mutableListOf<ContentPackChunkEntity>()

    override suspend fun getMeta(): ContentPackMetaEntity? = meta

    override suspend fun getChunks(version: Int): List<ContentPackChunkEntity> =
        chunks.filter { it.version == version }.sortedBy { it.idx }

    override suspend fun deleteAllChunks() {
        chunks.clear()
    }

    override suspend fun insertChunks(chunks: List<ContentPackChunkEntity>) {
        this.chunks.addAll(chunks)
    }

    override suspend fun upsertMeta(meta: ContentPackMetaEntity) {
        this.meta = meta
    }
}
