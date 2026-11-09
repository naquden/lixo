# 022 — App runs on the content pack (bundled only)

**Type**: feature
**Depends on**: 021

## Goal
Switch the whole app to the content pack. Avatars and items come from `ActiveContent`, loaded from the bundled `content_pack.json`, and are drawn by the layer renderer (020). Delete the hardcoded catalog, the `AvatarType` enum and the styleIndex renderers. Cloud sync comes in 024; drop rules still come from `BalanceConfig` until 023.

## Context
- **Catalog:**
  - `buildSeedWardrobeCatalog()` `Repositories.kt:877`, `GENERATED_WARDROBE_ITEMS` (`GeneratedWardrobeCatalog.kt`, about 11k lines)
  - the 015 merge: `bundledCatalog` + `remoteItems`, `publishWardrobeCatalog` :124, `loadCachedWardrobe`, `syncWardrobeFromCloud`, `RemoteWardrobeValidator.kt`, `data/local/WardrobeCatalogCache.kt`
  - gateway `fetchWardrobeMeta`/`fetchWardrobeItems` in `data/remote/FirebaseSyncService.kt`
- **AvatarType is used in:**
  - `Models.kt:9`
  - `LixoStrings.kt:195–200` (names)
  - `Avatar3DController.kt:1262/1277`
  - `Repositories.kt:189/193/382–383` (the default profile is CAT)
  - `FirebaseSyncService.kt:178/192` (demo friends) and :414 (parsing)
  - the avatar unlocks in `ProgressionAndGiftEngine.recordLevelCompletion` (`AvatarType.entries` filtered by `unlockStarsRequired`)
  - the avatar picker in the wardrobe/onboarding screens
- **Profile persistence:** `ProfileEntity.appearanceJson` and `unlockedAvatarsCsv` (`AvatarType.name`) in `data/local/Entities.kt`; Room v7 in `LixoDatabase.kt`, migrations in `LixoMigrations.kt`.
- **Renderer call sites:** `Avatar3DViewport`/`WardrobeItemPreview` in WardrobeScreen, CampaignMapScreen, AvatarMapScreen, ProfileSelectAndTutorialScreen, `celebration/ItemReveal.kt`, `UnlockDialogs.kt`.
- **`WardrobeItem` (`Models.kt:201`) consumers:**
  - `LixoApp.kt` (`wardrobeCatalog` :103, `catalogById` :129, runs :219/:277, wardrobe :401)
  - `ProgressionAndGiftEngine`
  - `AchievementEngine`
  - the celebration UI

## Steps
1. **Model:**
   - Replace `WardrobeItem` with the 019 `ContentItem` everywhere (or keep the name `WardrobeItem` as a typealias or rename of `ContentItem`; pick the rename and update the call sites). Removed fields: `styleIndex`, `meshAssetPath`, `meshNodeName`, `minAppVersionCode`, `primaryColorHex`/`secondaryColorHex` (now `palette`), `isWeeklyStreakSetPiece`/`unlockAchievementId`/`unlockStarRequirement` (now `pool` and drop rules; 023 wires the rules, but this task must keep behaviour by reading them from `content.dropRules`, or temporarily from `BalanceConfig`).
   - Rename `AvatarAppearanceV2` (020) to `AvatarAppearance(avatarId: String, tintHex: Long?, eyeIndex, smileIndex, equipped)` and delete the old class.
   - Delete `AvatarType`. Profile and domain code use `unlockedAvatarIds: Set<String>`, defaulting to the ids of the avatars with `unlock.type == default`.
