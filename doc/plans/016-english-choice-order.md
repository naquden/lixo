# 016 — English course: correct answer is always the first option; randomize choice order

**Type**: bug
**Depends on**: none. It touches `MathActivityScreen.kt`, which 013/014 may also edit, so run it after them in the queue.

## Goal
In the English course ("Engelska Glosresan") the correct translation is always the first answer card, so kids can tap the top card without reading. The answer order should be randomized, so the correct answer can be in any position.

## Context
- English tasks reuse the math quiz UI. `ui/screens/math/MathActivityScreen.kt:105-121` maps each `EnglishGlosaItem` to a `MathProblem` with:
  `choices = listOf(item.swedishTranslation, "sköld", "drake", "stjärna").distinct()`
- **Root cause**: the correct answer is hard-coded at index 0 and never shuffled. The choices render in list order (`MathActivityScreen.kt:342`, `problem.choices.forEachIndexed`).
- More bugs on the same line:
  - The distractors are the same 3 fixed words for every question.
  - When the answer is "stjärna" (item `en_<order>_2`, `Repositories.kt:1106`), `distinct()` drops the duplicate, so that question has only **3** options, with the correct one still first.
- Math problems avoid this with `MathCurriculum.buildChoices` (`domain/content/MathCurriculum.kt:385`). It places the answer at `(order + i) % 4`, which is deterministic but a predictable rotation. Math is out of scope here.
- English items come from the seed (`Repositories.kt:1090-1125`, 3 items per task: hat/star/castle) or from Firestore `tasks` (`englishItems`), so the fix must work for any item list.

## Steps
1. New pure helper `domain/content/EnglishChoices.kt`:
   ```kotlin
   object EnglishChoices {
       val FALLBACK_DISTRACTORS_SV = listOf("sköld", "drake", "katt", "hund", "sol", "boll", "bok", "fisk", "träd", "hus")
       fun build(item: EnglishGlosaItem, taskItems: List<EnglishGlosaItem>, random: Random): List<String>
   }
   ```
   - Distractor candidates: the `swedishTranslation` of the **other** items in the task first (that makes it a real vocabulary test), then `FALLBACK_DISTRACTORS_SV`. Exclude anything equal to the correct answer (case-insensitive, trimmed), dedupe, shuffle with `random`, and take 3. This always gives **4 unique options**.
   - Return `(distractors + correct).shuffled(random)`.
2. `MathActivityScreen`: add param `choiceSeed: Long? = null` (for tests). In the `remember(task)` block, create `val random = Random(choiceSeed ?: Random.nextLong())` once per task session, and use `EnglishChoices.build(item, task.englishItems, random)` instead of the hard-coded list. Because it's inside `remember(task)`, the order stays stable across recompositions and wrong taps, and a new session reshuffles.
3. Add `Modifier.testTag("choice_$index")` on each `ChoiceCard` (`MathActivityScreen.kt:342`) for the instrumented test. Leave the math path unchanged.

## Tests / verification
commonTest (new `EnglishChoicesTest.kt`):
- `build` always returns 4 distinct options containing the correct answer, including for "stjärna" (the old 3-option bug) and for a task with a single item (it falls back to the pool).
- The distractors never equal the correct answer (case/whitespace variants too).
- Other task items' translations are preferred as distractors when available.
- Position distribution: over 400 seeds, the correct answer lands in each of the 4 positions between 15% and 35% of the time, and isn't always index 0.
- Deterministic for a fixed seed.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/EnglishChoiceOrderTest.kt`, `ScreenshotTest` pattern: `createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme { MathActivityScreen(...) }` with the real seeded English level/task from `LixoRepository` and real `MathEngine`):
- `englishQuestion_hasFourOptions_correctNotAlwaysFirst`: with a fixed `choiceSeed` chosen so the first question's answer isn't at index 0, assert that `choice_0..choice_3` exist and that the correct translation's node is not `choice_0`. Tap the correct one → solved state. Capture a screenshot.
- `starQuestion_hasFourOptions`: advance to the "star" item and assert 4 choice nodes.
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.EnglishChoiceOrderTest` (Pixel_10 emulator, per the current run setup; use `R5CXC0681EM` if verifying on the S25).

## Open questions
- Not part of this fix, flagging only: all 20 seeded English levels use the same 3 words (hat/star/castle). The math choices also rotate predictably (`(order + i) % 4`). Both could be separate tasks.
