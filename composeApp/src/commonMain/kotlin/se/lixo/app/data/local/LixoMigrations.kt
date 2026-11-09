package se.lixo.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Explicit schema migrations for [LixoDatabase]. Every `version` bump in [LixoDatabase] MUST add a
 * matching Migration here -- there is no destructive fallback, user data must survive upgrades.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `styleIndex` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `secondaryColorHex` INTEGER NOT NULL DEFAULT 4294967295")
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `pattern` TEXT NOT NULL DEFAULT 'NONE'")
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `ornament` TEXT NOT NULL DEFAULT 'NONE'")
    }
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `achievementProgressJson` TEXT NOT NULL DEFAULT '{}'")
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `achievementCountedLevelIdsJson` TEXT NOT NULL DEFAULT '{}'")
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `unlockedAchievementIdsJson` TEXT NOT NULL DEFAULT '[]'")
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `pendingAchievementRewardIdsJson` TEXT NOT NULL DEFAULT '[]'")
    }
}

val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `chestsSinceRarePlus` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `chestsSinceEpicPlus` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `profiles` ADD COLUMN `chestsSinceLegendary` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds remote-wardrobe-item support (`doc/plans/015-remote-wardrobe-items.md`).
 * `appVersionCode` on `wardrobe_catalog_meta` was added by plan 018 while v7 was still unreleased.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `retired` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `wardrobe_items` ADD COLUMN `minAppVersionCode` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `wardrobe_catalog_meta` (`singletonId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `appVersionCode` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`singletonId`))"
        )
    }
}

val LIXO_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
