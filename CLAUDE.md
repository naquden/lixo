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

The workflow does not read `FEATURES.md` itself — the main agent does:

1. Collect the `## Pending` lines, ordered by their stated dependencies (e.g. "after 001" runs after 001), otherwise by number.
2. Turn each into a task string that points at its plan: `"NNN — <title>: implement exactly per doc/plans/NNN-slug.md (read it first)"`.
3. Run `model-split-implement` with `{ tasks: [...], batchVerify: true }` and `env: { verifyMode: "android-instrumented", verifyNotes: "Device R5CXC0681EM. ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>, using the instrumented test classes listed in each plan" }`.
   - batchVerify = per task in order: opus analyse → sonnet implement → haiku build + unit tests (fix build errors immediately). Then one device verification for all tasks, one opus diagnosis across all failures (incl. cross-task interactions), fixes in task order, re-verify only failures. One opus review of the whole diff at the end.
4. Afterwards: tick passed tasks and move them to `## Done` with a one-line verification note (test class + result, evidence path). Failed tasks stay in `## Pending` with a note on the root cause.

## Verifying fixes on device

Verify UI changes with **Compose instrumented tests** that render the real composables the same way the app does, with real engines/repository, not mocks of the UI. Don't drive the app by hand with adb taps or screenshots of a lock screen.

- Location: `androidApp/src/androidTest/kotlin/se/lixo/app/` (follow the `screenshots/ScreenshotTest.kt` pattern: `createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme { … }`).
- Assert with semantics (`onNodeWithText`, `onNodeWithContentDescription`, `assertExists`/`assertDoesNotExist`, `performClick`). Capture `onRoot().captureToImage()` as evidence when visuals matter.
- Device: **`R5CXC0681EM`** (Samsung S25, USB). Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (add `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` to run a single class).
- **Never leave the app uninstalled.** `connectedDebugAndroidTest` uninstalls the app after the run unless `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` is passed (always pass it). If the app was removed anyway, reinstall with `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:installDebug`.
- Every plan's **Tests / verification** section should list the instrumented tests to add, alongside the commonTest unit tests.

Only implement directly if the user explicitly says so (e.g. "implement now", "run the tasks", "do it directly").
