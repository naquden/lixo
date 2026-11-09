# 015 — Add new wardrobe items from the backend without an app release

**Type**: feature
**Depends on**: none (independent of 014; if 014 lands first, reuse its achievement id list in the validation step).

## Goal
New wardrobe items (e.g. new chest legendaries to refill the pool after 014 reserves 9) can be published to Firestore at any time. The app downloads them, caches them on the device, and makes them obtainable from regular chests. No app release needed.

## Context
- **The catalog is bundled only.** `LixoRepository` seeds `_wardrobeCatalog` from `buildSeedWardrobeCatalog()` (`data/repository/Repositories.kt:311`, `:789`): handmade items + `GENERATED_WARDROBE_ITEMS` (`GeneratedWardrobeCatalog.kt`, from `tools/item_pipeline/generate_wardrobe_items.py`). Nothing fetches items remotely. `CloudSyncGateway` (`data/remote/FirebaseSyncService.kt:113`) only has courses/levels/tasks/lexicon.
- **Rendering is data-driven.** `Avatar3DController` draws by `item.styleIndex.mod(8)` + colors/pattern/ornament on the shared `3d_sample.glb` (`ui/components/avatar3d/Avatar3DController.kt:524` etc.). So a remote item that uses the existing fields renders without new code.
- **Consumers read the live catalog:** `LixoApp.kt:103` (`wardrobeCatalog` → `catalogById` `:129`, `allWardrobeItems` for runs `:219/:277`, wardrobe `:401`). Chest/gift pools filter with `ProgressionAndGiftEngine.isChestEligible` (`ProgressionAndGiftEngine.kt:104`), so remote items join the pools automatically once they're in the catalog.
- **Hazard:** `syncCurriculumFromCloud` builds the level reward pool from `_wardrobeCatalog.value` (`Repositories.kt:739`). If remote items are merged in, the level → reward item mapping shifts for newly synced tasks. The level reward pool must stay **bundled-only**.
- **Pattern to copy:** the lexicon sync. `lexicon/meta {version, chunkCount}` + `lexicon/chunk_N {entries}` (`FirebaseSyncService.kt:133-136, :229-247`), Room cache `LexiconCache`/`RoomLexiconCache` (`data/local/LexiconCache.kt`), `loadCachedLexicon()` before `syncCurriculumFromCloud()` (`Repositories.kt:667-700`, called at `LixoApp.kt:116-117`). Upload is in `tools/content_pipeline/upload_content.py` (`_to_firestore_value`, `_load_firebase_cli_access_token`, batched `documents:commit`, `--validate-only/--dry-run`).
- **Room:** the `wardrobe_items` table/`WardrobeItemEntity` exists (`data/local/Entities.kt:72`) with DAO `observeAllWardrobeItems`/`upsertWardrobeItems` (`Daos.kt:49-53`), all unused. DB is version 6 (`LixoDatabase.kt:25`), with migrations in `LixoMigrations.kt`.
- **Rules:** `firebase/firestore.rules` denies every collection that isn't listed.
- **Inventory** stores item ids only, so an owned remote item must stay in the catalog forever or it can't be shown.

## Design decisions
- **Remote = additions only.** The app ignores remote entries whose id matches a bundled item, so balance, achievement rewards and level rewards stay app-owned.
- **Never delete.** Sync upserts. Taking an item out of drops is done with `retired = true`: a retired item is excluded from all drop pools but stays renderable for owners. A remote item missing from a newer upload is treated as still existing.
- **Validate on device, skip bad items** (never crash or drop the whole batch). Decode each item separately from `JsonElement` so one item with an unknown enum doesn't fail the chunk.
- **`minAppVersionCode`**: items that need newer app code (e.g. a future style 8+) are skipped by old apps.

