# 008 — Created profiles lost on app restart

**Type**: bug

**Goal**: When the user creates a profile, it disappears on every app restart. Profiles the user creates must persist and must never be cleared. In debug builds, the app may only seed or modify the dummy "Saga" profile (`profile_child_1`).

**Context**
- `LixoRepository` (`composeApp/.../data/repository/Repositories.kt:63`) keeps all state in memory only:
  - `_profiles` and `_activeProfileId` (`:75-79`) and `_unlockedItemIds`.
  - `seedInitialData()` (`:132-165`) runs every cold start. In debug it writes `_profiles.value = listOf(Saga)` and makes Saga active. In release the list is empty, so `FirstProfileSetupScreen` shows again (this was the known LOW in 006).
- A Room DB already exists but nothing uses it for profiles:
  - `LixoDatabase` (version 5, `data/local/LixoDatabase.kt`).
  - `ProfileEntity` (`data/local/Entities.kt:7`), `AppSettingsEntity.activeProfileId` (`Entities.kt:186`), and `UnlockedInventoryEntity` (`Entities.kt:94`).
  - DAO methods: `observeProfiles` / `getProfileById` / `upsertProfile`, `getUnlockedItemIds` / `insertUnlockedItem`, and `getAppSettings` / `upsertAppSettings` (`data/local/Daos.kt`).
  - Today only `LexiconCache.kt:47` uses the DB.
- `createPlatformLixoDatabase()` is a cached singleton on Android (`androidMain/.../PlatformAndroid.kt:343-362`) and also exists on iOS. It returns null when no context is available, as in JVM unit tests.
- `ChildProfile` (`domain/model/Models.kt:142`) maps 1:1 to `ProfileEntity`. `AvatarAppearance` is `@Serializable`, so it goes to `appearanceJson`. The achievement maps and sets go to their JSON columns, and `unlockedAvatars` goes to a CSV of enum names.
- Mutation sites to persist: `createProfile` (`:220`), `updateProfile` (`:237`), `selectProfile` (`:215`), `unlockWardrobeItem` (`:243`), `equipItemOnActiveProfile`, `selectAvatarTypeOnActiveProfile`, `customizeBaseAppearanceOnActiveProfile`, and the other `_profiles.update` calls (`:250`, `:272`, `:290`, and any further ones). Grep for `_profiles.update`, `_unlockedItemIds.update` and `_activeProfileId.value =`.

**Steps**
1. Add a mapper file `data/local/ProfileMappers.kt` with `ChildProfile.toEntity()` and `ProfileEntity.toDomain()` (kotlinx.serialization Json with `ignoreUnknownKeys = true`). Follow the mapper style in `LexiconCache.kt:28-45`.
2. Add a `ProfileStore` interface in `data/local`:
   - Functions: `suspend fun loadProfiles()`, `loadActiveProfileId()`, `loadUnlockedItemIds()`, `saveProfile(p)`, `saveActiveProfileId(id)`, `saveUnlockedItem(id, source)`.
   - `RoomProfileStore(dao)` implements it. For the active id, `getAppSettings()` → copy/insert `AppSettingsEntity(activeProfileId = …)` → `upsertAppSettings`.
   - Factory `createPlatformProfileStore(): ProfileStore? = createPlatformLixoDatabase()?.let { RoomProfileStore(it.lixoDao()) }`, the same shape as the lexicon cache.
   - No schema change and no DB version bump.
