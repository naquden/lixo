# 010 — Persist level progress (regression from 008)

**Type**: bug

**Goal**: 008 saves `ChildProfile` (totalStars, currentLevelOrder, achievements) and unlocked items, but per-level `LevelProgress` is still kept in memory only. After a cold start the map relocks levels, a replay counts as the first clear (stars are counted again, which inflates `totalStars` and the star achievements), and the onboarding reward chests are handed out again. Level progress must persist together with the profile.

**Context**
- `Repositories.kt:112` `_levelProgressByProfile` is in memory only. `saveLevelProgress` (`:421`) updates the map and nothing else.
- 008's persistence path: `loadPersistedProfiles(store)` (`:185`), `enqueueProfileWrite` (`:230`), the `ProfileStore` interface (`data/local/ProfileStore.kt:15-20`), and `RoomProfileStore`.
- The table, entity and DAO already exist: `LevelProgressEntity` (`Entities.kt:102`) maps 1:1 to `LevelProgress` (`Models.kt:327`), and `LixoDao.upsertLevelProgress`. The DAO has no "get all for a profile" suspend query; it only has `observeLevelProgress(profileId): Flow` and a single-row get.
- Consumers: `CampaignMapScreen` uses `ProgressionAndGiftEngine.isLevelUnlocked(progressByLevelId)`, and `LixoApp.kt:203/261` uses `clearedLevelsCountBeforeRun` / `isReplay`.
- The test fake is `FakeProfileStore` in `composeApp/src/commonTest/kotlin/se/lixo/app/ProfileStoreTest.kt:25`.

**Steps**
1. `Daos.kt`: add `@Query("SELECT * FROM level_progress") suspend fun getAllLevelProgress(): List<LevelProgressEntity>`. This is a query only, so there is no schema or version change.
2. `ProfileMappers.kt`: add `LevelProgress.toEntity()` / `LevelProgressEntity.toDomain()`.
3. `ProfileStore`: add `suspend fun loadLevelProgress(): List<LevelProgress>` and `suspend fun saveLevelProgress(progress: LevelProgress)`, and implement both in `RoomProfileStore`. Update `FakeProfileStore`.
4. `loadPersistedProfiles`: load all the progress rows and group them by profileId → levelId. Merge them into `_levelProgressByProfile` (in-memory entries win, as with profiles). Do this before `_profilesLoaded = true`.
5. `saveLevelProgress`: after the map update, call `enqueueProfileWrite { saveLevelProgress(progress) }`.
6. Review LOWs from the 007–009 run, fixed here as well:
   - The write loop and load fallbacks in `Repositories.kt` (`runCatching`) and the JSON decode fallbacks in `ProfileMappers.kt` swallow errors. Log the Throwable with `println("LixoProfiles: …")`.
   - `ItemCatalogScreenshotTest.kt`: `ItemSheet` hard-codes 5/6/1080/1600/"item_sheet". Use the companion constants instead.

**Tests / verification**
- commonTest `ProfileStoreTest`:
  - `levelProgress_persistsAcrossRepositoryInstances`: with a shared fake store, repo A calls `saveLevelProgress` for level 1. Repo B loads it, and `levelProgressByProfile[profileId][levelId]` equals the saved value.
  - `levelProgress_nextLevelUnlockedAfterColdStart`: `ProgressionAndGiftEngine.isLevelUnlocked` is true for level 2 on repo B.
- Instrumented: extend `se.lixo.app.verify.ProfilePersistenceTest` with `coldStart_keepsLevelProgress`:
  - Create a profile and call `repository.saveLevelProgress(...)` for the first Swedish level.
  - Start with a new repo and render the real `CampaignMapScreen` via `LixoApp`.
  - Assert that level 2 is unlocked (via semantics), and take a screenshot.
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ProfilePersistenceTest`

**Open questions**: should daily activity (streaks), struggled words and math errors also persist? They are not covered here.
