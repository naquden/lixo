# 039 — Pause course timer while waiting for "Nästa uppgift"

**Type**: feature

## Goal
During course play (math, English, geography — all run in `MathActivityScreen`), the elapsed-time counter must pause after the player has picked the correct answer and the "Snyggt jobbat!" sheet is waiting for them to tap NÄSTA UPPGIFT. It resumes when the next question is shown. Time spent reading the feedback should not count against the star time requirement.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/MathActivityScreen.kt:146` — `var elapsedSeconds by remember { mutableIntStateOf(0) }`.
- `MathActivityScreen.kt:163-178` — `LaunchedEffect(allSolved) { while (!allSolved) { delay(1000L); elapsedSeconds++ } … }` ticks unconditionally, also while the feedback sheet is up. When all are solved it evaluates the session (`mathEngine.evaluateMathSession(elapsedSeconds, level.timeRequirementSeconds)`) and calls `onFinishMathSession`.
- `MathActivityScreen.kt:358-380` — `onChoiceSelected`: on correct answer sets `lastCheckResult = true` and the attempt `isSolved = true`; on wrong answer `lastCheckResult = false` (player keeps trying the same question → timer should keep running).
- `MathActivityScreen.kt:527-535` — NÄSTA UPPGIFT button: `lastCheckResult = null; wrongChoices = emptySet(); currentIndex++`.
- `MathActivityScreen.kt:232` — timer pill shows `"${elapsedSeconds}s"`.
- Waiting state = `currentAttempt?.isSolved == true && !allSolved` (equivalently `lastCheckResult == true` before the last question). On the last question `allSolved` becomes true at the same moment and the loop already stops, so no change there.
- Current 1 s `delay` loop also loses/gains the partial second at each pause boundary; switch to millisecond accounting so pauses are exact.

## Steps
1. In `MathActivityScreen.kt` replace `elapsedSeconds` state with `var elapsedMillis by remember { mutableLongStateOf(0L) }` and a derived `val elapsedSeconds = (elapsedMillis / 1000L).toInt()` (keep the name so the pill, `evaluateMathSession` and `onFinishMathSession(…coerceAtLeast(1))` keep working).
2. Add `val awaitingNext = currentAttempt?.isSolved == true && !allSolved` (computed after `currentAttempt`; move the `currentAttempt` declaration above the timer effect if needed).
3. Split the timer from the completion logic:
   - Timer: `LaunchedEffect(allSolved, awaitingNext) { if (allSolved || awaitingNext) return@LaunchedEffect; var last = withFrameMillis { it }; while (true) { val now = withFrameMillis { it }; elapsedMillis += now - last; last = now } }` (or a `delay(100L)` loop using a monotonic clock source already in the project — check `PlatformContracts.kt:138` monotonic clock; frame clock is fine and is driven by the test clock in Compose tests). Restarting the effect on resume must not count the paused time (take a fresh `last` on start).
   - Completion: keep a separate `LaunchedEffect(allSolved) { if (allSolved && completedResult == null) { …evaluate + onFinishMathSession… } }` unchanged in behaviour.
4. Do not pause on a wrong answer (`lastCheckResult == false`) — the question is still open.
5. Optional visual: while `awaitingNext`, render the timer pill/icon dimmed (e.g. `alpha = 0.5f`) and add a semantics `contentDescription`/testTag `"math_timer"` on the pill text so tests can read it. Keep the text format `"Ns"`.

## Tests / verification
- Instrumented (new): `androidApp/src/androidTest/kotlin/se/lixo/app/verify/CourseTimerPauseTest.kt` — follow `MathChoiceNoPrefixTest.kt` setup (`createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme { MathActivityScreen(…) }` with a real level/repository). Use `composeTestRule.mainClock.autoAdvance = false`:
  1. Advance 3 s → timer shows `3s`.
  2. Click the correct choice → "Snyggt jobbat!" exists. Advance 10 s → timer still `3s`.
  3. Click NÄSTA UPPGIFT, advance 2 s → timer `5s`.
  4. Click a wrong choice, advance 2 s → timer `7s` (keeps running on wrong answer).
  5. Capture `onRoot().captureToImage()` while paused as evidence → `temp/verify/039/`.
  - Also run one geography and one English variant (or parametrize) since they share the screen; at minimum re-run `GeographyFlagChallengeTest`, `EnglishChoiceOrderTest`, `MathChoiceNoPrefixTest` as regression.
- Unit: none needed for engine (scoring unchanged); if the tick logic is extracted into a small helper (e.g. `PausableStopwatch` in commonMain), add `PausableStopwatchTest` in commonTest covering pause/resume not counting paused time.
- Instrumented classes to run: `se.lixo.app.verify.CourseTimerPauseTest,se.lixo.app.verify.MathChoiceNoPrefixTest,se.lixo.app.verify.EnglishChoiceOrderTest,se.lixo.app.verify.GeographyFlagChallengeTest`.

## Open questions
(none)

Resolved: the timer keeps running during the wrong-answer "Inte riktigt — prova igen!" sheet — the question is still open and we are waiting for the player's answer (confirmed by user 2026-11-11).
