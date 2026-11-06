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

val LIXO_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4)
