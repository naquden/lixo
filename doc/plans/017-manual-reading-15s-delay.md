# 017 — "Läsa själv": complete button unlocks after a fixed 15 seconds

**Type**: feature
**Depends on**: none.

## Goal
In "Läsa själv" (manual reading mode), the "Jag har läst klart" button should unlock after **15 seconds** instead of the current word-count-based delay.

## Context
- Delay calculation: `ForcedAlignmentEngine.calculateMinimumManualReadingMillis(wordCount)` (`domain/engine/ForcedAlignmentEngine.kt:120-124`) = `max(wordCount * MANUAL_MODE_MIN_SECONDS_PER_WORD, MANUAL_MODE_MIN_TOTAL_SECONDS) * 1000`.
- Constants: `BalanceConfig.MANUAL_MODE_MIN_SECONDS_PER_WORD = 0.35f`, `MANUAL_MODE_MIN_TOTAL_SECONDS = 4.0f` (`domain/model/BalanceConfig.kt:46-51`). Only `calculateMinimumManualReadingMillis` uses them.
- The result feeds `manualModeMinRequiredMillis` / `manualModeRemainingSeconds` / `isManualCompleteButtonEnabled` (`ForcedAlignmentEngine.kt:50-57`). The countdown label "Läser... (Ns)" is shown in `ui/screens/reading/ReadingActivityScreen.kt:615-625` and in the tutorial `ui/screens/onboarding/ProfileSelectAndTutorialScreen.kt:567-575`. Both read the session state, so neither needs changing.
- Existing test: `LixoEnginesTest.manualReadingMode_enforcesMinimumDurationAndOneStarCapAndNoGifts` (`composeApp/src/commonTest/kotlin/se/lixo/app/LixoEnginesTest.kt:461`) advances by `manualModeMinRequiredMillis + 500`, so it stays valid.

## Steps
1. `BalanceConfig.kt`: replace both constants with `const val MANUAL_MODE_UNLOCK_SECONDS = 15` and update the KDoc ("Fixed wait in Manual Reading Mode before 'Jag har läst klart' unlocks; same for every story, see doc/plans/017").
2. `ForcedAlignmentEngine.calculateMinimumManualReadingMillis`: return `BalanceConfig.MANUAL_MODE_UNLOCK_SECONDS * 1000L`. Keep the `wordCount` parameter (callers pass it) but mark it unused in the KDoc, or remove it and update the callers. `grep -rn calculateMinimumManualReadingMillis` covers all of them.
3. The tutorial uses the same engine, so its manual step also becomes 15 s. That's intended.

## Tests / verification
commonTest (`LixoEnginesTest.kt`):
- New `manualReadingMode_unlocksAfterExactly15Seconds`: start a MANUAL session (short and long story texts). `manualModeMinRequiredMillis == 15_000` for both. After `onTick(14_999)` the button is disabled and `manualModeRemainingSeconds` is 1. After a total of `15_000` it's enabled.
- The existing `manualReadingMode_enforcesMinimumDurationAndOneStarCapAndNoGifts` still passes.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/ManualReadingDelayTest.kt`, `ScreenshotTest` pattern, real `ReadingActivityScreen` with a seeded level + real `ForcedAlignmentEngine`, `compose.mainClock.autoAdvance = false`):
- Pick "Läsa själv" and start. The button shows "Läser... (15s)" and isn't enabled. `mainClock.advanceTimeBy(14_000)` → still disabled. `advanceTimeBy(1_500)` → "JAG HAR LÄST KLART" (`READING_MANUAL_DONE_BTN.uppercase()`) is enabled. Capture a screenshot of both states.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ManualReadingDelayTest` (Pixel_10 emulator per the current setup; `R5CXC0681EM` for the S25).

## Open questions
- I interpreted this as a **fixed** 15 s for every story. Previously long stories needed longer (0.35 s/word, e.g. 70 s for 200 words). If 15 s should instead be the new **minimum**, keeping the per-word scaling for long stories, change step 2 to `max(words * 0.35, 15) * 1000`.
