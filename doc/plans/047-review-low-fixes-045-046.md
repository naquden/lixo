# 047 — Fix LOW review findings from runs 044–046

- **Type**: bug
- **Goal**: Fix the LOW findings from the reviews of runs wf_31c61a54-6ba (045) and wf_b46fec8f-591 (046): stuck backup buttons, empty file left after a failed Android export, iOS temp-file/delegate leak on cancel, TalkBack reading map nodes twice ("Bana 1, 1"), and a redundant `WorldPage` parameter.

## Context

1. **Import confirm leaves `backupBusy` stuck**: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/parent/ParentGateAndDashboardScreen.kt:967-974`. The import-confirm `scope.launch` sets `backupBusy = true`, calls `onImportBackup(backup, mode).fold(...)`, then sets `backupBusy = false` with no `try/finally`. If `onImportBackup` throws (an unexpected repository exception or cancellation), both backup buttons (`enabled = !backupBusy`, lines 724 and 762) stay disabled. The export handler (≈701-719) and the import-open handler (≈736-756) already use `try/finally`.
2. **Failed SAF export leaves an empty file**: `composeApp/src/androidMain/kotlin/se/lixo/app/platform/AndroidProfileFilePicker.kt:17-36`. `CreateDocument` creates the file before returning the URI. If `writeBytes` fails for both `"wt"` and `"w"`, or throws, `saveText` returns `Failed` but the 0-byte `.json` file stays where the parent saved it. Importing that file later shows "Filen är inte en Lixo-säkerhetskopia."
3. **iOS export cleanup skipped on cancellation**: `composeApp/src/iosMain/kotlin/se/lixo/app/platform/IosProfileFilePicker.kt:45-69`. `activeDelegate = null` and `NSFileManager.defaultManager.removeItemAtURL(url, …)` run only after `suspendCancellableCoroutine` returns normally. If the coroutine is cancelled (the parent screen leaves composition while the picker is open), the temp file in `NSTemporaryDirectory` and the strong delegate reference are leaked.
4. **TalkBack reads each node twice**: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/map/CampaignMapScreen.kt:885-889`. The node `Box` sets `contentDescription = "Bana N"`, and the child number `Text`s are merged into the same node: the locked-node number `Text` (≈923) and the `Number3D` face/shadow `Text`s (≈945-985). TalkBack then reads "Bana 1, 1". The test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/CampaignMapLevelNumberScrollTest.kt:118-127` currently asserts the merged `SemanticsProperties.Text` equals "1"/"2" and that the CD equals "Bana 1".
5. **Redundant parameter**: `CampaignMapScreen.kt:328-329` passes `renderAvatar = pagerState.settledPage == page` and `isActivePage = pagerState.settledPage == page`. The two booleans in `WorldPage(...)` (lines 680-681) carry the same fact. `renderAvatar` is used at line 770 and `isActivePage` at lines 699-700.

Not in scope (no code change needed): review notes about the target-height fallback when centring (`CampaignMapScreen.kt:703`, screenshots show correct centring), and about commit hygiene/`gradle.properties` heap bump (kept on purpose).

## Steps

1. **ParentGateAndDashboardScreen.kt (import confirm)**: wrap it the same way as the other handlers:
   ```kotlin
   scope.launch {
       backupBusy = true
       try {
           backupStatus = onImportBackup(backup, mode).fold(
               onSuccess = { restored -> LixoStrings.parentBackupRestored(restored.displayName) },
               onFailure = { LixoStrings.PARENT_BACKUP_IMPORT_FAILED }
           )
       } finally {
           backupBusy = false
       }
   }
   ```
2. **AndroidProfileFilePicker.saveText**: once a `uri` has been obtained, every failure path (the `wrote == false` branch and the `catch`) must delete the created document before returning `Failed`: `runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }` (import `android.provider.DocumentsContract`). Ignore the result. Some providers don't support delete, and that is fine. Do not delete on `Saved`. Keep the function's signature and return values unchanged.
3. **IosProfileFilePicker.saveText**: move the cleanup into `try { … } finally { … }`:
   ```kotlin
   val result = try {
       suspendCancellableCoroutine<FileSaveResult> { cont -> /* unchanged */ }
   } finally {
       activeDelegate = null
       NSFileManager.defaultManager.removeItemAtURL(url, error = null)
   }
   result
   ```
   In `openText`, if `activeDelegate` is set there, clear it in a `finally` the same way (check the code; apply only if the same pattern exists).
4. **CampaignMapScreen.kt node semantics (TalkBack)**:
   - Make the node announce only its description. Replace `.semantics { contentDescription = … }` on the node `Box` with `.clearAndSetSemantics { contentDescription = …; if locked: disabled() }`. Keep `testTag` *before* it in the chain so the tag stays on the node. Make sure the node's click action still reaches accessibility. If `clickable` sits later in the same modifier chain, its semantics would be dropped. In that case add `onClick(label = null) { onNodeClick(level); true }` inside `clearAndSetSemantics` for unlocked nodes. Only use this approach if the existing assertions (`assertIsNotEnabled` on locked nodes, `performClick` elsewhere) still hold. Otherwise use the fallback: put `Modifier.clearAndSetSemantics {}` on each number `Text` (locked-number Text and the Text layers inside `Number3D`), and leave the node modifier as it is.
   - Make the description carry the lock state: add `const val MAP_LEVEL_NODE_LOCKED_SUFFIX = "låst"` to `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoStrings.kt` (next to `MAP_LEVEL_NODE_CD_PREFIX`, line 52). Locked nodes get CD `"Bana N, låst"`. Unlocked/gold nodes keep `"Bana N"`.
5. **CampaignMapScreen.kt `WorldPage` parameter**: remove `renderAvatar` from `WorldPage`'s signature (line 680) and its call site (line 328). Use `isActivePage` at line 770 (`if (isCurrentActiveNode && isActivePage)`). Check with grep that nothing else passes `renderAvatar` to `WorldPage` (previews, tests).
6. **Update CampaignMapLevelNumberScrollTest.levelNodes_showWorldLocalNumber** (lines 118-127):
   - Unlocked node: assert there is no merged `SemanticsProperties.Text` on the node (`texts.isEmpty()`), and `assertContentDescriptionEquals("Bana 1")`.
   - Locked node: `assertContentDescriptionEquals("Bana 2, låst")`, `assertIsNotEnabled()`.
   - The visible number is still covered by the existing `nodeShot(...)` captures. Keep them.
   - If the fallback in step 4 was used (per-Text `clearAndSetSemantics {}`), the same assertions apply.

## Tests / verification

- Build: `./gradlew :composeApp:testAndroidHostTest :androidApp:assembleDebug` and `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- Host unit tests: existing `ProfileBackupTest`, `ProfileImportTest`, `ProfileStoreTest` must stay green.
- New host unit test in `composeApp/src/androidHostTest/kotlin/se/lixo/app/` (only if `AndroidProfileFilePicker` can be exercised there with Robolectric; skip otherwise): not required.
- Instrumented (emulator-5554), all must pass:
  - `se.lixo.app.verify.CampaignMapLevelNumberScrollTest` (updated, 4 tests): no duplicate text on node, locked CD "Bana 2, låst", auto-scroll tests unchanged.
  - `se.lixo.app.verify.ProfileBackupTest` (4 tests) as a regression for the backup handlers.
  - Add `importConfirm_whenImportCancelled_reEnablesBackupButtons` to `androidApp/src/androidTest/kotlin/se/lixo/app/verify/ProfileBackupTest.kt`. Render the parent dashboard with an `onImportBackup` that throws `kotlinx.coroutines.CancellationException("test")`. A cancellation does not crash `rememberCoroutineScope`, while any other exception would crash the test process. Pick a valid backup via the fake picker and confirm. Assert the export and import buttons are enabled again with `assertIsEnabled()` (check the screen for their actual test tags). This test fails without step 1.
- Evidence: `temp/verify/047/` (node captures from the updated test).

## Open questions

None.
