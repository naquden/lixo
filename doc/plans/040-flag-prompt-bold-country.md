# 040 — Flag question prompt: normal weight, bold only the country name

**Type**: feature

**Goal**: In the geography flag question prompt ("Vilken flagga har Finland?"), render the sentence in normal font weight and bold only the country name, so the country is easier and faster to spot.

**Context**:
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/content/GeographyChoices.kt:59` builds `promptSv = "Vilken flagga har ${country.nameSv}?"` — the country name is known at build time but not carried separately on `MathProblem`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/model/Models.kt:264-277` — `MathProblem` (`@Serializable`), no emphasis/formatting field today.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/MathActivityScreen.kt:295-300` — prompt is rendered as a single `Text(problem.promptSv, style = headlineLarge, fontWeight = FontWeight.ExtraBold, ...)`. This screen is shared by math, English, and geography problems, so the change must be scoped to flag prompts only — math/English prompts keep the current fully-bold rendering.
- `MathActivityScreen.kt:286` (TTS `textToSpeak`) and `:400` (error log `promptSv`) use the plain string — unaffected by this change.

**Steps**:
1. Add `val promptEmphasisSv: String? = null` to `MathProblem` (`Models.kt`). Default `null` keeps existing JSON/seed content and serialization compatible.
2. In `GeographyChoices.buildProblems`, set `promptEmphasisSv = country.nameSv` on the built `MathProblem`.
3. Add a small pure helper (e.g. `promptAnnotated(prompt: String, emphasis: String?): AnnotatedString?` in a new `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/PromptEmphasis.kt`, or similar discoverable spot) that:
   - returns `null` if `emphasis` is `null` or not found in `prompt` (`indexOf`), so callers fall back to current behavior,
   - otherwise returns an `AnnotatedString` with the full text at `FontWeight.Normal` and a `SpanStyle(fontWeight = FontWeight.ExtraBold)` applied only over the `emphasis` substring's range.
4. In `MathActivityScreen.kt`, where the prompt `Text` is rendered (`:295-300`): compute the annotated string via the helper using `problem.promptSv` and `problem.promptEmphasisSv`. If non-null, render `Text(annotatedString, style = headlineLarge, fontWeight = FontWeight.Normal, color = TextPrimary)` (per-span weight from the annotation overrides the base). If `null`, keep the existing `Text(problem.promptSv, style = headlineLarge, fontWeight = FontWeight.ExtraBold, ...)` path unchanged — this preserves current math/English rendering exactly.

**Tests / verification**:
- commonTest (`GeographyChoicesTest` and/or a new `PromptEmphasisTest`):
  - `GeographyChoices.buildProblems(...)` sets `promptEmphasisSv == "Sverige"` (or whichever country) for a flag question.
  - Helper: emphasis found in prompt → returned `AnnotatedString`'s span covers exactly the emphasis range with `FontWeight.ExtraBold`, rest of text is `FontWeight.Normal`.
  - Helper: emphasis is `null` → returns `null`.
  - Helper: emphasis not found as a substring of prompt → returns `null` (defensive fallback).
- Instrumented (extend `androidApp/src/androidTest/kotlin/se/lixo/app/verify/GeographyFlagChallengeTest.kt`, or add a focused `FlagPromptEmphasisTest` in the same package):
  - Render level_161 (or equivalent geography flag level) via the real `MathActivityScreen`.
  - Assert the prompt node still renders with the full expected text (`onNodeWithText("Vilken flagga har <country>?")`).
  - Capture `onRoot().captureToImage()` to `lixo_screenshots/verify040/flag_prompt.png` as visual evidence of the bold/normal split.
  - Add/keep a regression assertion that a non-flag (math) prompt still renders fully bold (no behavior change there) — e.g. reuse an existing math screenshot test or add one assertion in this test class.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.GeographyFlagChallengeTest`

**Open questions**: none.
