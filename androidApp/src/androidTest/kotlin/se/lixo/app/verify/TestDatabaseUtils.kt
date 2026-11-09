package se.lixo.app.verify

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * For instrumented tests only: turns a v7 test database back into the v6 schema (drops the
 * `wardrobe_catalog_meta` table and the `retired`/`minAppVersionCode` columns added by
 * `MIGRATION_6_7`, then sets `PRAGMA user_version = 6`), so that reopening the same file through
 * Room on the next call runs the real `MIGRATION_6_7` plus Room's schema validation
 * (`doc/plans/015-remote-wardrobe-items.md`). There is no room-testing artifact on the androidTest
 * classpath, so this uses a raw [BundledSQLiteDriver] connection instead.
 */
fun rewindAndroidTestDatabaseToVersion6(context: Context, dbFileName: String) {
    val path = context.getDatabasePath(dbFileName).absolutePath
    val connection = BundledSQLiteDriver().open(path)
    try {
        connection.execSQL("DROP TABLE IF EXISTS `wardrobe_catalog_meta`")
        connection.execSQL("ALTER TABLE `wardrobe_items` DROP COLUMN `retired`")
        connection.execSQL("ALTER TABLE `wardrobe_items` DROP COLUMN `minAppVersionCode`")
        connection.execSQL("PRAGMA user_version = 6")
    } finally {
        connection.close()
    }
}
