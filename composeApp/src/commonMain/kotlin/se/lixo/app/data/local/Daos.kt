package se.lixo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LixoDao {

    // --- Profiles ---
    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMillis ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMillis ASC")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    // --- Courses, Levels & Tasks ---
    @Query("SELECT * FROM courses")
    suspend fun getAllCourses(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(courses: List<CourseEntity>)

    @Query("SELECT * FROM levels ORDER BY `order` ASC")
    fun observeCampaignLevels(): Flow<List<LevelEntity>>

    @Query("SELECT * FROM levels WHERE id = :levelId LIMIT 1")
    suspend fun getLevelById(levelId: String): LevelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLevels(levels: List<LevelEntity>)

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    // --- Universal Inventory ---
    @Query("SELECT * FROM unlocked_inventory")
    fun observeUnlockedInventory(): Flow<List<UnlockedInventoryEntity>>

    @Query("SELECT itemId FROM unlocked_inventory")
    suspend fun getUnlockedItemIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlockedItem(entity: UnlockedInventoryEntity)

    // --- Level Progress ---
    @Query("SELECT * FROM level_progress")
    suspend fun getAllLevelProgress(): List<LevelProgressEntity>

    @Query("SELECT * FROM level_progress WHERE profileId = :profileId")
    fun observeLevelProgress(profileId: String): Flow<List<LevelProgressEntity>>

    @Query("SELECT * FROM level_progress WHERE profileId = :profileId AND levelId = :levelId LIMIT 1")
    suspend fun getLevelProgress(profileId: String, levelId: String): LevelProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLevelProgress(progress: LevelProgressEntity)

    // --- Daily Activity & Streak ---
    @Query("SELECT * FROM daily_activity_log WHERE profileId = :profileId ORDER BY calendarDateIso DESC")
    fun observeDailyActivity(profileId: String): Flow<List<DailyActivityEntity>>

    @Query("SELECT * FROM daily_activity_log WHERE profileId = :profileId AND calendarDateIso = :dateIso LIMIT 1")
    suspend fun getDailyActivity(profileId: String, dateIso: String): DailyActivityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyActivity(activity: DailyActivityEntity)

    // --- Local Pedagogical Logs (Struggled Words & Math Errors) ---
    @Query("SELECT * FROM struggled_words_log WHERE profileId = :profileId ORDER BY encounterCount DESC, lastEncounteredEpochMillis DESC")
    fun observeStruggledWords(profileId: String): Flow<List<StruggledWordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStruggledWord(entity: StruggledWordEntity)

    @Query("SELECT * FROM math_error_log WHERE profileId = :profileId ORDER BY timestampEpochMillis DESC")
    fun observeMathErrors(profileId: String): Flow<List<MathErrorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMathError(entity: MathErrorEntity)

    // --- Offline Swedish Dictionary ---
    @Query("SELECT * FROM dictionary_entries WHERE wordNormalized = :wordNormalized LIMIT 1")
    suspend fun getDictionaryEntry(wordNormalized: String): DictionaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDictionaryEntries(entries: List<DictionaryEntryEntity>)

    @Query("SELECT * FROM dictionary_entries")
    suspend fun getAllDictionaryEntries(): List<DictionaryEntryEntity>

    @Query("DELETE FROM dictionary_entries")
    suspend fun deleteAllDictionaryEntries()

    @Query("SELECT * FROM lexicon_meta WHERE singletonId = 1 LIMIT 1")
    suspend fun getLexiconMeta(): LexiconMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLexiconMeta(meta: LexiconMetaEntity)

    @Transaction
    suspend fun replaceLexicon(version: Int, entries: List<DictionaryEntryEntity>) {
        deleteAllDictionaryEntries()
        upsertDictionaryEntries(entries)
        upsertLexiconMeta(LexiconMetaEntity(version = version, entryCount = entries.size))
    }

    // --- Friends (doc/plans/030 §5) ---
    @Query("SELECT * FROM friends ORDER BY addedAtEpochMillis DESC")
    suspend fun getAllFriends(): List<FriendEntity>

    @Query("SELECT * FROM friends WHERE profileId = :profileId ORDER BY addedAtEpochMillis DESC")
    fun observeFriends(profileId: String): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: FriendEntity)

    @Query("DELETE FROM friends WHERE profileId = :profileId AND friendUid = :friendUid")
    suspend fun deleteFriend(profileId: String, friendUid: String)

    /** Updates the card-derived fields only; never touches [FriendEntity.label] (device-only name). */
    @Query(
        "UPDATE friends SET nickJson = :nickJson, appearanceJson = :appearanceJson " +
            "WHERE profileId = :profileId AND friendUid = :friendUid"
    )
    suspend fun updateFriendCard(profileId: String, friendUid: String, nickJson: String, appearanceJson: String)

    @Query("UPDATE friends SET label = :label WHERE profileId = :profileId AND friendUid = :friendUid")
    suspend fun setLabel(profileId: String, friendUid: String, label: String?)

    @Query(
        "UPDATE friends SET revealedAtEpochMillis = :revealedAtEpochMillis " +
            "WHERE profileId = :profileId AND friendUid = :friendUid"
    )
    suspend fun markRevealed(profileId: String, friendUid: String, revealedAtEpochMillis: Long)

    // --- Friend removal tombstones (doc/plans/030 review M2) ---
    @Query("SELECT * FROM friend_removal_tombstones")
    suspend fun getAllFriendRemovalTombstones(): List<FriendRemovalTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriendRemovalTombstone(tombstone: FriendRemovalTombstoneEntity)

    @Query("DELETE FROM friend_removal_tombstones WHERE profileId = :profileId AND friendUid = :friendUid")
    suspend fun deleteFriendRemovalTombstone(profileId: String, friendUid: String)

    // --- App Settings & GDPR Controls ---
    @Query("SELECT * FROM app_settings WHERE singletonId = 1 LIMIT 1")
    suspend fun getAppSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppSettings(settings: AppSettingsEntity)
}

/**
 * Content-pack cloud sync (024) cache. A separate DAO (rather than folding into [LixoDao]) so
 * [se.lixo.app.data.local.ContentPackCache] tests can fake it without implementing the whole
 * (much larger) [LixoDao] interface.
 */
@Dao
interface ContentPackDao {

    @Query("SELECT * FROM content_pack_meta WHERE id = 0 LIMIT 1")
    suspend fun getMeta(): ContentPackMetaEntity?

    @Query("SELECT * FROM content_pack_chunks WHERE version = :version ORDER BY idx ASC")
    suspend fun getChunks(version: Int): List<ContentPackChunkEntity>

    @Query("DELETE FROM content_pack_chunks")
    suspend fun deleteAllChunks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ContentPackChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: ContentPackMetaEntity)

    @Transaction
    suspend fun replace(meta: ContentPackMetaEntity, chunks: List<ContentPackChunkEntity>) {
        deleteAllChunks()
        insertChunks(chunks)
        upsertMeta(meta)
    }
}
