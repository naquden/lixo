# 035 — Math answer options show only the option text (no 1, 2, 3, 4 prefix)

- **Type**: bug
- **Goal**: The multiple-choice answers in math show a badge `1`, `2`, `3`, `4` in front of each option.
  A child can confuse that badge with the answer value itself (e.g. badge `2` next to answer `4`).
  Remove the prefix entirely: each option shows only its text, no number, letter or other badge.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/MathActivityScreen.kt:351-388`:
  answer choices rendered with `problem.choices.forEachIndexed { index, choice -> ChoiceCard(…,
  badgeNumber = "${index + 1}", …) }` (line 362).
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoComponents.kt:361-410`: `ChoiceCard`
  draws `badgeNumber` in a 28dp rounded box plus a 12dp spacer before the choice text, only when
  `badgeNumber != null`. `MathActivityScreen` is its only caller.
- `MathActivityScreen` also runs English tasks (see `androidApp/src/androidTest/kotlin/se/lixo/app/verify/EnglishChoiceOrderTest.kt`),
  so English options lose the badge too. That's intended.
- Test tags `MATH_CHOICE_TAG_PREFIX + index` (`"choice_0"`…) stay unchanged, since tests use them.
- Hint lines (`"${idx + 1}. $hintText"`, line 339) are a numbered list, not answer badges. Out of
  scope, don't touch.

## Steps

1. In `MathActivityScreen.kt:362`, delete the `badgeNumber = "${index + 1}",` argument.
2. In `LixoComponents.kt`, remove the now-unused `badgeNumber` parameter from `ChoiceCard` and the
   `if (badgeNumber != null) { Box…; Spacer… }` block. Keep the choice `Text` and its layout
   (alignment stays `CenterStart`, same padding) unchanged.
3. `grep -rn badgeNumber` must return nothing afterwards (common, android and iOS source sets).

## Tests / verification

- Instrumented `androidApp/src/androidTest/kotlin/se/lixo/app/verify/MathChoiceNoPrefixTest.kt`
  (follow the `EnglishChoiceOrderTest` setup: `createComposeRule()`, `AndroidPlatformContext.init`,
  `LixoTheme { MathActivityScreen(…, mathEngine = remember { MathEngine() }, …) }` with a seeded
  math level/task):
  - for each `choice_<i>` node, the unmerged child texts (`useUnmergedTree = true`) are exactly
    `[problem.choices[i]]`. No extra text node (no `1`…`4`, no letter);
  - capture `onRoot().captureToImage()` to `temp/verify/035/math_choices.png` as evidence.
- Re-run `EnglishChoiceOrderTest` (shares the screen) to confirm it still passes.
- No commonTest needed: this is a pure UI removal.

## Open questions

(none)
