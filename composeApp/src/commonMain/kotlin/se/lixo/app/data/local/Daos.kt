package se.lixo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LixoDao {

    // --- Profiles ---
    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMillis ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

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

    // --- Wardrobe & Universal Inventory ---
    @Query("SELECT * FROM wardrobe_items ORDER BY unlockStarRequirement ASC")
    fun observeAllWardrobeItems(): Flow<List<WardrobeItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWardrobeItems(items: List<WardrobeItemEntity>)

    @Query("SELECT * FROM unlocked_inventory")
    fun observeUnlockedInventory(): Flow<List<UnlockedInventoryEntity>>

    @Query("SELECT itemId FROM unlocked_inventory")
    suspend fun getUnlockedItemIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlockedItem(entity: UnlockedInventoryEntity)

    // --- Level Progress ---
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

    // --- Friends ---
    @Query("SELECT * FROM friends ORDER BY addedAtEpochMillis DESC")
    fun observeFriends(): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFriend(friend: FriendEntity)

    @Query("DELETE FROM friends WHERE userId = :userId")
    suspend fun deleteFriend(userId: String)

    // --- App Settings & GDPR Controls ---
    @Query("SELECT * FROM app_settings WHERE singletonId = 1 LIMIT 1")
    suspend fun getAppSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppSettings(settings: AppSettingsEntity)
}
