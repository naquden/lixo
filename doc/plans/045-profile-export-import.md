# 045 — Parent view: export / import child profile to a user-picked file

**Type**: feature

**Goal**: In the parent view (after the math gate), a parent can export the active child profile to a file at a location they pick with the system file picker (Android Storage Access Framework / iOS document picker), and import such a file later to restore the profile (same device, or a new device / after reinstall).

## Context

- Parent view: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/parent/ParentGateAndDashboardScreen.kt:67` (signature, callbacks), Friends card `:596`, GDPR/privacy card `:655` (the email/account block inside it is hidden behind `EMAIL_BACKUP_FEATURE_ENABLED`). Wired in `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:584`.
- Persisted data (Room, `composeApp/src/commonMain/kotlin/se/lixo/app/data/local/`):
  - `ProfileEntity` (`Entities.kt:7`) ↔ `ChildProfile` via `ProfileMappers.kt` (`profileJson` at `ProfileMappers.kt:17`).
  - `LevelProgressEntity` per profile (`Entities.kt:76`).
  - `UnlockedInventoryEntity` — **universal pool, not per profile** (`Entities.kt:71`).
  - `FriendEntity` / tombstones / `AppSettingsEntity.cloudUid` — bound to this device's Firebase anonymous uid.
- `ProfileStore` interface + `RoomProfileStore` (`ProfileStore.kt:27`). Repository state: `LixoRepository` (`data/repository/Repositories.kt:96`), flows `_profiles` `:123`, `_unlockedItemIds` `:143`, `_levelProgressByProfile` `:146`; writes go through `enqueueProfileWrite` `:362` / `updateProfiles` `:372`; `selectProfile` `:456`, `updateProfile` `:480`; `ensureCloudIdentity` `:695` already resets friend state when the persisted uid doesn't match (041 review HIGH 1).
- Daily activity, struggled words and math errors (`saveDailyActivity` `:605`, `logStruggledWord`, math errors) are **in-memory only today** — not in Room, so they are not part of the export (see open questions).
- Platform pattern for "a system UI that returns a result": `QrScanner` interface + `expect fun createPlatformQrScanner()` (`platform/PlatformContracts.kt:192-200`), passed as a plain parameter so tests can fake it. Android activity-result plumbing lives in `AndroidPlatformContext` (`androidMain/.../platform/PlatformAndroid.kt:59`, record-audio requester) + `androidApp/src/main/.../MainActivity.kt:21-45` (`registerForActivityResult`). iOS presents view controllers via `topViewController()` in `iosMain/.../platform/IosQrScanner.kt:84-101`.
- 041 (`doc/plans/041-allow-backup-device-transfer.md`): cloud backup deliberately excludes child data; this export is a user-initiated local file, so it doesn't contradict that — the parent chooses where the file goes.

## Steps

1. **Export format** — new `composeApp/src/commonMain/kotlin/se/lixo/app/data/local/ProfileBackup.kt`:
   ```kotlin
   @Serializable data class ProfileBackup(
       val format: String = "lixo-profile-backup",
       val schemaVersion: Int = 1,
       val exportedAtEpochMillis: Long,
       val appVersion: String?,          // from PlatformFeatureFlags if available, else null
       val profile: ProfileEntity-like DTO (all ChildProfile fields, friendCardId stripped),
       val levelProgress: List<LevelProgressDto>,
       val unlockedItems: List<UnlockedItemDto>   // itemId, unlockedAtEpochMillis, unlockedBySource
   )
   ```
   Use dedicated `@Serializable` DTOs (don't serialize Room entities directly). Exclude: friends, tombstones, `friendCardId`, `cloudUid`, app settings, content pack, lexicon. Keep `nicknameJson` (it's only the name shown to friends; a fresh card is created later).
   - `fun encodeProfileBackup(b: ProfileBackup): String` (pretty JSON via `profileJson`-style `Json { ignoreUnknownKeys = true; encodeDefaults = true }`).
   - `fun decodeProfileBackup(text: String): Result<ProfileBackup>` — fails with a typed error (`ProfileBackupError.NotABackup`, `.UnsupportedVersion(v)`, `.Corrupt`) when `format` mismatches, `schemaVersion > 1`, JSON invalid, or `profile.id`/`displayName` blank.
2. **Repository** (`Repositories.kt`):
   - `fun buildProfileBackup(profileId: String): ProfileBackup?` — from in-memory `_profiles`, `_levelProgressByProfile[profileId]`, and the unlocked pool (needs `unlockedAtEpochMillis`/`source`: add `ProfileStore.loadUnlockedItems(): List<UnlockedItem>` + DAO `@Query("SELECT * FROM unlocked_inventory") suspend fun getUnlockedInventory()`; when no store, fall back to ids with `0L`/`"UNKNOWN"`). Make it `suspend`.
   - `suspend fun importProfileBackup(backup: ProfileBackup, mode: ImportMode): Result<ChildProfile>` where `ImportMode = REPLACE_EXISTING | AS_NEW`:
     - `REPLACE_EXISTING` (profile id already exists): overwrite profile (keep the existing local `friendCardId`/`nicknameJson`? → no: keep local `friendCardId` + friends so friends aren't orphaned; take everything else from the backup), replace that profile's level progress (delete existing rows for profileId first — add DAO `@Query("DELETE FROM level_progress WHERE profileId = :profileId")` + `ProfileStore.replaceLevelProgress(profileId, list)` in a `@Transaction`).
     - `AS_NEW` (id not present): insert with the backup id, `friendCardId = null`, no friends.
     - Unlocked items: **union** into the universal pool (`insertUnlockedItem` is already `IGNORE`), never remove.
     - Update `_profiles`, `_levelProgressByProfile`, `_unlockedItemIds` in memory, persist via `enqueueProfileWrite`, then `selectProfile(imported.id)`.
     - Filter equipped items in `appearance` to ids that are unlocked after the union; drop `avatarId`/`unlockedAvatarIds` not in the active content pack (fallback to `content.active.value.defaultAvatar.id`) so an import from a newer/older pack can't crash rendering.
   - `fun backupConflictsWithExisting(backup): Boolean` = a profile with `backup.profile.id` exists.
3. **Platform file picker** — `platform/PlatformContracts.kt`:
   ```kotlin
   sealed interface FileSaveResult { data object Saved; data object Cancelled; data class Failed(val message: String) }
   sealed interface FileOpenResult { data class Opened(val text: String); data object Cancelled; data class Failed(val message: String) }
   interface ProfileFilePicker {
       suspend fun saveText(suggestedFileName: String, mimeType: String, text: String): FileSaveResult
       suspend fun openText(mimeTypes: List<String>): FileOpenResult
   }
   expect fun createPlatformProfileFilePicker(): ProfileFilePicker
   ```
   - **Android** (`PlatformAndroid.kt` + `MainActivity.kt`): in `MainActivity` register `ActivityResultContracts.CreateDocument("application/json")` and `ActivityResultContracts.OpenDocument()`; hand launchers to `AndroidPlatformContext` (extend `init(...)` or add `setDocumentLaunchers(create: (String) -> Unit, open: (Array<String>) -> Unit)` and `clearDocumentLaunchers()` in `onDestroy`), with `CompletableDeferred<Uri?>` like `pendingRecordAudio`. `saveText` writes via `contentResolver.openOutputStream(uri, "wt")` on `Dispatchers.IO`; `openText` reads via `openInputStream(uri)` (cap at 5 MB → `Failed`). Null uri → `Cancelled`. No storage permission needed (SAF).
   - **iOS** (`iosMain/.../platform/IosProfileFilePicker.kt`): save = write to a temp file in `NSTemporaryDirectory()` then present `UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)`; open = `UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)`; delegate resumes a `suspendCancellableCoroutine`; present via the same `topViewController()` approach as `IosQrScanner` (extract to a shared helper if convenient). Read with `NSString.stringWithContentsOfURL`.
   - Default file name: `lixo-<displayName-sanitized>-<yyyy-MM-dd>.json` (strip anything outside `[A-Za-z0-9åäöÅÄÖ_-]`).
4. **UI** — new card "Säkerhetskopia" in `ParentGateAndDashboardScreen` between the Friends card (`:596`) and the GDPR card (`:655`):
   - Text: `PARENT_BACKUP_TITLE = "Säkerhetskopia"`, `PARENT_BACKUP_NOTE = "Spara <namn>s profil (nivåer, stjärnor, avatar och föremål) som en fil där du vill, t.ex. i Google Drive eller Filer. Kompisar följer inte med."` (add to `ui/theme/LixoStrings.kt`).
   - Buttons: `LixoButton` PRIMARY `"Exportera profil"` (contentDescription/testTag `parent_export_profile`), SECONDARY `"Importera profil"` (`parent_import_profile`).
   - New params: `filePicker: ProfileFilePicker`, `onBuildBackup: suspend () -> String?`, `onPrepareImport: (String) -> Result<ProfileBackup>`, `onImportBackup: suspend (ProfileBackup, ImportMode) -> Result<ChildProfile>`, `backupConflicts: (ProfileBackup) -> Boolean`. Defaults so existing call sites/tests compile.
   - Export flow: build JSON → `filePicker.saveText(...)` → status text `"Profilen sparades."` / `"Export avbröts."` (silent on Cancelled is fine) / `"Kunde inte spara filen."`.
   - Import flow: `openText` → decode; on error show `"Filen är inte en Lixo-säkerhetskopia."` / `"Filen är från en nyare version av Lixo."`. On success show an `AlertDialog`: title `"Återställ <namn>?"`, body shows stars/level from backup; if conflict: buttons `"Ersätt <namn>"` (REPLACE_EXISTING, danger) + `"Avbryt"`; else `"Lägg till"` (AS_NEW) + `"Avbryt"`. After success: `"<namn> återställdes."`.
   - Status text rendered as a `Text` under the buttons (testable via `onNodeWithText`).
5. **Wiring** (`LixoApp.kt:584`): pass `createPlatformProfileFilePicker()` (remember once; also make it injectable via a `LixoApp` param defaulting to the platform one, like the QR scanner), and repository lambdas.
6. **Docs**: short section in `doc/project.md` (or `doc/friends.md` privacy part) — what the backup contains, that friends aren't included, file stays where the parent saves it.

## Tests / verification

**commonTest**
- `ProfileBackupTest` (new, `composeApp/src/commonTest/kotlin/se/lixo/app/ProfileBackupTest.kt`):
  - `roundTrip_encodeDecode_preservesAllFields`
  - `decode_rejectsWrongFormat`, `decode_rejectsNewerSchemaVersion`, `decode_rejectsGarbage`, `decode_ignoresUnknownKeys`
  - `export_stripsFriendCardIdAndHasNoFriends`
- `ProfileImportTest` (new, repository with in-memory/fake `ProfileStore`, like `ProfileStoreTest`):
  - `importAsNew_addsProfileProgressAndSelectsIt`
  - `importReplace_overwritesProgressKeepsLocalFriendsAndCardId`
  - `import_unionsUnlockedItems_neverRemoves`
  - `import_unknownAvatarOrEquippedItem_fallsBackToDefaults`
  - `exportThenImportAfterWipe_restoresSameProfile` (new repository instance over an empty store)

**Instrumented** (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/ProfileBackupTest.kt`, `se.lixo.app.verify.ProfileBackupTest`) — real `LixoRepository` + `FakeCloudGateway`, real `ParentGateAndDashboardScreen` inside `LixoTheme`, a **fake `ProfileFilePicker`** that stores the saved text in memory (the system picker UI is outside the app and can't be driven by Compose tests):
  - `exportProfile_writesValidBackup`: unlock gate, tap "Exportera profil", assert fake received JSON that decodes with the active profile's id/stars, `"Profilen sparades."` shown.
  - `importBackup_replacesExistingProfile`: export, then change the profile (stars/level via repository), import the saved text, confirm "Ersätt …", assert restored stars visible in the dashboard tile.
  - `importBackup_asNewProfile_onFreshRepository`: second repository over a cleared DB, import, "Lägg till", profile appears and is active.
  - `importInvalidFile_showsError`: fake returns `"{}"` → `"Filen är inte en Lixo-säkerhetskopia."`.
  - Capture `onRoot().captureToImage()` of the backup card + dialog to `temp/verify/045/`.
- Optional manual smoke on the Pixel 10 emulator (emulator-5554): tap Exportera → system "Save to" picker appears (SAF), save to Downloads; tap Importera → pick the file → dialog. Not required for pass.
- iOS: `./gradlew :composeApp:compileKotlinIosSimulatorArm64` must compile (picker itself verified manually / out of scope for automated runs).

## Open questions

- Should the in-memory-only logs (daily activity, struggled words, math errors) be included? They aren't persisted in Room today, so they'd be lost on restart anyway; plan excludes them. Add later together with persisting them.
- Export only the active profile (planned) vs. all profiles in one file?
