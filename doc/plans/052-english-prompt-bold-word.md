# 052 — English question prompt: normal weight, bold only the English word

**Type**: feature

**Goal**: In English course prompts like `Vad betyder ordet "hat"?` (with the example sentence below), render the sentence in normal font weight and bold only the English target word (`hat`), so it is easier and faster to spot — same pattern as flag country emphasis (040) and math expression emphasis (051).

**Context**:
- Shared prompt UI: `MathActivityScreen.kt:296-314` uses `promptWithEmphasis` when `MathProblem.promptEmphasisSv` is set; otherwise the whole prompt is ExtraBold.
- English problems are built inline in `MathActivityScreen.kt:128-141`:
  - `promptSv = "Vad betyder ordet \"${item.englishWord}\"?\n(${item.simpleSentenceEn})"`
  - `promptEmphasisSv` is **not** set today (defaults to null).
- Geography already sets `promptEmphasisSv = country.nameSv` in `GeographyChoices.kt:64`. Math will set expression emphasis in 051 via `MathCurriculum`. English should set `promptEmphasisSv = item.englishWord` (bare word, no quotes) on the `MathProblem` constructed at `:132-140`.
- `promptWithEmphasis` uses `indexOf`, so the first match is the quoted word on line 1. If the same word also appears in `simpleSentenceEn` (e.g. "hat"), that is fine — the first occurrence is the one that should be bold.
- Do **not** bold the surrounding Swedish frame text or the example sentence; only the English vocabulary word.
- No seed JSON / model / helper changes needed — prompts are built at runtime from `EnglishGlosaItem`.

**Steps**:
1. In `MathActivityScreen.kt` where English `MathProblem`s are mapped (`:132-140`), add `promptEmphasisSv = item.englishWord`.
2. Leave the render path, `PromptEmphasis.kt`, and `Models.kt` unchanged.
3. Optional (not required): move English problem building into `EnglishChoices` (mirror `GeographyChoices.buildProblems`) for consistency; if you do, keep the same `promptSv` template and set `promptEmphasisSv` there instead. Prefer the one-line field set in the screen unless a refactor is already touching that block.

**Tests / verification**:
- commonTest — assert that a built English problem has `promptEmphasisSv == englishWord` and that `promptSv` contains that word (can be a small unit test next to `EnglishChoicesTest`, or assert via a tiny helper that mirrors the screen template if problems stay inline-only). Also: `promptWithEmphasis("Vad betyder ordet \"hat\"?\n(The cat has a blue hat.)", "hat")` → ExtraBold span covers the first `"hat"` only (quoted word), not necessarily the one in the sentence — document expected range with `indexOf`.
- Instrumented — add `EnglishPromptEmphasisTest` (or extend `FlagPromptEmphasisTest`) in `androidApp/src/androidTest/kotlin/se/lixo/app/verify/`:
  - Render a real English level (e.g. `level_5` like `EnglishChoiceOrderTest`) via `MathActivityScreen`.
  - Assert full prompt text still matches; assert bold range is only `englishWord` (reuse `assertBoldRangeOnly` from `FlagPromptEmphasisTest` if accessible, or copy the helper).
  - Capture `onRoot().captureToImage()` as `lixo_screenshots/verify052/english_prompt.png`.
  - Flag case remains covered by existing `FlagPromptEmphasisTest` (040); do not regress it.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.EnglishPromptEmphasisTest` (adjust class name if extended into `FlagPromptEmphasisTest`).

**Open questions**: none.
