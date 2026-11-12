# 043 — Reading: remove wrong/skip debug buttons, last-word-missed fallback

**Type**: feature

**Goal**: Remove the "Säg fel ord" and "Hoppa över ord" buttons. Replace the always-visible debug "Läs hela meningen" with a fallback button for all builds. It shows only when the cursor is on the last story word and the child has stalled, meaning they probably read it and STT missed it.

**Context**
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/reading/ReadingActivityScreen.kt:494-614`: AI-mode controls inside `if (showDebugSimulationControls)`: "Läs nästa ord" (keep, debug), wrong-word (529-554), skip-word (558-584), full-sentence (588-613).
- `millisSinceLastWordAdvance` state is at `ReadingActivityScreen.kt:120`. It is reset when a word advances (146) and incremented in the tick loop (163).
- `ForcedAlignmentEngine.kt:170-302` `processRecognizedTokens`. `isAwaitingUnreadWords = reachedEnd && !allCompleted`. `finishWithUnreadWords` is at 509.
- `BalanceConfig.STUCK_WORD_PROMPT_DELAY_MS = 4_500L` (`BalanceConfig.kt:118`).
- Strings are in `LixoStrings.kt:72-77`.
- Tests that use the removed buttons: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/HeardWordPopupTest.kt:137-142, 230-235`.

**Steps**
1. Engine (`ForcedAlignmentEngine.kt`):
   - Add `fun isLastWordMissedCandidate(state, millisSinceLastWordAdvance): Boolean`. It returns true when mode is AI_LISTENING, the session is not completed, not `isAwaitingUnreadWords`, `currentWordIndex == words.lastIndex`, every word after the cursor is completed (trivially true at lastIndex), and `millisSinceLastWordAdvance >= BalanceConfig.LAST_WORD_MISSED_FALLBACK_DELAY_MS`.
   - Add `fun creditMissedLastWord(state): ReadingAlignmentSessionState`. Guarded by the same structural checks (no time check). It marks `words[lastIndex]` `isCompleted = true`, keeps `needsPractice` as is, and does not add it to struggled. It moves the cursor to `words.size` and recomputes `isCompleted = all completed` and `isAwaitingUnreadWords = !allCompleted`. Clear `isWordStuckSupportVisible` when completed. Reuse the same end-of-state logic as lines 291-301 (extract a small private helper if useful).
2. `BalanceConfig.kt`: add `LAST_WORD_MISSED_FALLBACK_DELAY_MS = STUCK_WORD_PROMPT_DELAY_MS` (4.5 s), with a KDoc.
3. `LixoStrings.kt`: delete `READING_SIMULATE_SKIP_WORD_BTN`, `READING_SIMULATE_WRONG_WORD_BTN` and `READING_SIMULATE_FULL_SENTENCE_BTN`. Add `READING_LAST_WORD_MISSED_BTN = "Jag har läst klart"`.
4. `ReadingActivityScreen.kt`:
   - Delete the wrong-word, skip-word and full-sentence buttons and their spacers. Keep "Läs nästa ord" under `showDebugSimulationControls`.
   - Outside the debug gate, in AI mode and not completed: when `alignmentEngine.isLastWordMissedCandidate(sessionState, millisSinceLastWordAdvance)` is true, show an OUTLINE `LixoButton` (46.dp, `labelMedium`, Blue, same style as the removed buttons) with `READING_LAST_WORD_MISSED_BTN`. onClick: `sessionState = alignmentEngine.creditMissedLastWord(sessionState); millisSinceLastWordAdvance = 0L`. If yellow words remain, the existing `isAwaitingUnreadWords` card and "Fortsätt med gula ord" button take over. Otherwise the completion LaunchedEffect fires.
5. Check `ProfileSelectAndTutorialScreen.kt` for references to the removed constants (only `READING_SIMULATE_VOICE_BTN` expected). Fix any compile errors.

**Tests / verification**
- commonTest (engine tests, e.g. next to the existing `LixoEnginesTest` / alignment tests):
  - Candidate is false at session start, false on the last word before the delay, and true on the last word at or after the delay.
  - Candidate is false when the cursor is not on the last word, false when `isAwaitingUnreadWords`, and false in MANUAL mode.
  - `creditMissedLastWord` with no yellow words gives `isCompleted`. With an earlier yellow word it gives `isAwaitingUnreadWords`, not completed, and the yellow word is still unread. It does not touch earlier words.
- Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/`):
  - Update `HeardWordPopupTest`: replace the wrong-word and skip-word button clicks with emitting tokens through `SilentSttEngine.tokens`. Emit a wrong token `"lxq"` for word 0, then the word-1 token (`"är"`), so "Lixo" turns yellow with heardText "lxq". Use `compose.mainClock.advanceTimeBy` like the existing code.
  - New `ReadingLastWordFallbackTest` (pattern: `HeardWordPopupTest`, real `ForcedAlignmentEngine` + `DummyData.populatedRepository()`, `showDebugSimulationControls = false`):
    1. `fallbackHiddenAtStart`: after starting AI reading, "Jag har läst klart", "Säg fel ord", "Hoppa över ord" and "Läs hela meningen" do not exist.
    2. `fallbackAppearsWhenStuckOnLastWord_andFinishes`: emit tokens for all words except the last, advance the clock by ≥4.5 s, assert the button exists, click it, assert that `onFinishReadingSession` was called (capture a flag) or that the completion UI is shown.
    3. `fallbackWithYellowWords_goesToYellowFlow`: skip one middle word by emitting a later word, read on to the last word, wait, click the fallback, assert `READING_CONTINUE_WITH_YELLOW_BTN` exists.
    - Capture `onRoot().captureToImage()` evidence.
  - Run on Pixel 10 emulator (unlock PIN 111111 first if locked): `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ReadingLastWordFallbackTest,se.lixo.app.verify.HeardWordPopupTest`

**Open questions**
- (none — label "Jag har läst klart" confirmed by user)
