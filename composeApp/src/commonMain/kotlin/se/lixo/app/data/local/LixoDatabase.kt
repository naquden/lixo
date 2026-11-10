package se.lixo.app.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * 022 reset the schema to version 1: the whole wardrobe-item table pair and the old
 * `AvatarType`-keyed CSV columns are gone, and nothing has shipped on this schema yet, so both
 * builders ([se.lixo.app.platform]) use a destructive fallback (upgrade AND downgrade) instead of
 * an explicit [LIXO_MIGRATIONS] migration. Add a real migration here once a release ships on v1+.
 *
 * 024 added `content_pack_meta`/`content_pack_chunks` to v1 **in place** (same reasoning: nothing
 * has shipped on v1 yet). A device that already installed a pre-024 v1 build hits Room's
 * identity-hash check on the first query; `adb shell pm clear se.lixo.app` before verifying.
 */
@Database(
    entities = [
        ProfileEntity::class,
        CourseEntity::class,
        LevelEntity::class,
        TaskEntity::class,
        UnlockedInventoryEntity::class,
        LevelProgressEntity::class,
        DailyActivityEntity::class,
        StruggledWordEntity::class,
        MathErrorEntity::class,
        DictionaryEntryEntity::class,
        FriendEntity::class,
        FriendRemovalTombstoneEntity::class,
        AppSettingsEntity::class,
        LexiconMetaEntity::class,
        ContentPackMetaEntity::class,
        ContentPackChunkEntity::class
    ],
    version = 1,
    exportSchema = true
)
@ConstructedBy(LixoDatabaseConstructor::class)
abstract class LixoDatabase : RoomDatabase() {
    abstract fun lixoDao(): LixoDao
    abstract fun contentPackDao(): ContentPackDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LixoDatabaseConstructor : RoomDatabaseConstructor<LixoDatabase> {
    override fun initialize(): LixoDatabase
}
