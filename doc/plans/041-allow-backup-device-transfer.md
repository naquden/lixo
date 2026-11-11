# 041 — Allow local app data in Android backup / phone-transfer

**Type**: feature

**Goal**: Let the player's local progress carry over when they switch phones via Android's device-to-device transfer (Google's "Switch"/D2D tool) and cloud backup, instead of starting the new phone with an empty local cache. Today this is fully blocked by `android:allowBackup="false"`.

## Context

- `androidApp/src/main/AndroidManifest.xml:24` and `composeApp/src/androidMain/AndroidManifest.xml:24` both set `android:allowBackup="false"`. `androidApp` is the only `com.android.application` module (`applicationId = "se.lixo.app"`, `androidApp/build.gradle.kts:18`); `composeApp` is a library module whose manifest gets merged in, so both copies of the attribute must agree or the manifest merger fails.
- `minSdk = 26`, `targetSdk = 35` (`gradle/libs.versions.toml:4-5`). Backup/transfer rules need **both**:
  - `android:fullBackupContent="@xml/backup_rules"` (used on API 23–30, and as the pre-31 fallback)
  - `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+, covers both cloud backup and device transfer)
  No such XML resources exist yet (confirmed by grep).
- Local data that exists on device:
  - Room DB `lixo.db` (built in `composeApp/src/androidMain/kotlin/se/lixo/app/platform/PlatformAndroid.kt:591-604`, via `buildLixoDatabase`/`createPlatformLixoDatabase`) — holds `RoomProfileStore` (profile/progress) and `RoomContentPackCache` data (`composeApp/src/commonMain/kotlin/se/lixo/app/data/local/ProfileStore.kt`, `Daos.kt`). This mirrors what's already synced to Firestore via `FirebaseSyncService`, but carrying it over means the new phone works offline immediately instead of waiting on a re-sync.
  - SharedPreferences `lixo_trusted_clock` (`PlatformAndroid.kt:686-717`) — anti-cheat trusted-time anchor (`anchor_server_millis`, `anchor_monotonic_millis`, `anchor_boot_id`). It's tied to this device's boot/monotonic clock, not meaningful on a different phone.
  - SharedPreferences `lixo_email_link_auth_prefs` (`PlatformAndroid.kt:722-756`) — transient pending-email for an in-progress email-link sign-in. Not meaningful to restore later.
  - Firebase Auth / Play services' own SharedPreferences and files (created by the SDKs, not app code) — these are tied to per-device keys; Firebase explicitly recommends excluding them from backup since restoring on a different device can fail to decrypt or leave a broken session.
- Given the above, the safest rule shape is an **allowlist**: only include the Room database (`lixo.db` plus its `-wal`/`-shm` sidecar files, since Room/SQLite defers writes to WAL) in both cloud backup and device transfer. Everything else (SharedPreferences, Firebase/GMS internal files) is left out simply by not being included — no need to enumerate GMS's internal file names, which can change across SDK versions.

## Steps

1. In `androidApp/src/main/AndroidManifest.xml`, on the `<application>` element:
   - Change `android:allowBackup="false"` to `android:allowBackup="true"`.
   - Add `android:dataExtractionRules="@xml/data_extraction_rules"`.
   - Add `android:fullBackupContent="@xml/backup_rules"`.
2. In `composeApp/src/androidMain/AndroidManifest.xml`, change `android:allowBackup="false"` to `android:allowBackup="true"` on its `<application>` element too (must match the merged value; this module has no launcher/application id of its own, so it doesn't need the `dataExtractionRules`/`fullBackupContent` attributes).
3. Create `androidApp/src/main/res/xml/data_extraction_rules.xml`:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <data-extraction-rules>
       <cloud-backup>
           <include domain="database" path="lixo.db" />
           <include domain="database" path="lixo.db-wal" />
           <include domain="database" path="lixo.db-shm" />
       </cloud-backup>
       <device-transfer>
           <include domain="database" path="lixo.db" />
           <include domain="database" path="lixo.db-wal" />
           <include domain="database" path="lixo.db-shm" />
       </device-transfer>
   </data-extraction-rules>
   ```
4. Create `androidApp/src/main/res/xml/backup_rules.xml` (pre-31 fallback, same allowlist, older schema):
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <full-backup-content>
       <include domain="database" path="lixo.db" />
       <include domain="database" path="lixo.db-wal" />
       <include domain="database" path="lixo.db-shm" />
   </full-backup-content>
   ```
5. No code changes beyond the two manifests and two new XML resources.

## Tests / verification

- No new automated test needed — this is manifest/resource-only. Confirm existing instrumented and host test suites still pass (no behavior change), e.g. re-run a broad existing suite such as `GeographyFlagChallengeTest` as a smoke check that the app still builds and launches.
- Manual verification (not automatable on-device via adb per project rules, but worth noting for whoever verifies manually): `adb shell bmgr backupnow se.lixo.app` after enabling a test backup transport, or use `adb shell cmd backup` tooling to confirm only `lixo.db` (and its `-wal`/`-shm` siblings) show up in the backup contents — no `shared_prefs/*` files.
- Build check: `./gradlew :androidApp:assembleDebug` to confirm the manifest merge (duplicate `allowBackup` across `androidApp` and `composeApp`) doesn't fail, and that `@xml/data_extraction_rules` / `@xml/backup_rules` resolve.

## Open questions

- None.

## Review fixes (round 1)

A code review of the implementation (commit `0cb3b08`) found two HIGH findings, both fixed:

- **Privacy regression:** `<cloud-backup>` in `data_extraction_rules.xml` originally included `lixo.db`/`-wal`/`-shm`, which would have uploaded child progress, word/math-error logs and private friend labels to Google cloud backup — contradicting `doc/project.md`/`doc/friends.md`'s "never uploaded" claims. `<cloud-backup>` is now an explicit exclude-everything block (and `backup_rules.xml`, the pre-31 fallback, excludes everything too), so no data goes to Google cloud backup. Only Android's own device-to-device transfer (`<device-transfer>`, Android 12+) still carries the Room DB. Docs (`doc/friends.md`, `doc/project.md`) were updated to describe this precisely.
- **Stale cloud identity after restore:** after a device transfer, Firebase Auth state doesn't carry over, so the new phone would sign in under a new anonymous uid while Room still had the old uid's `friendCardId`s, friends and tombstones — all unusable (and silently rejected by Firestore rules) under the new identity. `LixoRepository.ensureCloudIdentity()` (`Repositories.kt`) now verifies the persisted `cloud_uid` against the real signed-in Firebase uid once per process, and on a mismatch resets all cloud-bound local state (`friendCardId`, friends, removal tombstones) via a new `resetLostCloudIdentity()`, so `ensureMyCard()` creates a fresh card under the real uid instead of reusing one tied to a dead identity.
