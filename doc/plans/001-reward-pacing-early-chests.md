# 001 — Reward pacing: no floods, no droughts, generous start

**Type**: bug (balance)

## Goal

Two reported symptoms, same root cause:
- First reading level → 3–4 chests at once (flood).
- First 8 math levels → 2 chests after level 1, then **nothing until level 7** (drought).

Target: **one reward moment per level at the start**, gradually thinning out, never a burst and never a long dry spell. Keep it generous early without inflating value (`doc/project.md` §2 "Belöningskurva: Snabb start med tidiga upplåsningar, sedan gradvis svårare…", "varje belöning får ett eget iscensatt ögonblick").

## Context / root cause

- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/engine/ProgressionAndGiftEngine.kt:155-165`: step 2 unlocks **every** item with `unlockStarRequirement in 1..newTotalStars` in one run, with no cap.
- `ui/components/celebration/CelebrationSequence.kt:42-52`: each item in `newlyUnlockedItems` becomes its own Chest + EquipPrompt step.
- `tools/item_pipeline/generate_wardrobe_items.py` `_star_unlock_thresholds` (~line 246) generates `GeneratedWardrobeCatalog.kt`. Star thresholds come out as:
  - stars 2,2,3,3,4,4,5,5,6: 11 items packed into the first 6 stars
  - **then nothing until 37★**, then clusters of ~9 at 37–41, 73–76, 108–111, 144–145, 179–180, 215, 250.
- Stars are shared across subjects and a level gives 1–3★, so the first one or two levels use up all 11 early items (flood). The next ~10 levels give nothing from stars (drought). The only thing that fills the gap is the Flitkista, at 10 progress runs → that was the chest at math level 7 (reading runs count too).
- More generally: **star-based spacing can't pace the onboarding**, because a 3★ child goes through thresholds 3× faster than a 1★ child. Early pacing has to depend on **levels cleared**.
- Other sources that can stack in a run: level `rewardItemId` (step 1, currently dead, see Open questions), repetition gift (step 4), Flitkista (4b), weekly streak piece (6), avatar unlock (DOG 12★, KNIGHT 30★…, a dialog, not a chest).

## Balance rationale

There's no single industry number for this, but these principles are well established:
- **Early quick wins**: a reward on every one of the first sessions builds the habit (standard onboarding practice in kids' and learning apps).
- **Satiation**: several rewards at once lower the value of each, so at most one main reveal per run.
- **Overjustification effect** (Lepper, Greene & Nisbett 1973, studied in children): heavy, predictable tangible rewards can lower intrinsic motivation, so the rate should **taper** as play becomes the habit, not stay at one per level forever.
- **No dead zones**: the gaps should widen smoothly. A long dry spell right after a generous start feels like a punishment and is a common churn point.

## Target cadence (levels cleared, across all subjects)

| Levels cleared | Source | Item moments |
|---|---|---|
| 1–6 | onboarding track | **1 every level** (6) |
| 7–20 | onboarding track | 1 every 2nd level (7) |
| 21–40 | onboarding track | 1 every 3rd level (~7) |
| always | Flitkista (unchanged, 10 progress runs) | +1 at ~10, 20, 30… |
| from ~30★ | star thresholds (smooth curve) | widening gaps up to 250★ |
| on top | avatars (DOG 12★, KNIGHT 30★…) | separate dialog |
| on top | weekly streak / achievements | own chest, may stack with the above |

Result for the reported case (8 math levels): **8 levels → 6 onboarding items + 1 (level 8) = 7 chests, one per level, plus the DOG avatar around 12★.** Before: 2 + 1. First level → exactly 1 chest.

## Steps

1. **Onboarding track** (new, engine):
   - Add `BalanceConfig.ONBOARDING_REWARD_LEVELS: Set<Int>` = cleared-level counts that award an item: `1..6`, then every 2nd up to 20 (`8,10,…,20`), then every 3rd up to 40 (`23,26,…,38`). Generate it in code with a small builder + KDoc that names the table above.
   - Add a parameter `clearedLevelsCountBeforeRun: Int` to `recordLevelCompletion`. In `ui/LixoApp.kt:185` and `:232`, pass `levelProgressByProfile[activeProfile.id].orEmpty().size`. No schema change.
   - When `!isReplay` and `clearedLevelsCountBeforeRun + 1 in ONBOARDING_REWARD_LEVELS`: roll one item, reusing `rollChest`'s pool and dedup rules (no streak/achievement items), preferring COMMON/UNCOMMON rarity. Add it to `newlyUnlockedItems`.
2. **One chest per source per run** (engine). Different reward sources **may stack** in the same run (e.g. round reward + achievement + Flitkista = 3 chests): each one is a separately earned moment. The flood came from *one* source (star thresholds) dumping many items, so the cap applies **per source**, not globally.
   - Add `enum class RewardSource { LEVEL, STARS, FLITKISTA, GIFT, WEEKLY_STREAK, ACHIEVEMENT }` and `data class UnlockedReward(val item: WardrobeItem, val source: RewardSource)` (domain/model or next to `LevelCompletionOutcome`).
   - `LevelCompletionOutcome.newlyUnlockedItems` becomes `newlyUnlockedRewards: List<UnlockedReward>`. Keep a derived `newlyUnlockedItems` getter if that keeps call sites simple (`LixoApp.kt` unlock loop, screens).
   - Rule: **max 1 item per source per run.** `BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN = 1`.
   - **"Progression" sources share one slot:** LEVEL/onboarding item and STARS count as the same "progression" moment. If the onboarding item fired this run, star unlocks are deferred: they stay locked and unlock on a later run (`<= newTotalStars` stays true, so no new state is needed). This is the anti-flood rule.
   - Star unlock: take the lowest eligible (sort by `unlockStarRequirement`, then id).
   - Order inside `recordLevelCompletion` (also the celebration order): progression (LEVEL/onboarding or STARS) → FLITKISTA → GIFT → WEEKLY_STREAK → ACHIEVEMENT.
   - Worst case per run: progression 1 + Flitkista 1 + gift 1 (replay only, so never together with onboarding) + streak 1 + achievement 1. Typical run: 0–1, milestone runs: 2–3.
   - Achievements: no achievement is awarded at runtime today (no code tracks `Achievement.currentCount`/`isUnlocked` in the engine). Only add the `ACHIEVEMENT` enum value + ordering slot here so a future achievement task plugs in. Don't implement achievement tracking (that's task 004).
   - **Celebration UI** (`ui/components/celebration/CelebrationSequence.kt`): `CelebrationStep.Chest(item, source)`. `ChestRevealStage` shows a short source label above the chest so stacked chests feel distinct, not like spam. Add the strings to `ui/theme/LixoStrings.kt`: LEVEL "Banbelöning", STARS "Stjärnbelöning", FLITKISTA "Flitkistan", GIFT "Present", WEEKLY_STREAK "Veckans streak", ACHIEVEMENT "Prestation". If the existing chest UI has a natural title slot, use that instead of adding a new one.
3. **Re-curve star thresholds** (`generate_wardrobe_items.py`):
   - The onboarding track now covers the start, so star thresholds **start at ~30★** (after ~15 levels) and rise as one global, unique, ascending sequence up to ≤250★, gaps widening smoothly (e.g. `gap_k = round(4 + k^1.15)`, tuned to fit). Interleave slots so consecutive unlocks are different slots, and put rarer items later.
   - Keep the `STAR_UNLOCKABLE_*_CAP` counts. Regenerate `GeneratedWardrobeCatalog.kt` with the script (check its argparse/header). Don't hand-edit.
4. Leave `CHEST_POINTS_*` unchanged. Update the KDocs (class header in the engine, `BalanceConfig`) to describe the three layers: onboarding track → Flitkista → star curve.

## Tests / verification

`composeApp/src/commonTest/kotlin/se/lixo/app/LixoEnginesTest.kt`, using the real `GeneratedWardrobeCatalog`:
- Fresh profile, first level at 3★ → exactly 1 item; `buildCelebrationSteps` has exactly 1 `Chest`.
- **Simulate 8 math levels at 3★** (reproduces the reported case): ≥1 item on every level 1–6, ≥7 in total, never >1 item from progression sources in one run.
- Same simulation at 1★: same per-level guarantee for levels 1–6 (the onboarding track doesn't depend on stars).
- Simulate 40 levels at 2★: no run with >1 item from the same source (LEVEL+STARS counted together); longest streak of levels with 0 items ≤ 3 before level 20 and ≤ 5 before level 40.
- Replays never trigger the onboarding item.
- A profile jumping to 100★ unlocks only 1 star item per run, and the next run unlocks the next one.
- Stacking allowed: a first clear that hits an onboarding slot **and** completes the Flitkista (and the 7th streak day) → 1 LEVEL + 1 FLITKISTA (+ 1 WEEKLY_STREAK) reward, in that order; `buildCelebrationSteps` yields one Chest per reward with the matching `source`.
- Onboarding item + star threshold crossed in the same run → only the LEVEL item; the star item unlocks next run.
- Catalog invariant: star thresholds unique and ascending, the first ≥ 25, the last ≤ 250, gaps non-decreasing (±1 tolerance).
- Update existing tests that assumed batch unlocks or the old thresholds.

Run the full commonTest suite; all tests must pass. In the app: new profile → play math 1–8 → one chest each level 1–6, then level 8; a run where Flitkista also fills shows 2 chests, each with its own label.

## Open questions

- Level `rewardItemId`s in `seed_campaign_content.json` (162 refs, e.g. `item_hat_2`) don't exist in the catalog, so step 1 never fires. → handled in task 002 (runs after this one; it reuses this plan's onboarding schedule and per-source rule).
