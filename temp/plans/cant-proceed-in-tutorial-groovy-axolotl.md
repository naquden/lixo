# Plans: bug 007 (tutorial stuck on skipped words) + bug 008 (created profile lost on restart) + feature 009 (screenshot all items)

## Context
The project rule (CLAUDE.md) says a bug request becomes a plan doc and a FEATURES.md task. Nothing is implemented now.
On approval I will write these files and then stop:
1. `doc/plans/007-tutorial-stuck-unread-words.md`: the content of section **Plan doc 007**.
2. `doc/plans/008-persist-profiles.md`: the content of section **Plan doc 008**.
2b. `doc/plans/009-item-catalog-screenshots.md`: the content of section **Plan doc 009**.
3. Three lines in `FEATURES.md` under `## Pending`:
   - `- [ ] 007 — Tutorial: skipped words block completion; allow "Gå vidare" once last word is read (bug) → [plan](doc/plans/007-tutorial-stuck-unread-words.md)`
   - `- [ ] 008 — Created profiles lost on app restart; persist profiles in Room, debug may only touch Saga (bug) → [plan](doc/plans/008-persist-profiles.md)`
   - `- [ ] 009 — Instrumented test that screenshots every wardrobe item, grouped in grids (feature) → [plan](doc/plans/009-item-catalog-screenshots.md)`

---

## Plan doc 007 (doc/plans/007-tutorial-stuck-unread-words.md)

**Type**: bug

**Goal**: In the onboarding tutorial (AI listening), when the child reaches the last word but the listener missed some words ("Lixo", "Hatten", "Lixo" in the screenshot), the child is stuck. The AI listener must never block finishing a story. Once the last word is registered, show a button that lets the child continue.

**Context**
- `ForcedAlignmentEngine.processRecognizedTokens` (`composeApp/.../domain/engine/ForcedAlignmentEngine.kt:130`) handles skipped words via lookahead. They get flagged `needsPractice` and stay incomplete. When the cursor reaches the end with unread words left, it sets `isAwaitingUnreadWords = true` and `isCompleted = false` (`:232-240`).
- `ForcedAlignmentEngine.finishWithUnreadWords` (`:387`) already exists and is tested (`LixoEnginesTest.kt:1533`, `:1563`). It ends an awaiting session.
- `ReadingActivityScreen.kt:472-508` already handles this case: a gold hint card (`READING_READ_YELLOW_WORDS`) and a "Gå vidare" button (`READING_CONTINUE_WITH_YELLOW_BTN`) that call `finishWithUnreadWords`.
- **Root cause**: the tutorial stage in `ProfileSelectAndTutorialScreen.kt:440-540` has no `isAwaitingUnreadWords` branch. Because `isCompleted` stays false, it keeps showing the "Läs nästa ord" button. That button does nothing at the end of the story: `currentWord` is null, so `?: return@LixoButton` at `:494` exits. The completion card at `:541` never appears.
- The tutorial word rendering (`:458-466`) also ignores `needsPractice`. Skipped words show as plain black text instead of yellow, so the child can't see why they're stuck.

**Steps**
1. `ProfileSelectAndTutorialScreen.kt`, word rendering (`:458-483`): match the yellow states used in `ReadingActivityScreen.kt:413-438`.
   - `isYellowUnread = token.needsPractice && !token.isCompleted && !isCurrent` → GoldTint/Gold/GoldLip with `Modifier.dashedBorder(border, 8.dp)`.
   - `isYellowRead` → GoldTint/transparent/GoldLip.
   - Check where `dashedBorder` is defined. If it is private to ReadingActivityScreen, make it `internal` (or move it to `ui/components`) so the tutorial can use it.
   - No help popup in the tutorial (keep scope small).
2. Same file, action area (`:488-540`): inside `if (!sessionState.isCompleted)`, add a branch before the existing AI/manual buttons: `if (sessionState.isAwaitingUnreadWords)`.
   - Render the same gold hint card (`LixoStrings.READING_READ_YELLOW_WORDS`).
   - Render a PRIMARY `LixoButton` labelled `LixoStrings.READING_CONTINUE_WITH_YELLOW_BTN.uppercase()`. Its onClick calls `sessionState = alignmentEngine.finishWithUnreadWords(sessionState)`.
   - In this state, hide the "Läs nästa ord" simulate button (it is a no-op at the end).
   - STT collection keeps running, so the child can still read the yellow words. A yellow match completes them and, once all are completed, sets `isCompleted` true naturally.
   - After the session completes, the existing completion card (`:541`) shows and "continue to map" calls `onTutorialCompleted`. No scoring change is needed (tutorial has no score).
3. No engine changes. `finishWithUnreadWords` already covers this. The tick loop at `:319` keeps ticking until `isCompleted`, which is fine.

**Tests / verification**
- commonTest (`LixoEnginesTest.kt`): add `forcedAlignment_tutorialStorySkippedWordsAwaitThenFinish`.
  - Start a session with `BalanceConfig.TUTORIAL_STORY_SV` in AI_LISTENING.
  - Feed every word except "Lixo" (index 1) and "Hatten" as recognized tokens.
  - Assert `isAwaitingUnreadWords` is true, `isCompleted` is false, and both skipped words have `needsPractice`.
  - Call `finishWithUnreadWords` and assert `isCompleted` is true.
