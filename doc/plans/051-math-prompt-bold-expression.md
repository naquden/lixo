# 051 — Math question prompt: normal weight, bold only the expression

**Type**: feature

**Goal**: In math prompts like "Vad blir 5 + 3?", render the sentence in normal font weight and bold only the mathematical expression ("5 + 3"), so the numbers are easier and faster to spot — same pattern as flag prompts (040) which bold only the country name.

**Context**:
- Task 040 already added `MathProblem.promptEmphasisSv` and `promptWithEmphasis` (`composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/PromptEmphasis.kt`). When `promptEmphasisSv` is non-null and found in `promptSv`, `MathActivityScreen.kt:296-306` renders Normal base + ExtraBold on the emphasis span. When null, `:307-314` keeps the whole prompt ExtraBold.
- Geography sets `promptEmphasisSv = country.nameSv` in `GeographyChoices.kt:64`. Math does not set it: `MathCurriculum.buildMathProblems` at `MathCurriculum.kt:745-756` builds `MathProblem` with `promptSv = rendered.prompt` and leaves `promptEmphasisSv` at its default `null`.
- Expression templates in `MathCurriculum.render` (`:518-633`):
  - `ADD_SMALL` / `ADD_PLACE` / `ADD_CARRY`: `"Vad blir $a + $b?"` → emphasize `"$a + $b"`
  - `SUB_SMALL` / `SUB_BRIDGE` / `SUB_TENS` / `SUB_BORROW`: `"Vad blir $a - $b?"` → emphasize `"$a - $b"`
  - `DOUBLES`: `"Dubbelt: Vad blir $a + $a?"` → emphasize `"$a + $a"`
  - `TEN_CROSS`: `"Tiotalsövergång: Vad blir $a + $b?"` → emphasize `"$a + $b"`
  - `TEN_FRIEND`: `"Tiokompisar: $a + ? = 10. Vilket tal fattas?"` → emphasize `"$a + ? = 10"`
- Non-expression kinds (`CLOCK`, `MONEY`, `WORD_ADD`, `WORD_SUB`, `WORD_TWO_STEP`) have no single compact expression substring in the prompt; leave `promptEmphasisSv` null so they stay fully ExtraBold (same as today).
- UI / helper / model need no new fields — only curriculum must populate the existing optional emphasis.
- `FlagPromptEmphasisTest.mathPrompt_staysFullyBold` (`androidApp/.../FlagPromptEmphasisTest.kt:135-168`) currently asserts `spanStyles.isEmpty()` for math — that regression must be inverted.

**Steps**:
1. Extend the private `Rendered` data class in `MathCurriculum.kt` with `val emphasis: String? = null`.
2. In each expression `render` branch listed above, pass `emphasis = "..."` matching the exact substring already embedded in `prompt` (same spacing as the template, e.g. `"$a + $b"`, `"$a - $b"`, `"$a + $a"`, `"$a + ? = 10"`). Leave CLOCK / MONEY / WORD_* without `emphasis` (default null).
3. In `buildMathProblems` when constructing `MathProblem`, set `promptEmphasisSv = rendered.emphasis`.
4. Do **not** change `MathActivityScreen`, `PromptEmphasis.kt`, or `Models.kt` — the 040 path already handles non-null emphasis.
5. Update tests (see below). Do not edit seed JSON; live math problems come from `MathCurriculum`, not embedded `promptEmphasisSv` in resources.

**Tests / verification**:
- commonTest — extend `composeApp/src/commonTest/kotlin/se/lixo/app/geography/PromptEmphasisTest.kt` (or add a small math curriculum assertion elsewhere if preferred):
  - `promptWithEmphasis("Vad blir 5 + 3?", "5 + 3")` → one ExtraBold span covering exactly the `"5 + 3"` range; surrounding text Normal.
  - Keep existing null/not-found cases.
- commonTest / androidHostTest — assert `MathCurriculum.buildMathProblems` for a known early addition level (e.g. stage that uses `ADD_SMALL`) sets `promptEmphasisSv` equal to the `"N + M"` / `"N - M"` substring of `promptSv` (reuse the regex already in `LixoEnginesTest.kt:1656` if handy: `Vad blir (\d+) ([+-]) (\d+)\?` → emphasis `"$1 $2 $3"`). For a CLOCK (or WORD) problem, `promptEmphasisSv` stays null.
- Instrumented — update `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FlagPromptEmphasisTest.kt`:
  - Rename / replace `mathPrompt_staysFullyBold` so it asserts Normal + ExtraBold only on the expression (reuse `assertBoldRangeOnly` with the problem's `promptEmphasisSv`), still on a real MATH level via `MathActivityScreen`.
  - Keep the flag case unchanged.
  - Capture `math_prompt.png` as evidence of the bold/normal split.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.FlagPromptEmphasisTest`

**Open questions**: none.