3. `LixoRepository` constructor: add `private val profileStore: ProfileStore? = createPlatformProfileStore()` and an internal `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for writes. Add `private val _profilesLoaded = MutableStateFlow(profileStore == null)` and expose it as `profilesLoaded: StateFlow<Boolean>`.
4. Startup load. Keep the content seeding (courses, levels, wardrobe, achievements) in `seedInitialData()`, and move the profile seeding into a new `suspend fun loadPersistedProfiles()` launched from `init`:
   - Read the persisted profiles, unlocked ids and active id.
   - **Debug only**: if `profile_child_1` (Saga) is missing from the persisted list, add the Saga seed, add `TUTORIAL_FIRST_REWARD_ITEM_ID` to the unlocked ids, and persist both. Never touch any other profile id. Debug code may write `profile_child_1` only.
   - **Release**: never seed anything.
   - Set `_profiles`, `_unlockedItemIds`, and `_activeProfileId`:
     - Use the persisted active id if that profile exists.
     - Otherwise use the first profile (Saga in debug), or null.
   - Merge with anything created in memory before the load finished (union by id, in-memory wins) to avoid losing writes. Then set `_profilesLoaded = true`.
   - When `profileStore == null` (unit tests or JVM), keep today's synchronous behaviour: debug seeds Saga in memory and `profilesLoaded` starts true. Existing commonTest tests keep passing.
5. Write-through. After every mutation in step "Mutation sites", persist the changed state via `scope.launch { profileStore?.… }`:
   - Profiles: diff the old and new lists and `saveProfile` the profiles that changed. A helper `private fun updateProfiles(transform)` wraps `_profiles.update` and persists the changed entries. Replace all `_profiles.update` calls with it.
   - `selectProfile` and `createProfile` persist the active id.
   - `unlockWardrobeItem` and other unlock paths call `saveUnlockedItem`.
   - There is no delete path, and none may be added that removes non-Saga profiles.
6. `LixoApp.kt:99-170`: collect `repository.profilesLoaded`. While it is false, render a blank `LixoColors.Background` box (no `FirstProfileSetupScreen` flash). This decides whether the profile setup screen shows.
7. Update the KDoc on `isDebugBuild` (`Repositories.kt:66-71`) and the 006 note in FEATURES.md, since the known LOW is now resolved.

**Tests / verification**
- commonTest (`LixoEnginesTest.kt` or a new `ProfilePersistenceTest.kt`) with an in-memory `FakeProfileStore`:
  - `profiles_persistAcrossRepositoryInstances`: create a profile on repo A with a shared fake store. Repo B on the same store loads it and makes it active.
  - `debugSeed_doesNotOverwriteUserProfiles`: the store holds a user profile and an edited Saga, debug=true. After load, both are present, the user profile is unchanged, and Saga is not re-seeded.
  - `debugSeed_addsSagaWhenMissing`: the store holds only a user profile. After load, Saga is added and the user profile stays active if it was the persisted active one.
  - `release_neverSeedsSaga`: debug=false with an empty store gives no profiles.
  - `unlockAndEquip_persisted`: unlock an item and equip it. A new repo instance shows the item unlocked and equipped.
  - Mapper round-trip: `ChildProfile` → entity → domain is equal, including achievements, appearance and unlockedAvatars.
- Instrumented: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/ProfilePersistenceTest.kt` (FQCN `se.lixo.app.verify.ProfilePersistenceTest`), using the real Room DB through `AndroidPlatformContext.init(ctx)`.
  - In `@Before`, delete the test profile ids from the DB (the test owns its own ids and never touches others).
  - Compose: render `LixoApp(repository = LixoRepository(FakeCloudGateway(), isDebugBuild = false))`, go through `FirstProfileSetupScreen` to create "Testa", and wait for the tutorial. Dispose it.
  - Render a fresh `LixoApp` with a new `LixoRepository` (simulating a cold start). Assert `FirstProfileSetupScreen` is NOT shown and "Testa" is the active profile (`onNodeWithText`). Take a screenshot.
  - Debug variant: construct with `isDebugBuild = true` and assert both "Saga" and "Testa" are in the profile list.
  - Clean up the "Testa" rows in `@After`.
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ProfilePersistenceTest`
  - Manual sanity: create a profile, force-stop the app, relaunch. The profile is still there.

**Open questions**: Should level progress, streak/daily logs and struggled words also be persisted now? The DAO supports them, but they are out of scope here. Stars and level order live on `ChildProfile`, so they persist with this change.

**Verification device**: The user started a Pixel 10 emulator, `emulator-5554`, and it is used for verification instead of R5CXC0681EM, which is not connected.
- When running the pending tasks, set `verifyNotes` to `"Device emulator-5554 (Pixel 10 emulator). ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>"`.
- On the emulator, the 007 test feeds STT through the fake engine (there is no real mic). 008 uses the real Room DB on the emulator.
