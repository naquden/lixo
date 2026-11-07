# 003 — Flitkista: holo chest meter instead of points text

**Type**: feature

**Depends on**: none (compatible with 001's `RewardSource.FLITKISTA` chest label; if 001 has already run, reuse its types).

## Goal

Replace the "Flitkista: 4,5 / 10 p" text with a **holographic chest icon in the upper right corner** that gradually fills up as the child earns Flitkista points. When it reaches 10 p, the chest reward unlocks. The child never sees any numbers, only how full the chest is.

## Context

- Points live in `ChildProfile.chestProgressHalfPoints` (`domain/model/Models.kt:160`, `data/local/Entities.kt:26`). The threshold is `BalanceConfig.CHEST_POINTS_THRESHOLD_HALF = 20`. The engine keeps the overflow after a chest is awarded (`ProgressionAndGiftEngine.kt:197-200`). `LevelCompletionOutcome.chestPointsAwardedHalf` holds this run's gain.
- Current number displays (both to be removed):
  - `ui/screens/reading/ReadingActivityScreen.kt:617-623`, inside `LevelCompleteCard` `extraContent`
  - `ui/screens/math/MathActivityScreen.kt:418-424`, same pattern
  - string builder `ui/theme/LixoStrings.kt:208` `chestProgress(halfPoints)`
- Map HUD top bar: `ui/screens/map/CampaignMapScreen.kt:151-195`. Profile pill on the left; right `Row` with `StatPill` stars + streak. **Upper right corner = the end of that right Row.**
- Existing chest drawing: `ui/components/celebration/RewardChest.kt` (`RewardChest`, Canvas-drawn body/lid/lock, glow/breathe transitions). Reuse its shape geometry so the meter and the reward chest look like the same chest.
- `LevelCompleteCard` (`ui/components/celebration/LevelCompleteCard.kt:29`) has no corner slot today.

## Design

- **Holo look**: a chest silhouette drawn on Canvas (same proportions as `RewardChest`). It has a translucent body (cyan/violet/pink gradient at ~35% alpha), a thin bright outline, and a slow diagonal shimmer sweep (infinite transition, ~2.5 s). Empty = only the holo outline and glass.
- **Fill**: a gold liquid/light fill rises from the bottom, clipped to the chest body path. Fill fraction = `chestProgressHalfPoints / CHEST_POINTS_THRESHOLD_HALF`. The fill has a gentle wave on top and its glow grows stronger as it gets fuller. At ≥ 80%, add a subtle pulse ("almost full").
- **Size**: ~32 dp in the HUD (same height as `StatPill`), ~44 dp in the level-complete card corner.
- **No numbers anywhere.** Accessibility `contentDescription` uses words only: "Flitkistan: tom / lite fylld / halvfull / nästan full" (thresholds 0, <40%, <80%, ≥80%).
- **Tap** (HUD only): a small tooltip/dialog: "Spela banor för att fylla Flitkistan. När den är full får du en present!" Dismiss on tap or back.

## Steps

1. **Pure fill model** (`ui/components/celebration/FlitChestMeterModel.kt` or `domain/engine` if it fits better):
   - `fun chestFillFraction(halfPoints: Int): Float` = `(halfPoints.coerceIn(0, T)) / T`.
   - `data class ChestFillAnimation(val from: Float, val to: Float, val wrapped: Boolean)` and `fun chestFillAnimation(updatedHalfPoints: Int, awardedHalf: Int, chestAwarded: Boolean): ChestFillAnimation`. Previous = `updated - awarded`, plus `T` if `chestAwarded`. `wrapped = chestAwarded`.
   - `fun chestFillLevelDescription(fraction): String`, the a11y words above. Add the strings to `LixoStrings`.
2. **Composable** `FlitChestMeter(halfPoints: Int, modifier, size: Dp, animation: ChestFillAnimation? = null, onClick: (() -> Unit)? = null)` in `ui/components/celebration/FlitChestMeter.kt`:
   - Static mode: shows the current fill, with shimmer always on.
   - Animated mode: `Animatable` fills `from → to` (~900 ms, ease-out). If `wrapped`: fill `from → 1.0`, flash/burst glow + `haptics` light tick, drain to 0, then fill to `to` (the overflow).
   - Extract the chest path geometry from `RewardChest.kt` into a shared internal helper so both use the same shape. Don't change how `RewardChest` looks.
3. **Map HUD** (`CampaignMapScreen.kt`, right Row ~line 181): add `FlitChestMeter(profile.chestProgressHalfPoints, size = 32.dp, onClick = showTooltip)` as the **last** item (top-right corner), after the stars and streak pills.
4. **Level-complete card**:
   - Add an optional `cornerContent: (@Composable BoxScope.() -> Unit)? = null` to `LevelCompleteCard`. Wrap the card content in a `Box` and align `cornerContent` `TopEnd` with ~8 dp inset.
   - In `ReadingActivityScreen.kt` and `MathActivityScreen.kt`, pass `FlitChestMeter(updatedHalf, 44.dp, animation = chestFillAnimation(...))`. Start the animation after the Stars step, so it plays right before a Flitkista chest reveal if one was awarded (simplest approach: start after a delay matching `STAR_POP_START_DELAY_MS + stars*STAR_POP_STAGGER_MS`, or key it off the celebration step index if that's exposed).
   - **Remove** the `LixoStrings.chestProgress(...)` text in both screens. Keep `LixoStrings.CHEST_OPENED` when a chest was opened (it has no numbers). Delete `chestProgress()` from `LixoStrings` once it's unused.
5. Manual / non-gift-eligible runs (0 points): the meter shows statically with no animation.

## Tests / verification

- Unit tests (commonTest) for the model:
  - `chestFillFraction`: 0 → 0f, 10 → 0.5f, 20 → 1f, values > T clamp to 1f.
  - `chestFillAnimation`: updated 6, awarded 2, no chest → from 0.2, to 0.3, not wrapped. Updated 1, awarded 2, chest → from 0.95, to 0.05, wrapped.
  - Description buckets at 0, 0.3, 0.6, 0.9.
- `grep` check: no `chestProgress(` and no `" p"` points text left in the reading/math completion UI.
- Existing `CelebrationStepsTest` still passes. Run the full commonTest suite.
- In the app (screenshot verification): the map HUD shows the holo chest at the top-right, partly filled for a profile with points. Finish a level → the card's corner chest animates its fill and no points text is shown. On the 10th progress run, the meter fills to full, flashes, resets with the overflow, and the Flitkista reveal follows.

## Open questions

- None blocking. Exact holo palette and shimmer speed are up to the implementer within the design above.
