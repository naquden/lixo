# 012 — Rarity follow-ups from the 011 review (4 LOW findings)

**Type**: bug

**Goal**: Fix the four LOW findings from the 011 review:
1. The pity floor gets stuck once a tier is fully owned.
2. The chest-teaser animations run for nothing.
3. The "LEGENDARISK" label wraps on wardrobe cards.
4. The `WardrobeScreen` KDoc is in the wrong place.

**Context**
- **Pity floor.** `ProgressionAndGiftEngine.rollChest` (`domain/engine/ProgressionAndGiftEngine.kt:471-482`) computes `floor = chestMinRarity(...)` and calls `rollWeighted(..., preferUnowned = true, minRarity = floor)`.
  - `rollWeighted` (`:518-530`) limits step 1 to rarities `>= minRarity`. With preferUnowned, it then falls back to the nearest unowned tier, lower before higher.
  - `advanceChestPity` (`:156-160`, called at `:338`) resets counters based on `rolledItem.rarity`.
  - Failure: every chest-eligible LEGENDARY is owned and `chestsSinceLegendary >= 19`. The floor is LEGENDARY, the fallback gives an EPIC, and `chestsSinceLegendary` keeps growing. Every following chest stays floored and gives the highest tier that still has unowned items, so the odds break.
- **Chest teaser.** `RewardChest.kt:161-190` starts the `chestTeaserPulse`, `chestTeaserSparkle` and `chestTeaserShake` infinite transitions for every rarity. `shake.value` is read inside `graphicsLayer` (`:250-256`) even when `spec.shakeAmplitudeDp == 0f` (COMMON–EPIC, `:94-127`), and pulse/sparkles run even when `glowAlpha == 0f` / `sparkleCount == 0` (COMMON). That costs one layer and draw invalidation per frame for no visible effect. Spec: `ChestTeaserSpec` / `chestTeaserSpecFor` (`:70-140`).
- **Wardrobe label.** `WardrobeScreen.kt:452-464`: the rarity badge `Text(LixoStrings.rarityName(item.rarity).uppercase())` has no `maxLines`/`softWrap`, so "LEGENDARISK" breaks into "LEGENDARIS / K" on 2-column cards (see `temp/verify011/wardrobe_rarity_counts.png`).
- **KDoc.** `WardrobeScreen.kt:58-65`: the "Duolingo-styled 3D Wardrobe Screen" KDoc now sits above `RarityCollectionCount` (`:68`) instead of `fun WardrobeScreen` (`:124`).

**Steps**
1. **Pity floor** (`ProgressionAndGiftEngine.kt`):
   - Add `private fun highestTierWithUnowned(pool: List<WardrobeItem>, unlockedItemIds: Set<String>): Rarity?`, which returns the highest rarity that has at least one unowned chest-eligible item.
   - In `rollChest`, cap the floor at that tier: `val floor = chestMinRarity(...).let { f -> highestTierWithUnowned(...)?.let { minOf(f, it) } ?: Rarity.COMMON }`. A floor never points at a fully owned tier.
   - Change `advanceChestPity(profile, rolled, exhaustedTiers: Set<Rarity> = emptySet())`. A counter is also reset to 0 when its tier and every higher tier are in `exhaustedTiers`, i.e. have no unowned chest-eligible items after this chest. For example, the legendary counter resets when all legendaries are owned, and the epic+ counter resets when all epic and legendary items are owned.
   - Compute `exhaustedTiers` at the call site (`:338`) from the post-unlock `workingUnlockedIds`.
   - Update the KDoc on `rollChest` / `advanceChestPity`.
2. **Chest teaser** (`RewardChest.kt:161-190, 250-256`):
   - Create the pulse transition only when `spec.glowAlpha > 0f || spec.ringAlpha > 0f`, the sparkle transition only when `spec.sparkleCount > 0`, and the shake transition only when `spec.shakeAmplitudeDp > 0f`. Otherwise use a constant `remember { mutableFloatStateOf(0f) }`, the same pattern as `WardrobeItemPreview` in `Avatar3DController.kt:196-215`.
   - In `graphicsLayer`, read `shake.value` only `if (spec.shakeAmplitudeDp > 0f)`.
   - The teaser transitions only run in the WAITING state. Keep today's behaviour for when OPENING starts.
3. **Wardrobe label** (`WardrobeScreen.kt:458-463`): add `maxLines = 1` and `softWrap = false` to the badge Text. If the text still doesn't fit, shrink the font with `style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp)` for LEGENDARY only, or use a `BasicText` with `autoSize` if the Compose version supports it (check `libs.versions.toml`). The goal is that "LEGENDARISK" stays on one line on the narrowest card.
4. **KDoc** (`WardrobeScreen.kt:58-65`): move the "Duolingo-styled 3D Wardrobe Screen" block so it sits directly above `@Composable fun WardrobeScreen` (`:123-124`). `RarityCollectionCount` keeps its own one-line KDoc.

**Tests / verification**
- commonTest (`LixoEnginesTest.kt`):
  - `chestPity_floorCappedWhenTierExhausted`: all eligible LEGENDARY owned, `chestsSinceLegendary = 19`. Run 50 chests and the observed rarity distribution must not be stuck at the highest available tier. Concretely, at least one COMMON or UNCOMMON must appear within 10 chests, using a seeded `Random`.
  - `advanceChestPity_resetsExhaustedTierCounters`: with `exhaustedTiers = {LEGENDARY}` and rolled EPIC, `chestsSinceLegendary == 0` and `chestsSinceEpicPlus == 0`.
  - The existing `chestPity_*` tests must keep passing.
- commonTest (`ConfettiPhysicsTest.kt`, where `chestTeaserSpec_escalatesWithRarity` lives): `chestTeaserSpec_commonHasNoAnimatedEffects`: COMMON has `glowAlpha == 0f`, `ringAlpha == 0f`, `sparkleCount == 0` and `shakeAmplitudeDp == 0f`. This guards the precondition for skipping the transitions.
- Instrumented:
  - Extend `se.lixo.app.verify.WardrobeRarityCountTest`: with a LEGENDARY unlocked, assert that the "LEGENDARISK" node exists and that its height equals the height of the "VANLIG" badge node (one line), using `onNodeWithText(...).fetchSemanticsNode().size.height`. Take a screenshot `012_wardrobe_legendary_label.png`.
  - Rerun `se.lixo.app.verify.RarityRevealTest`, which must still pass with the same screenshots.
  - Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.WardrobeRarityCountTest,se.lixo.app.verify.RarityRevealTest`
  - Unit tests: `./gradlew :composeApp:testAndroidHostTest`.

**Open questions**: none.
