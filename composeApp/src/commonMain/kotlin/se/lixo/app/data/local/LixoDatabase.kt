package se.lixo.app.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        ProfileEntity::class,
        CourseEntity::class,
        LevelEntity::class,
        TaskEntity::class,
        WardrobeItemEntity::class,
        UnlockedInventoryEntity::class,
        LevelProgressEntity::class,
        DailyActivityEntity::class,
        StruggledWordEntity::class,
        MathErrorEntity::class,
        DictionaryEntryEntity::class,
        FriendEntity::class,
        AppSettingsEntity::class,
        LexiconMetaEntity::class
    ],
    version = 5,
    exportSchema = true
)
@ConstructedBy(LixoDatabaseConstructor::class)
abstract class LixoDatabase : RoomDatabase() {
    abstract fun lixoDao(): LixoDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LixoDatabaseConstructor : RoomDatabaseConstructor<LixoDatabase> {
    override fun initialize(): LixoDatabase
}
