# 007 — Tutorial stuck on skipped words

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

**Verification device**: The user started a Pixel 10 emulator, `emulator-5554`, and it is used for verification instead of R5CXC0681EM, which is not connected.
- When running the pending tasks, set `verifyNotes` to `"Device emulator-5554 (Pixel 10 emulator). ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>"`.
- On the emulator, the 007 test feeds STT through the fake engine (there is no real mic). 008 uses the real Room DB on the emulator.
