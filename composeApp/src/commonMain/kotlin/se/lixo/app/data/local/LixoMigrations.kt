package se.lixo.app.data.local

import androidx.room.migration.Migration

/**
 * Explicit schema migrations for [LixoDatabase]. The DB schema was reset to version 1 in 022
 * (dropping the wardrobe-item tables and the `AvatarType`-keyed CSV columns); both platform
 * builders use a destructive fallback (upgrade AND downgrade) until a release ships on this
 * schema. Add a real `Migration` here once that happens.
 */
val LIXO_MIGRATIONS: Array<Migration> = emptyArray()
