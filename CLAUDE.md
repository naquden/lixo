# Project Rules

## Feature / bug requests → plan + FEATURES.md task

When the user says "add feature …", "fix bug …" (or similar) in this project, do NOT implement right away. Instead:

1. Investigate the codebase enough to write a concrete plan.
2. Write the plan to `doc/plans/<NNN>-<short-kebab-slug>.md` (NNN = next free 3-digit number). Plan contains:
   - **Type**: feature | bug
   - **Goal**: what the user asked, in one or two sentences
   - **Context**: relevant files (`path:line`), current behaviour, root cause for bugs
   - **Steps**: ordered implementation steps, specific enough for another agent to execute without re-investigating
   - **Tests / verification**: unit tests to add/update, how to verify in the app
   - **Open questions**: anything ambiguous (keep empty if none)
3. Add a task line to `FEATURES.md` under `## Pending`, referencing the plan:
   `- [ ] NNN — <title> (feature|bug) → [plan](doc/plans/NNN-slug.md)`
4. Report the plan path and a short summary. Stop there.

Tasks are executed later in batch (e.g. `use workflow` with model-split-implement on all pending tasks in `FEATURES.md`). When a task is implemented, tick it (`- [x]`) and move it to `## Done`.

### Running pending tasks

The workflow does not read `FEATURES.md` itself — the main agent does, in a loop that runs until `## Pending` is empty. This loop overrides the global "do not commit" rule: it commits, but never pushes.

1. **Collect.** Re-read `FEATURES.md` from disk and take the `## Pending` lines, ordered by their stated dependencies (e.g. "after 001" runs after 001), otherwise by number.
2. Turn each into a task string that points at its plan: `"NNN — <title>: implement exactly per doc/plans/NNN-slug.md (read it first)"`.
3. **Run** `model-split-implement` with `{ tasks: [...], batchVerify: true, commitEach: true }` and `env: { verifyMode: "android-instrumented", verifyNotes: "Device: Pixel 10 emulator (AVD Pixel_10, adb serial emulator-5554). Screen-lock PIN 111111: if locked, unlock first with `adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP && adb -s emulator-5554 shell wm dismiss-keyguard && adb -s emulator-5554 shell input text 111111 && adb -s emulator-5554 shell input keyevent KEYCODE_ENTER`. ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>, using the instrumented test classes listed in each plan" }`.
   - Per task in order: opus analyse → sonnet implement → haiku build + unit tests (fix build errors immediately) → haiku **commits that task** (subject = the task string; files that were dirty before the run are never staged).
   - Then one device verification over all the committed tasks, one opus diagnosis across all failures (incl. cross-task interactions), fixes in task order with **one commit per fix** (`Fix after verify round N: …`), re-verify only failures. One opus review of the whole run (`git diff <baseSha>`) at the end.
4. **Record.** Tick passed tasks and move them to `## Done` with a one-line verification note (test class + result, evidence path). Failed tasks stay in `## Pending` with a note on the root cause. Commit `FEATURES.md` on its own: `Update FEATURES.md: done NNN, NNN`. If the review reported HIGH findings, fix them before the next step (sonnet) and commit as `Address review: …`.
5. **Repeat.** Go back to step 1. New tasks added to `FEATURES.md` while the run was going are picked up here, along with failed tasks, which get retried. Stop only when `## Pending` is empty.
   - A task that has failed in two runs with the same root cause is not retried again. Leave it in `## Pending` with the note, skip it in later runs, and treat the loop as done when only such tasks are left. Report them at the end.

## Verifying fixes on device

Verify UI changes with **Compose instrumented tests** that render the real composables the same way the app does, with real engines/repository, not mocks of the UI. Don't drive the app by hand with adb taps or screenshots of a lock screen.

- Location: `androidApp/src/androidTest/kotlin/se/lixo/app/` (follow the `screenshots/ScreenshotTest.kt` pattern: `createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme { … }`).
- Assert with semantics (`onNodeWithText`, `onNodeWithContentDescription`, `assertExists`/`assertDoesNotExist`, `performClick`). Capture `onRoot().captureToImage()` as evidence when visuals matter.
- Device: **Pixel 10 emulator** — AVD `Pixel_10`, adb serial **`emulator-5554`**. Don't use the Pixel 7 (`192.168.50.202:5555`) or the S25 (`R5CXC0681EM`) unless asked.
  - Screen-lock **PIN `111111`**. If the emulator is locked (tests fail with "No compose hierarchies found" or screenshots show the lock screen), unlock before running: `adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP && adb -s emulator-5554 shell wm dismiss-keyguard && adb -s emulator-5554 shell input text 111111 && adb -s emulator-5554 shell input keyevent KEYCODE_ENTER`.
  - If it isn't running (`adb devices` has no `emulator-5554`), start it with `emulator -avd Pixel_10` (in `$ANDROID_HOME/emulator`) and wait for `adb -s emulator-5554 wait-for-device shell getprop sys.boot_completed` = `1`, then unlock.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (add `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` to run a single class).
- **Never leave the app uninstalled.** `connectedDebugAndroidTest` uninstalls the app after the run unless `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` is passed (always pass it). If the app was removed anyway, reinstall with `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:installDebug`.
- Every plan's **Tests / verification** section should list the instrumented tests to add, alongside the commonTest unit tests.

Only implement directly if the user explicitly says so (e.g. "implement now", "run the tasks", "do it directly").
