# 024 — Cloud sync of the content pack (Firestore), cache, hot-swap, upload tool

**Type**: feature
**Depends on**: 022 (can run in parallel with 023)

## Goal
The app downloads newer content packs from Firestore, validates them, caches them on the device, and swaps them in at runtime. Publishing new avatars, items, events or rules is then only an upload. This adds the uploader with its stability checks.

## Context
- **Lexicon/wardrobe meta+chunk pattern:** `FirebaseSyncService.kt` (lexicon :133–136, :229–247), `CloudSyncGateway` :113, `LexiconCache`/`RoomLexiconCache` in `data/local/LexiconCache.kt`, and sync triggered from `syncCurriculumFromCloud()` (`Repositories.kt`), called at startup in `LixoApp.kt`.
- `ContentRepository` (022) loads only the bundled pack today.
- Upload helpers in `tools/content_pipeline/upload_content.py`: `_load_firebase_cli_access_token`, batched `documents:commit`, `--validate-only`/`--dry-run`. The pack sources and `validate_pack.py` (with the stability diff) come from 021.
- `firebase/firestore.rules` denies every collection that isn't listed; the `wardrobe` rule was removed in 022.
- Android CursorWindow is 2 MB, so every Room row stays under 1 MB.

## Steps
1. **Firestore layout:**
   - `content/meta` = `{ packs: { "1": { version, chunkCount, sha256, bytes, publishedAt } } }`. Packs are keyed by schemaVersion: an app reads only `packs[SUPPORTED_SCHEMA_VERSION]`, so future schema-2 apps and schema-1 apps can coexist.
   - `content_chunks/{schema}_{version}_{i}` = `{ data: String }`: the canonical pack JSON (021) split into slices of about 900 KB. The concatenation is byte-identical to `out/content_pack.json`.
2. **Gateway** (`CloudSyncGateway` + `FirebaseSyncService`):
   - `fetchContentMeta(): ContentMetaResult?` returns the meta entry for the supported schema, **plus the server time**. Use the Firestore read time or snapshot metadata if available; otherwise read the HTTP `Date` header in the REST fallback. 025 uses this time.
   - `fetchContentChunks(schema, version, count): List<String>?` returns null if any chunk is missing.
   - Both are public reads that also work in Local-Only mode, and both swallow errors and return null.
3. **Cache** `data/local/ContentPackCache.kt`:
   - Room entities `ContentPackMetaEntity(id=0, schema, version, sha256)` and `ContentPackChunkEntity(version, idx, data)`, PK (version, idx)
   - `@Transaction replace(meta, chunks)` deletes the old chunks
   - `suspend fun load(): String?` concatenates the chunks and checks sha256
   - add the entities to `LixoDatabase` (it is v1 after 022: bump to 2 with destructive fallback, or edit v1 in place since nothing is released; prefer editing v1 in place)
   - sha256: use an existing multiplatform hash if the project has one; otherwise add a small pure-Kotlin SHA-256 in `domain/content/Sha256.kt` with a test vector
4. **`ContentRepository`:**
   - `loadInitial()`: if the cache loads and validates, and its version > the bundled version, use the cache; otherwise use the bundled pack.
   - `sync(): Boolean`:
     1. Fetch meta; return false if it is null or `version <= active.version`.
     2. Fetch the chunks; check the count and sha256 against the meta.
     3. Run `ContentPackValidator.validate(json, appAchievementIds, previous = active.value)`. On Rejected, log and keep the current pack.
     4. Write to the cache (a failure is logged and doesn't block).
     5. `_active.value = ActiveContent.from(pack)`.
   - Call `sync()` from the startup sync, next to the curriculum sync, and on app resume (at most once per 15 min).
   - KDoc on `sync` explains the publish flow.
5. **Hot swap:** every screen already collects `content.active` (022). Runs use their frozen snapshot (023).
6. **Firestore rules:** `match /content/{doc} { allow read: if true; allow write: if false; }` and the same for `/content_chunks/{doc}`. Deploying them is a manual user step (`firebase deploy --only firestore:rules`); say so in the report.
7. **Uploader** `tools/content_pipeline/pack/upload_pack.py`:
   - Run `validate_pack.py` first, including the stability diff against `published/`.
   - Chunk the pack, compute the sha256, and build the writes: **all chunks first, meta last**, in separate commits if needed.
   - `--dry-run` prints the writes; `--validate-only` stops after validation.
   - After a successful upload, copy the pack to `published/pack_v<N>.json` and delete chunk docs older than the last 3 versions.
   - Reuse the helpers from `upload_content.py` by importing them, not copying.
   - Add an "Upload" section to the README.

## Tests / verification
commonTest (fake gateway `object : CloudSyncGateway by FirebaseSyncService()` overriding the two methods; in-memory `ContentPackCache` fake):
- `ContentSyncTest`:
  - a newer version activates and is cached
  - the same version → `fetchContentChunks` is not called
  - a missing chunk, a bad sha256, a validator rejection (an item id removed vs active), or meta for another schema each keep the current pack
  - offline (null meta) → no change
  - a cache newer than the bundled pack wins at `loadInitial`; a cache older than the bundled pack (after an app update) loses
- `ContentPackCacheTest` (Room in-memory if available on the host test, otherwise the fake): replace and load round-trip; chunks of 900 KB.
- `Sha256Test`: the "abc" and empty-string vectors.

pytest:
- Chunking concatenates back to the original bytes.
- The writes put meta last with the right `chunkCount`/sha256.
- A stability-diff failure blocks the upload.
- Pruning keeps 3 versions.

Instrumented:
- `se.lixo.app.verify.ContentHotSwapTest`: a real `ContentRepository` + Room cache; the fake gateway serves the bundled pack + 1 new item (version + 1). Show `WardrobeScreen`, call `sync()`, then `onNodeWithText(<new item name>)` exists (after unlocking it). Restart with a new repository and an offline gateway: the item is still there (cache). Screenshot to `temp/verify024/`.

Run:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ContentHotSwapTest
```

## Open questions
- None blocking. Deploying the rules and the first real upload are manual user steps.
