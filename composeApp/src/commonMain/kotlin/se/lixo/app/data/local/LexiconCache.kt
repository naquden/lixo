package se.lixo.app.data.local

import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.platform.createPlatformLixoDatabase

/**
 * Local on-device cache of the dictionary ("lexicon") downloaded from Firebase. Backed by Room
 * when a platform database is available ([RoomLexiconCache]), and absent (`null`) otherwise, in
 * which case [se.lixo.app.data.repository.LixoRepository] stays purely in-memory.
 */
interface LexiconCache {
    suspend fun loadVersion(): Int?
    suspend fun loadEntries(): List<DictionaryEntry>
    suspend fun replace(version: Int, entries: List<DictionaryEntry>)
}

class RoomLexiconCache(private val dao: LixoDao) : LexiconCache {
    override suspend fun loadVersion(): Int? = dao.getLexiconMeta()?.version

    override suspend fun loadEntries(): List<DictionaryEntry> =
        dao.getAllDictionaryEntries().map { it.toDomain() }

    override suspend fun replace(version: Int, entries: List<DictionaryEntry>) {
        dao.replaceLexicon(version, entries.map { it.toEntity() })
    }
}

private fun DictionaryEntryEntity.toDomain(): DictionaryEntry = DictionaryEntry(
    wordNormalized = wordNormalized,
    displayWord = displayWord,
    syllables = syllables,
    explanationSv = explanationSv,
    exampleSentenceSv = exampleSentenceSv,
    languageIso = languageIso
)

private fun DictionaryEntry.toEntity(): DictionaryEntryEntity = DictionaryEntryEntity(
    wordNormalized = wordNormalized,
    displayWord = displayWord,
    syllables = syllables,
    explanationSv = explanationSv,
    exampleSentenceSv = exampleSentenceSv,
    languageIso = languageIso
)

fun createPlatformLexiconCache(): LexiconCache? =
    createPlatformLixoDatabase()?.let { RoomLexiconCache(it.lixoDao()) }
