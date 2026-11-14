# 049 — Friend rename field hidden by keyboard

**Type**: bug

## Goal
In the friend detail sheet, tapping "Byt namn" must show the text field above the keyboard. The sheet should scroll, and typing a name should feel natural.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoComponents.kt:590-621`: the sheet's `Column` in `LixoBottomSheet` has no `verticalScroll`. It sits at the bottom of a full-size scrim `Box`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/social/AvatarMapScreen.kt:501-652`: `FriendDetailSheetContent` is tall. It stacks a 260dp avatar, the name, the rename field or button, the gear list, and two more buttons.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:288`: the root has `safeDrawingPadding()`, which includes the keyboard. When the keyboard opens, the whole app gets shorter. The sheet is drawn inside the Scaffold content, above `LixoBottomNavBar` (`LixoApp.kt:471-502`). The bottom nav bar stays visible above the keyboard and takes up more space.

Current behaviour: the sheet's content is taller than the space left. A `Column` that can't scroll clips its bottom part, so the field, the "Spara" button and everything below it are cut off. Only a sliver of the field's blue outline shows above the nav bar.

Root cause: the sheet is not scrollable. Compose text fields scroll themselves into view when focused, but only inside a scrollable parent.

## Steps
1. In `LixoBottomSheet` (`LixoComponents.kt`), add `.verticalScroll(rememberScrollState())` to the inner `Column` after `clip`/`background` and before `.padding(24.dp)`. This is safe for every caller: none of them uses vertical `weight` or a lazy list. `CampaignMapScreen` and `EventBanner` content just becomes scrollable when it overflows. `AvatarViewport` only listens for horizontal drags, so it doesn't block vertical scrolling.
2. In `LixoApp.kt`, hide `LixoBottomNavBar` while the keyboard is open. In the Scaffold's `bottomBar`, wrap the nav bar in `if (!WindowInsets.isImeVisible)` and opt in with `@OptIn(ExperimentalLayoutApi::class)`. This frees up room on every screen with a text field (rename, add-friend code, parent gate).
3. In `FriendDetailSheetContent` (`AvatarMapScreen.kt`, around line 547), make the rename field easier to use:
   - Add a `FocusRequester` with `LaunchedEffect(editingLabel) { if (editingLabel) focusRequester.requestFocus() }`, so tapping "Byt namn" focuses the field and opens the keyboard right away.
   - Set `singleLine = true` and `keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done)`.
   - Set `keyboardActions = KeyboardActions(onDone = { save })`. Pull the save lambda out of the "Spara" button so both share it.
   - Add `Modifier.testTag("friend_label_field")`.
4. Give `FriendNameStepSheet`'s field (`AvatarMapScreen.kt`, around line 461) the same `singleLine`, `keyboardOptions` and `onDone = { onSave(...) }`, plus `testTag("friend_name_step_field")`. It sits in the same kind of sheet and has the same problem.

## Tests / verification
- New instrumented test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FriendRenameKeyboardTest.kt`. It follows `FriendLabelTest`: `FakeFriendCloudStore`, `friendRepoFor`, `FriendVillageTestHost`.
  - `renameField_reachableOnShortWindow`: host the friend village inside `Box(Modifier.fillMaxWidth().height(420.dp))` to stand in for a window shrunk by the keyboard. Add a friend, tap `friend_plot_<uid>`, then tap `FRIEND_SHEET_CHANGE_NAME_BTN`. Assert that `friend_label_field` is focused (`assertIsFocused`) and displayed (`assertIsDisplayed`). Type "Olle" and press the keyboard's Done action (`performImeAction`). Assert that "Olle" shows up and that `repo.friendsOfActiveProfile().first().label == "Olle"`. Save a screenshot to `lixo_screenshots/friends/049_rename_short_window.png`.
  - `detailSheet_scrollsToRemoveButton_onShortWindow`: same 420dp host. Run `onNodeWithText(FRIEND_SHEET_REMOVE_BTN).performScrollTo().assertIsDisplayed()` to prove the sheet scrolls.
- Regression tests: `se.lixo.app.verify.FriendLabelTest`, `se.lixo.app.verify.FriendCodeFlowTest`, `se.lixo.app.verify.FriendRevealTest`.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.FriendRenameKeyboardTest,se.lixo.app.verify.FriendLabelTest,se.lixo.app.verify.FriendCodeFlowTest,se.lixo.app.verify.FriendRevealTest`
- No commonTest unit tests: this is a UI-only change.

## Open questions
None. Hiding the bottom nav while the keyboard is open applies to the whole app, not just this screen.
