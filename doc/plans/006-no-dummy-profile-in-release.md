# 006 — Release builds start without the "Saga" dummy profile

**Type**: feature

**Depends on**: none.

## Goal

In production/release builds, the "Saga" dummy profile must not exist. On first launch, the child or parent creates the first profile (name + age) and then goes into the tutorial as usual. Debug builds keep seeding Saga with the tutorial already completed (added on 2026-11-07, `LixoRepository(isDebugBuild = …)`).

## Context

- Seed: `composeApp/src/commonMain/kotlin/se/lixo/app/data/repository/Repositories.kt` `seedInitialData()` (~line 126) always creates `defaultChild` "Saga" (`id = "profile_child_1"`) and sets `_activeProfileId`. The constructor param `isDebugBuild: Boolean = PlatformFeatureFlags().isDebugBuild` already exists. In debug it marks the tutorial done and seeds the starter hat.
- `ui/LixoApp.kt:124`: `val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.first()`. This **crashes on an empty list**, and every screen below takes a non-null `ChildProfile` (`LixoApp.kt:186, 233, 267, 328, 345, 357, 378`).
- `ui/screens/onboarding/ProfileSelectAndTutorialScreen.kt:74` requires `activeProfile: ChildProfile`. It already has a "create profile" form (name field `OutlinedTextField`, `LixoStrings.PROFILE_NEW_NAME_LABEL`, `PROFILE_CREATE_BTN`, ~line 245-295) that calls `onCreateSiblingProfile(name)` → `repository.createSiblingProfile(name, 8)` (`Repositories.kt:214`), which creates a profile with `tutorialCompleted = false` and makes it active. Reuse it.
- Tests that rely on the seeded profile:
  - `composeApp/src/commonTest/kotlin/se/lixo/app/LixoEnginesTest.kt:1338` (`repo.activeProfileId.value!!`)
  - `androidApp/src/androidTest/kotlin/se/lixo/app/screenshots/DummyData.kt:57`
  - `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FlitChestMeterTest.kt:112,158`
  - In host unit tests `PlatformFeatureFlags().isDebugBuild` is false (no app context).

## Steps

1. **Repository** (`Repositories.kt`):
   - `seedInitialData()`: only create and activate `defaultChild` when `isDebugBuild`. Otherwise leave `_profiles` empty and `_activeProfileId` null, and don't pre-unlock the starter hat. Keep all other seeding (courses, levels, catalog, achievements) unchanged.
   - Rename `createSiblingProfile` → `createProfile(displayName, ageYears)` and update its callers. Same behavior; it already activates the new profile.
2. **App shell** (`ui/LixoApp.kt`):
   - Make `activeProfile` nullable: `profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()`.
   - If `activeProfile == null`: render a new `FirstProfileSetupScreen` instead of the normal destinations, and return early from the content block, so none of the existing screens ever see a null profile.
3. **First-run screen** (new `ui/screens/onboarding/FirstProfileSetupScreen.kt`):
   - Welcome title + short text ("Välkommen till Lixo! Vad heter du?"), a name field, an age picker 6–12 (simple chip row, default 8), and a "Skapa profil" button (disabled while the name is blank).
   - Extract the existing name-field/form UI from `ProfileSelectAndTutorialScreen` into a shared composable (e.g. `ProfileNameForm`) and use it in both places, so it isn't duplicated.
   - On create: `repository.createProfile(name, age)` → `currentDestination = PROFILE_SELECT_AND_TUTORIAL`. The new profile has `tutorialCompleted = false`, so the existing tutorial starts.
   - Strings go in `LixoStrings`.
4. **Firebase/cloud**: if profile sync restores profiles for a signed-in parent (check `data/remote/FirebaseSyncService.kt` `SyncableProfileSnapshot` merge), make sure an empty local list followed by synced profiles works: the setup screen disappears once `profiles` becomes non-empty. Don't add new sync behavior.
5. **Tests**:
   - Tests that need Saga construct `LixoRepository(isDebugBuild = true, …)`. Update `LixoEnginesTest.kt:1338`, `DummyData.kt`, and `FlitChestMeterTest.kt`, or have DummyData build its own profile.

## Tests / verification

- commonTest:
  - `LixoRepository(isDebugBuild = false)` → `profiles` empty, `activeProfileId` null, no unlocked items, levels/catalog still seeded.
  - `LixoRepository(isDebugBuild = true)` → exactly one profile "Saga", `tutorialCompleted = true`, starter hat unlocked + equipped.
  - `createProfile("Ella", 7)` on an empty repo → active profile "Ella", age 7, `tutorialCompleted = false`.
  - Age is clamped to 6..12.
- Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/FirstProfileSetupTest.kt`, run on R5CXC0681EM per CLAUDE.md):
  - Render `LixoApp(repository = LixoRepository(isDebugBuild = false))` → the setup screen shows. Type a name, tap "Skapa profil" → the tutorial starts with the name shown. "Saga" doesn't exist anywhere.
- Build a release variant (`./gradlew :androidApp:assembleRelease` if signing allows it, otherwise check via a test with `isDebugBuild = false`).
- Full commonTest suite passes.

## Open questions

- None blocking. Default age 8 and the age range 6–12 match `createSiblingProfile`'s clamp.
