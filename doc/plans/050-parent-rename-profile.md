# 050 — Parent can rename the child's profile (avatar name)

**Type**: feature

## Goal
In the parent dashboard, a parent can change the name of the active child profile. This is the name shown with the avatar ("Jag · Ella" on the avatar map, the map header, profile select, and the parent's sibling switcher). The new name is saved and survives an app restart. Two profiles on the device may not have the same name. This applies to renaming and to creating a sibling. The comparison is trimmed and case-insensitive.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/model/Models.kt:156-158`: the name is `ChildProfile.displayName`. It is local-only. Friends see the generated `nickname`, never `displayName`, and `FirebaseSyncService`/`FriendCloud` never send it. So a rename only touches Room and has no effect on friends.
- Where the name is shown (all read `displayName` from the profile flow, so they update automatically):
  - `ui/screens/social/AvatarMapScreen.kt:1185` ("Jag · <name>" under the own avatar)
  - `ui/screens/map/CampaignMapScreen.kt:209`
  - `ui/screens/onboarding/ProfileSelectAndTutorialScreen.kt:178`
  - `ui/screens/parent/ParentGateAndDashboardScreen.kt:274` (sibling switcher), `:693`/`:709` (backup note and file name)
- `data/repository/Repositories.kt:470-492`: `createProfile` trims the name and falls back to "Syskon" when blank. `updateProfile(updated)` replaces the profile and goes through `updateProfiles`, which persists changed profiles via the FIFO `enqueueProfileWrite { saveProfile(p) }` (`:380-391`). No rename function exists.
- `ui/screens/parent/ParentGateAndDashboardScreen.kt:78-102`: parameters. `onUpdateProfileGoalsAndSettings: (ChildProfile) -> Unit` is wired to `repository.updateProfile(it)` at `ui/LixoApp.kt:602`. The sibling switcher row is at `:258-281`. The parent has no way to edit the name today.
- `ui/screens/onboarding/FirstProfileSetupScreen.kt:47-73`: `ProfileNameForm` (label `PROFILE_NEW_NAME_LABEL` "Barnets namn", placeholder "T.ex. Alva"). It has no length limit and no duplicate check. The first-profile screen (`:170-175`, wired at `LixoApp.kt:302`) can't produce a duplicate because no other profile exists yet.
- `ui/screens/onboarding/ProfileSelectAndTutorialScreen.kt:83, 252-273`: the sibling-creation form. It uses `ProfileNameForm`, and its create button is only gated on `newSiblingName.isNotBlank()`. It calls `onCreateSiblingProfile(name)`, wired at `LixoApp.kt:449` to `repository.createProfile(name, PROFILE_DEFAULT_AGE_YEARS)`. Today this can create a second "Ella".
- `domain/model/BalanceConfig.kt:18-22`: the profile constants. There is no name length constant.
- `data/repository/Repositories.kt:644-665`: backup import. `backupConflictsWithExisting` and `importProfileBackup` only compare ids. So `ImportMode.AS_NEW` can import an "Ella" from another device next to a local "Ella" with a different id. `REPLACE_EXISTING` could also bring a backup name that matches another sibling.
- Plan 049 (pending) adds focus, `ImeAction.Done` and hiding the bottom nav while the keyboard is open, for friend rename. Use the same text-field pattern here so the two rename fields behave the same.

## Steps
1. `BalanceConfig.kt`: add `const val PROFILE_NAME_MAX_LENGTH = 20` next to the other `PROFILE_*` constants, with a one-line KDoc.
2. New file `domain/model/ProfileNames.kt`. It holds pure functions shared by the repository and the UI, so they apply the same rule:
   ```kotlin
   /** Trimmed and capped to [BalanceConfig.PROFILE_NAME_MAX_LENGTH]; the form every stored displayName has. */
   fun normalizeProfileName(raw: String): String

   /** True if another profile (not [excludingProfileId]) already has [name], compared after normalizing, ignoring case. */
   fun isProfileNameTaken(profiles: List<ChildProfile>, name: String, excludingProfileId: String? = null): Boolean

   /** [name] if free, else "<name> 2", "<name> 3", … (base shortened so the result fits the max length). */
   fun uniqueProfileName(profiles: List<ChildProfile>, name: String, excludingProfileId: String? = null): String
   ```
   Compare case-insensitively with `lowercase()`, so "ella" and "Ella " count as the same name.
3. `Repositories.kt`:
   - Add, next to `updateProfile`:
     ```kotlin
     enum class RenameProfileResult { RENAMED, UNCHANGED, BLANK, TAKEN, UNKNOWN_PROFILE }
     fun renameProfile(profileId: String, newName: String): RenameProfileResult
     ```
     Put the enum in `domain/model/ProfileNames.kt`. Normalize the name, then return `BLANK` if it's empty and `UNKNOWN_PROFILE` if no profile has that id. If it equals the current name exactly, return `UNCHANGED` without writing. If `isProfileNameTaken(_profiles.value, name, profileId)`, return `TAKEN`. Otherwise call `updateProfiles { list -> list.map { if (it.id == profileId) it.copy(displayName = name, updatedAtEpochMillis = now) else it } }` and return `RENAMED`. Do the taken check inside the `updateProfiles` transform, against `cur`, so two quick renames can't race. Changing only the case of the own name ("ella" → "Ella") is allowed, because the own id is excluded. Don't change `id`: it's the key for level progress, friends and the backup conflict check.
   - `createProfile` (`:470`): store `uniqueProfileName(cur, normalizeProfileName(displayName).ifBlank { "Syskon" })`. The UI blocks duplicates before this point (steps 6-7), so this only acts as a safety net and never fails. That also keeps the two `LixoApp.kt` callers and existing tests unchanged. Compute it inside the `updateProfiles` transform, or read `_profiles.value` right before; both are fine since creation runs on the main thread.
   - `importProfileBackup` (`:657`): when building the imported profile, set `displayName = uniqueProfileName(_profiles.value, backup.profile.displayName, excludingProfileId = backup.profile.id)`. `AS_NEW` then gets "Ella 2" next to a local "Ella". `REPLACE_EXISTING` keeps the backup's name unless another sibling already has it.
4. `LixoStrings.kt`: add, near the other `PARENT_*` strings:
   - `PARENT_PROFILE_NAME_TITLE = "Namn"`
   - `PARENT_RENAME_BTN = "Byt namn"`
   - `PARENT_RENAME_SAVE_BTN = "Spara"`
   - `PARENT_RENAME_CANCEL_BTN = "Avbryt"`
   - `PROFILE_NAME_TAKEN = "Det namnet finns redan"` (near `PROFILE_NAME_PLACEHOLDER`)
5. `FirstProfileSetupScreen.kt` `ProfileNameForm`: add the parameter `errorText: String? = null`. Pass `isError = errorText != null` and `supportingText = errorText?.let { { Text(it) } }` to the `OutlinedTextField`, and add `Modifier.testTag("profile_name_field")`. Cap input with `onNameChange(it.take(BalanceConfig.PROFILE_NAME_MAX_LENGTH))`.
6. `ProfileSelectAndTutorialScreen.kt:252-273` (sibling form): compute `val nameTaken = isProfileNameTaken(profiles, newSiblingName)`. Pass `errorText = if (nameTaken) LixoStrings.PROFILE_NAME_TAKEN else null` to `ProfileNameForm`. Gate both the button's `enabled` and the click guard on `newSiblingName.isNotBlank() && !nameTaken`.
7. `FirstProfileSetupScreen`: no duplicate check. No profile exists yet when it shows.
8. `ParentGateAndDashboardScreen.kt`:
   - Add the parameter `onRenameProfile: (profileId: String, newName: String) -> RenameProfileResult = { _, _ -> RenameProfileResult.UNCHANGED }` after `onUpdateProfileGoalsAndSettings`. The default keeps existing test hosts (`ProfileBackupTest` etc.) compiling.
   - Right after the sibling switcher `Row` (after line 281), add a `LixoCard` like the "Dagliga mål" card. It has the title `PARENT_PROFILE_NAME_TITLE` and two states, kept in `var editingName by remember(activeProfile.id) { mutableStateOf(false) }` and `var nameInput by remember(activeProfile.id) { mutableStateOf(activeProfile.displayName) }`. Keying on `activeProfile.id` resets the edit state when the parent switches sibling.
     - **Viewing**: a row with `activeProfile.displayName` (titleMedium, bold) and a `LixoButtonVariant.SECONDARY` button `PARENT_RENAME_BTN` with `testTag("parent_rename_btn")`. On tap, set `nameInput = activeProfile.displayName` and `editingName = true`.
     - **Editing**: an `OutlinedTextField` with `testTag("parent_name_field")`, `singleLine = true`, `onValueChange = { nameInput = it.take(BalanceConfig.PROFILE_NAME_MAX_LENGTH) }`, `placeholder = PROFILE_NAME_PLACEHOLDER`, the same colours/shape as the gate field, `keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)` and `keyboardActions = KeyboardActions(onDone = { save() })`. Compute `val nameTaken = isProfileNameTaken(profiles, nameInput, activeProfile.id)`. When it's true, set `isError = true` and `supportingText = { Text(LixoStrings.PROFILE_NAME_TAKEN) }`. Add a `FocusRequester` with `LaunchedEffect(editingName) { if (editingName) focusRequester.requestFocus() }`. Below it, a row with a PRIMARY button `PARENT_RENAME_SAVE_BTN` (`testTag("parent_name_save")`, `enabled = nameInput.isNotBlank() && !nameTaken`) and a GHOST button `PARENT_RENAME_CANCEL_BTN`, which sets `editingName = false`.
     - `save()` is a local lambda that both Save and Done call. If `nameInput.isNotBlank() && !nameTaken`, it calls `onRenameProfile(activeProfile.id, nameInput)`. It closes the editor (`editingName = false`) on `RENAMED`/`UNCHANGED`. On `TAKEN`/`BLANK` it stays open, and the error text already covers `TAKEN`. Done with a taken name does nothing.
9. `LixoApp.kt:602`: pass `onRenameProfile = { id, name -> repository.renameProfile(id, name) }`.

## Tests / verification
- commonTest, `composeApp/src/commonTest/kotlin/se/lixo/app/ProfileStoreTest.kt` (uses the in-file fake `ProfileStore` and `awaitPendingProfileWrites()`):
  - `renameProfile_persistsAcrossRepositoryInstances`: create "Ella", rename to "  Elsa  ", await writes. A new repo on the same store loads `displayName == "Elsa"` with the same `id`.
  - `renameProfile_blankName_isRejected`: `renameProfile(id, "   ")` returns `BLANK` and the name is unchanged.
  - `renameProfile_unknownId_returnsUnknownProfile`.
  - `renameProfile_truncatesToMaxLength`: a 30-char name is stored as its first `PROFILE_NAME_MAX_LENGTH` chars.
  - `renameProfile_onlyTouchesTargetProfile`: with two siblings, renaming one leaves the other unchanged.
  - `renameProfile_duplicateName_isRejected`: with "Ella" and "Olle", renaming Olle to " ella " returns `TAKEN` and both names stay the same. Nothing extra is written to the store.
  - `renameProfile_caseChangeOfOwnName_isAllowed`: "ella" → "Ella" returns `RENAMED`.
  - `createProfile_duplicateName_getsSuffix`: `createProfile("Ella")` twice gives "Ella" and "Ella 2". A third call gives "Ella 3".
  - `importAsNew_duplicateName_getsSuffix`: with a local "Ella", importing an `AS_NEW` backup of a different-id "Ella" gives "Ella 2".
- commonTest, new `composeApp/src/commonTest/kotlin/se/lixo/app/ProfileNamesTest.kt`, for the pure helpers:
  - `isProfileNameTaken` is case- and whitespace-insensitive and skips `excludingProfileId`.
  - `uniqueProfileName` returns the name unchanged when it's free, otherwise " 2", " 3" and so on. With a 20-char name the result still fits 20 chars.
  - `normalizeProfileName` trims the name and caps it.
- Instrumented test (new), `androidApp/src/androidTest/kotlin/se/lixo/app/verify/ParentRenameProfileTest.kt`. Copy the host from `verify/ProfileBackupTest.kt` (`createAndroidComposeRule<ComponentActivity>()`, `AndroidPlatformContext.init`, a real `LixoRepository` with `createAndroidProfileStore(ctx, "lixo_test_050.db")`, `FakeCloudGateway`, `FakeFriendCloud`, `TestContent.repository`, `LixoTheme { ParentGateAndDashboardScreen(...) }` with `onRenameProfile = { id, n -> repo.renameProfile(id, n) }`, and the `unlockGate()` helper). Delete the test DB in `@Before`/`@After`.
  - `parentRenamesProfile_namePersists`: seed "Ella" and unlock the gate. Click `parent_rename_btn` and assert `parent_name_field` is focused. Clear the text, type "Elsa" and click `parent_name_save`. Assert "Elsa" exists (the switcher chip and the name card) and "Ella" doesn't. Then build a second `LixoRepository` on the same DB file, wait until its profiles load (`waitUntil`), and assert the profile with the same id has `displayName == "Elsa"`. Save a screenshot to `lixo_screenshots/050_renamed.png`.
  - `rename_imeDone_saves`: type "Alva" and run `performImeAction()` on `parent_name_field`. Assert "Alva" shows and the field is gone.
  - `rename_blank_saveDisabled_cancelRestores`: clear the field and assert `parent_name_save` `assertIsNotEnabled()`. Click `PARENT_RENAME_CANCEL_BTN` and assert the original name still shows.
  - `rename_switchSibling_resetsEditState`: seed "Ella" and "Olle". Start editing on Ella, then tap the "Olle" chip. Assert `parent_name_field` doesn't exist and "Olle" is shown in the name card.
  - `rename_toSiblingName_showsErrorAndBlocksSave`: seed "Ella" and "Olle" with Ella active. Edit and type "olle". Assert `PROFILE_NAME_TAKEN` exists and `parent_name_save` `assertIsNotEnabled()`. Run `performImeAction()` and assert the field is still shown and the repo name is still "Ella". Save a screenshot to `lixo_screenshots/050_name_taken.png`.
- Instrumented test (new), `androidApp/src/androidTest/kotlin/se/lixo/app/verify/SiblingDuplicateNameTest.kt`. Host `ProfileSelectAndTutorialScreen` the way `verify/TutorialUnreadWordsTest.kt` does, but with a real repository on `createAndroidProfileStore(ctx, "lixo_test_050_sibling.db")`, `onCreateSiblingProfile = { repo.createProfile(it, BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) }` and an existing "Ella". Open the sibling form, type "Ella " in `profile_name_field`, and assert `PROFILE_NAME_TAKEN` shows and the create button is disabled. Change the text to "Elsa" and assert the error is gone. Click create and assert the repo now has "Ella" and "Elsa". Save a screenshot to `lixo_screenshots/050_sibling_taken.png`.
- Regression: `se.lixo.app.verify.ProfileBackupTest` (import as new/replace still works; names may now get a suffix), `se.lixo.app.verify.ProfilePersistenceTest`, `se.lixo.app.verify.FirstProfileSetupTest`.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ParentRenameProfileTest,se.lixo.app.verify.SiblingDuplicateNameTest,se.lixo.app.verify.ProfileBackupTest,se.lixo.app.verify.ProfilePersistenceTest,se.lixo.app.verify.FirstProfileSetupTest`

## Decisions
- Max length is 20 characters (confirmed).
- Profiles saved before this change that already share a name are left as they are, with no migration (confirmed). The parent can still rename one of them, because the check skips the profile's own id.

## Open questions
None.