## Steps
1. **Model** (`domain/model/Models.kt` `WardrobeItem`): add `retired: Boolean = false`, `minAppVersionCode: Int = 0`. In `ProgressionAndGiftEngine.isChestEligible` (and any star/gift/onboarding pool filter that doesn't go through it) also require `!item.retired`.
2. **Gateway** (`FirebaseSyncService.kt`): add `@Serializable data class WardrobeMeta(val version: Int, val chunkCount: Int)` and `@Serializable private data class WardrobeChunkDoc(val items: List<JsonElement>)`. Add `CloudSyncGateway.fetchWardrobeMeta(): WardrobeMeta?` and `fetchWardrobeItems(chunkCount: Int): List<JsonElement>` reading `wardrobe/meta` and `wardrobe/chunk_N`, with try/catch → null/empty like the lexicon. Public read, so it also works in Local-Only mode (same as curriculum).
3. **Validation** (new pure `domain/engine/RemoteWardrobeValidator.kt`): `fun accept(raw: List<JsonElement>, bundledIds: Set<String>, knownAchievementIds: Set<String>, appVersionCode: Int): List<WardrobeItem>`. Rules: decodes (lenient json, `ignoreUnknownKeys`); id matches `^item_[a-z0-9_]+$`; not in `bundledIds`; no duplicate in the batch (first wins); `styleIndex in 0..7`; `meshAssetPath == "3d_sample.glb"`; `scaleMultiplier in 0.5f..2f`; `unlockAchievementId` is null or in `knownAchievementIds`; `isWeeklyStreakSetPiece == false`; `minAppVersionCode <= appVersionCode`; nameSv non-blank. Log skipped items with their reason (`println("LixoWardrobeSync: skip <id>: <reason>")`, like `AchievementEngine`). The app version code comes from a new `expect`/platform value, or `BuildConfig.VERSION_CODE` via `AndroidPlatformContext`; on iOS read `CFBundleVersion`.
4. **Cache** (new `data/local/WardrobeCatalogCache.kt`, mirroring `LexiconCache`): `interface WardrobeCatalogCache { suspend fun loadVersion(): Int?; suspend fun loadItems(): List<WardrobeItem>; suspend fun upsert(version: Int, items: List<WardrobeItem>) }`. The Room impl stores **remote items only** in the existing `wardrobe_items` table: add `@ColumnInfo(defaultValue="0") retired`, `minAppVersionCode` columns. Add a new `wardrobe_catalog_meta(id INTEGER PK = 0, version INTEGER)` entity + `@Transaction upsertRemoteCatalog`. **DB 6 → 7 migration** in `LixoMigrations.kt`: `ALTER TABLE wardrobe_items ADD COLUMN retired INTEGER NOT NULL DEFAULT 0`, `... minAppVersionCode INTEGER NOT NULL DEFAULT 0`, `CREATE TABLE wardrobe_catalog_meta`. Entity ↔ model mappers go next to the cache, like `LexiconCache.kt`. `createPlatformWardrobeCatalogCache()`. Inject it as a `LixoRepository` constructor param defaulting to the platform one, same as `lexiconCache`.
5. **Repository** (`Repositories.kt`):
   - Keep `private val bundledCatalog: List<WardrobeItem>` (the seed) and `private var remoteItems: Map<String, WardrobeItem>`. `_wardrobeCatalog.value = bundledCatalog + remoteItems.values.sortedBy { it.id }`.
   - `buildLevelRewardPool(...)` at `:739` (and in the seed `:975`) uses `bundledCatalog`, never the merged list. Add a comment pointing at this plan.
   - `suspend fun loadCachedWardrobe()` loads the cache → re-validates the items (rules may have tightened in an app update, e.g. a new bundled item now shares an id) → merges.
   - `private suspend fun syncWardrobeFromCloud(): Boolean`: fetch meta; return when `meta.version == wardrobeVersion` or `chunkCount <= 0`; fetch → validate → `remoteItems = remoteItems + accepted.associateBy(id)` (upsert, never remove) → cache upsert (failure doesn't block the in-memory update) → merge. Call it from `syncCurriculumFromCloud()` independently of course sync, next to `syncLexiconFromCloud()`, and include it in the return value.
   - `LixoApp.kt:116`: call `repository.loadCachedWardrobe()` before `syncCurriculumFromCloud()`.
6. **Owned-but-unknown safety**: anywhere `catalogById[id]` is looked up for an owned id (wardrobe grid, equipped appearance, celebration), a missing id is skipped silently, not `!!`. Audit with `grep -n "catalogById\[" ui/` and fix any non-null assertions.
7. **Firestore rules** (`firebase/firestore.rules`): add `match /wardrobe/{docId} { allow read: if true; allow write: if false; }` next to lexicon. Deploying the rules is the user's step (`firebase deploy --only firestore:rules`). Note it in the report.
8. **Item pipeline**:
   - Source of truth: `tools/item_pipeline/remote_items/remote_wardrobe_items.json` (`{"version": N, "items": [WardrobeItem JSON...]}`), written by hand or by a generator helper.
   - Add a `--remote-legendaries N` mode to `generate_wardrobe_items.py`. It appends N new LEGENDARY items per slot to that json, with ids/names/visuals from the same helpers as the main loop, a separate rng `f"{seed}:{slot}:remote:{version}"`, and skips `(style, colorway)` combos already used in the bundled catalog or the json. It must never touch `GeneratedWardrobeCatalog.kt` or the bundled fixture.
   - New `tools/item_pipeline/upload_wardrobe_items.py`. It reuses `_to_firestore_value`, `_load_firebase_cli_access_token` and the batch commit from `tools/content_pipeline/upload_content.py` (import, don't copy). It validates with the same rules as step 3 (plus: no id clash with the bundled catalog, read from `generate_catalog()` + the handmade ids). It writes `wardrobe/chunk_N` (200 items per doc) + `wardrobe/meta {version, chunkCount}`, and has `--validate-only` / `--dry-run`. Upload chunks first, meta last, so clients never see a meta whose chunks are missing.
9. **Docs**: KDoc on `LixoRepository.syncWardrobeFromCloud` and a short "Adding items without a release" section in the module docstring of `upload_wardrobe_items.py` (generate → validate → upload → bump version).

## Tests / verification
commonTest (`LixoEnginesTest.kt` sync tests use `object : CloudSyncGateway by FirebaseSyncService()` fakes, see `:1751`; add an in-memory `WardrobeCatalogCache` fake):
- `RemoteWardrobeValidator`: accepts a valid legendary. Rejects: a bundled id clash, `styleIndex = 9`, an unknown slot string (that item skipped, the others kept), an unknown `unlockAchievementId`, `isWeeklyStreakSetPiece = true`, `minAppVersionCode` above the current version, a duplicate id in the batch (first kept).
- Sync: the remote version is newer → the item appears in `wardrobeCatalog`. The same version → `fetchWardrobeItems` is not called. Offline (null meta) → no change, no crash.
- Upsert-only: version 2 that omits an item from version 1 → the item is still in the catalog. `retired = true` → still in the catalog, but `isChestEligible == false` and never rolled.
- Cache: after sync, a new repository with the same cache + an offline gateway → `loadCachedWardrobe()` restores the item.
- Level rewards stable: a level → `rewardItemId` map from before a remote sync equals the one after (both seed and synced tasks).
- Chest pool: a remote LEGENDARY is among the candidates `rollChest` can return for LEGENDARY (seeded rng or candidate-list assertion), and the per-rarity collection counts include it.
- Room migration 6 → 7: an existing DB keeps its profiles/inventory and the new columns default (follow the `ProfileStoreTest` / `ProfilePersistenceTest` migration pattern).

pytest (`tools/tests/test_pipelines.py`):
- The `--remote-legendaries` output is deterministic, ids don't clash with the bundled catalog or each other, there are no duplicate (style, colorway) per slot, and `test_ids_names_rarities_unchanged` (bundled fixture) still passes.
- The upload validator rejects the same bad cases as the Kotlin validator, and `build_writes` emits meta last with the right `chunkCount`.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/RemoteWardrobeItemTest.kt`, `AchievementTrackingTest` pattern, real `LixoRepository` with a fake `CloudSyncGateway` returning one new LEGENDARY, real Room cache):
- `remoteLegendary_appearsInWardrobeWhenOwned`: sync, unlock the item, render `WardrobeScreen` in `LixoTheme`, then `onNodeWithText(<nameSv>)` exists. Capture a screenshot of it rendered on the avatar.
- `remoteItem_survivesColdStart`: sync, then a new repository on the same DB with an offline gateway → the item is still in the catalog and rendered.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.RemoteWardrobeItemTest,se.lixo.app.verify.ProfilePersistenceTest`

## Open questions
- None blocking. Deploying the Firestore rules and the first real upload are manual steps for the user, not part of the workflow.