- Instrumented: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/TutorialUnreadWordsTest.kt` (FQCN `se.lixo.app.verify.TutorialUnreadWordsTest`). Follow the `HeardWordPopupTest` / `FirstProfileSetupTest` pattern (`createAndroidComposeRule`, `AndroidPlatformContext.init`, `LixoTheme`).
  - Render the real `ProfileSelectAndTutorialScreen` with `startTutorialImmediately = true` and a profile with `tutorialCompleted = false`.
  - Use a fake `SpeechRecognitionEngine`: `requestMicrophonePermission()` returns true, and `recognizedTokens` is a `MutableSharedFlow` (extend the `SilentSttEngine` pattern from `HeardWordPopupTest.kt:75`).
  - Click `TUTORIAL_ALLOW_MIC_BTN`. Emit tokens for all tutorial words except the 2nd ("Lixo") and "Hatten".
  - Assert the "GÅ VIDARE" node exists and the "LÄS NÄSTA ORD" node does not. Take a screenshot.
  - Click "GÅ VIDARE". Assert `TUTORIAL_CONTINUE_TO_MAP_BTN.uppercase()` exists. Click it and assert the `onTutorialCompleted` callback fired. Take a screenshot.
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.TutorialUnreadWordsTest`

**Open questions**: none.

---

## Plan doc 008 (doc/plans/008-persist-profiles.md)

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

---

## Plan doc 009 (doc/plans/009-item-catalog-screenshots.md)

**Type**: feature

**Goal**: Add an instrumented test that produces screenshots of every item in the game. Items are grouped into grid sheets to keep the number of screenshots low.

**Context**
- The real catalog comes from `LixoRepository().wardrobeCatalog`, built by `buildSeedWardrobeCatalog()` (`Repositories.kt`, about 579 items: 3 handmade plus `GeneratedWardrobeCatalog.kt`) across 9 `EquipmentSlot`s and 5 `Rarity`s.
- The real item renderer is `WardrobeItemPreview(item, modifier)` (`ui/components/avatar3d/Avatar3DController.kt:196`), the same one used by wardrobe cards (`WardrobeScreen.kt:370`) and the chest reveal.
- SPARKLE/GLOW ornaments start an `infiniteRepeatable` animation, which would make `waitForIdle()` hang. The test must turn off clock auto-advance.
- Screenshot pattern: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FirstProfileSetupTest.kt`. It uses `createAndroidComposeRule<ComponentActivity>()`, `AndroidPlatformContext.init`, an appops `MANAGE_EXTERNAL_STORAGE` grant, a `Downloads/lixo_screenshots` output folder, and `onRoot().captureToImage()` → PNG.

**Steps**
1. Create `androidApp/src/androidTest/kotlin/se/lixo/app/verify/ItemCatalogScreenshotTest.kt` (FQCN `se.lixo.app.verify.ItemCatalogScreenshotTest`). Reuse the `setUp` and `shot()` helpers from `FirstProfileSetupTest`, with output going to `Downloads/lixo_screenshots/items/`. Clear old PNGs there first.
2. Load items with `LixoRepository(FakeCloudGateway()).wardrobeCatalog.value`. Group them by `slot` (in `EquipmentSlot.entries` order), and within each slot sort by `rarity` ordinal, then `id`.
3. Split each slot group into pages of `ITEMS_PER_SHEET = 30` (5 columns × 6 rows), which gives roughly 20 to 25 sheets in total.
4. Add a composable `ItemSheet(title: String, items: List<WardrobeItem>)` with fixed size 1080×1600 px (use `Modifier.requiredSize` in dp from density), inside `LixoTheme` on a white background.
   - Header text: `"<slot.name> — sida n/m (<count> föremål)"`.
   - A non-lazy grid built from `Row`s and `Column`s, so every cell is composed.
   - Each cell (about 200 dp wide):
     - A 72 dp `WardrobeItemPreview`.
     - A 2 dp border coloured with `Color(item.rarity.hexColorPrimary)`.
     - Below it, the `item.nameSv` (1 line, ellipsis) and the `item.id` in small muted text, so a broken item can be traced from the image.
5. A single test, `screenshotAllItems()`:
   - Set `compose.mainClock.autoAdvance = false`.
   - Use one `setContent` holding a `mutableStateOf(sheetIndex)`. For each sheet: set the index, call `compose.mainClock.advanceTimeBy(500)`, then `shot("items_%02d_<slot>_p<n>")`. This avoids calling `setContent` twice, which caused the earlier 004 bug.
   - Also write `items_index.txt` listing sheet file → item ids.
6. Assertions:
   - The total number of items rendered across sheets equals the catalog size.
   - The ids are unique.
   - Each sheet's first and last item ids are found with `onNodeWithText(id).assertExists()`.

**Tests / verification**
- Instrumented only (it produces visual evidence): `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ItemCatalogScreenshotTest`
- Pull the results: `adb -s emulator-5554 pull /sdcard/Download/lixo_screenshots/items temp/verify009/`. Then check that the sheet count is ceil(per-slot count / 30) summed over slots, that each PNG shows a full grid, and that `items_index.txt` lists every id.

**Open questions**: none.

---

## Verification device (all three plans)
The user started a Pixel 10 emulator, `emulator-5554`, and it is used for verification instead of R5CXC0681EM, which is not connected.
- When running the pending tasks, set `verifyNotes` to `"Device emulator-5554 (Pixel 10 emulator). ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>"`.
- On the emulator, the 007 test feeds STT through the fake engine (there is no real mic). 008 uses the real Room DB on the emulator.