2. **`ContentRepository`** (`data/repository/ContentRepository.kt`):
   - `val active: StateFlow<ActiveContent>`
   - `suspend fun loadBundled()`: `Res.readBytes("files/content_pack.json")` → `ContentPackValidator.validate(…, previous = null)` (the bundled pack must be Accepted; if it's Rejected, throw in debug and log in release)
   - Inject it into `LixoRepository` and `LixoApp`. Load it before anything reads the catalog: in `LixoApp.kt`, where `loadCachedWardrobe()` runs today.
3. **Renderer switch:** replace every `Avatar3DViewport`/`WardrobeItemPreview` call with `AvatarViewport`/`ItemPreview` (020), passing `content.active`. Then delete `ui/components/avatar3d/Avatar3DController.kt`, after moving any helper still in use (pose, patterns, ornaments) into `ui/components/avatar/` (020 may already have done this). Delete `drawWardrobeItemPreview`.
4. **Avatar picker and names:**
   - The picker lists `content.avatarsOrdered`; locked avatars show their star requirement from `unlock.stars`.
   - `LixoStrings` avatar name becomes `content.string(avatar.nameKey)`.
   - Avatar unlocks in `recordLevelCompletion`: avatars with `unlock.type == stars && stars <= totalStars`, not yet unlocked.
5. **Owned or equipped ids that aren't in the pack** are skipped silently everywhere (grid, equipped map, celebrations). Audit with `grep -rn "catalogById\[\|itemsById\[" composeApp/src` and remove every `!!`.
6. **Room reset:**
   - Set the DB version to 1 and empty `LixoMigrations.kt`.
   - Use `fallbackToDestructiveMigration(dropAllTables = true)`.
   - Delete `WardrobeItemEntity`, `WardrobeCatalogMetaEntity` and their DAOs.
   - `ProfileEntity`: `appearanceJson` holds the new `AvatarAppearance`; rename `unlockedAvatarsCsv` to `unlockedAvatarIdsCsv`.
   - Delete Room schema JSON files for old versions if the project exports them.
7. **Delete:**
   - `GeneratedWardrobeCatalog.kt` and `buildSeedWardrobeCatalog`
   - the 015 merge and sync code, `RemoteWardrobeValidator.kt`, `WardrobeCatalogCache.kt` and the platform factories
   - gateway `fetchWardrobeMeta`/`fetchWardrobeItems`
   - the Kotlin-emitting part of `tools/item_pipeline/generate_wardrobe_items.py` (or the whole of `tools/item_pipeline/` if 021 fully replaced it), and `tools/item_pipeline/upload_wardrobe_items.py`
   - `3d_sample.glb` (both copies), `GlbAssetMetadata`/`parseGlbHeader`, `tools/asset_pipeline/blender_avatar_pipeline.py`
   - the `sceneview-compose` entry in `gradle/libs.versions.toml` and `build.gradle.kts`
   - the 015/018-specific tests (`RemoteWardrobeItemTest`, the remote validator tests in `LixoEnginesTest`)
8. **Level rewards for now:** keep the curriculum task `rewardItemId` working by reading `content.dropRules.levelRewards` (an explicit list per level, 021). `buildLevelRewardPool`/`levelRewardItemIdFor` are deleted here; the full drop-rule wiring is 023.
9. **Demo friends** (`FirebaseSyncService.kt:178/192`) use avatar ids. Public avatar parsing (:414) falls back to the first default avatar id.
10. Remove `firebase/firestore.rules` `wardrobe` (024 adds the content rules).

## Tests / verification
commonTest:
- Update `LixoEnginesTest` to build `ActiveContent` from `tools/tests/fixtures/mini_pack.json`, or from the 019 test fixture copied into commonTest. Delete the 015/018 remote wardrobe tests.
- `ContentRepositoryBundledTest`: loading the bundled pack gives Accepted, 6 avatars and the tutorial item present.
- `AvatarUnlockByIdTest`: reaching 12 stars unlocks `avatar_dog`; a new profile has `unlockedAvatarIds == {avatar_cat}`.
- `./gradlew :composeApp:testAndroidHostTest` is green and `./gradlew :androidApp:assembleDebug` builds; the iOS framework compiles (`:composeApp:compileKotlinIosSimulatorArm64`).

Instrumented (real `LixoRepository` + `ContentRepository` with the bundled pack, real Room):
- `se.lixo.app.verify.WardrobeScreenContentTest`: the avatar picker shows every avatar name from the pack (`onNodeWithText("Katt")` etc.); equipping an item updates the viewport; screenshot.
- `se.lixo.app.verify.ItemRevealContentTest`: the chest reveal renders a pack item with its name.
- Re-run the existing `ProfilePersistenceTest` and `AchievementLadderTest`, updated for the new models.

Run:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.WardrobeScreenContentTest,se.lixo.app.verify.ItemRevealContentTest,se.lixo.app.verify.ProfilePersistenceTest,se.lixo.app.verify.AchievementLadderTest
```
Note in the report: dev devices need `adb shell pm clear se.lixo.app` (destructive DB reset).

## Open questions
- None.
