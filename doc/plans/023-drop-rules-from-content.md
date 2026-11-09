# 023 — Drop rules come from the content pack

**Type**: feature
**Depends on**: 022

## Goal
Every reward and drop tuning value comes from `content.dropRules` (019 schema) instead of `BalanceConfig` constants and item flags. Odds, pity, chest cost, onboarding levels, star unlocks, level rewards, achievement rewards, the tutorial reward and the weekly set piece can then all be changed from the cloud. The engines receive the rules as parameters, so they stay pure and testable.

## Context
- `domain/engine/ProgressionAndGiftEngine.kt`:
  - `isChestEligible` :119 and `isOnboardingPreferred` :127
  - `rollPresentGift` :516 (subject theme), `rollChest` :545 (pity), `rollOnboardingItem` :572
  - `recordLevelCompletion` :257–511 (onboarding slot :314, curated level item :320, star-threshold unlocks :330–343)
- `domain/model/BalanceConfig.kt`: `TUTORIAL_FIRST_REWARD_ITEM_ID` :11, `WEEKLY_STREAK_DAYS_FOR_SET_REWARD` :93, `CHEST_POINTS_THRESHOLD_HALF`/`PROGRESS_RUN_HALF`/`REPLAY_RUN_HALF` :119–125, `PITY_*` :134–136, `ONBOARDING_REWARD_LEVELS`.
- `Rarity.giftDropWeight` in `Models.kt:49`.
- `AchievementEngine.kt` and `buildSeedAchievements` (plan 014): achievements carry a `rewardItemId`.
- After 022, items have `pool` (`standard|event|achievement|level|setpiece|none`) and `retired`.

## Steps
1. Add a `dropRules: DropRules` parameter (or take it from an `ActiveContent` parameter) to every function in `ProgressionAndGiftEngine` that reads a constant or flag above:
   - `isChestEligible(item)` = `item.pool == STANDARD && !item.retired`
   - `isOnboardingPreferred` = chest-eligible && rarity in COMMON/UNCOMMON && not in `starUnlocks`
   - rarity roll weights come from `dropRules.rarityWeights`
   - chest cost and points come from `chestCostHalfPoints`/`progressRunHalfPoints`/`replayRunHalfPoints`
   - pity comes from `dropRules.pity`
   - onboarding levels come from `dropRules.onboarding.levels`
   - star unlocks: `dropRules.starUnlocks.filter { it.stars <= totalStars && it.itemId !in owned }`
   - the curated level item is `dropRules.levelRewards.firstOrNull { it.level == order }?.itemId`, only when that item exists in the pack (otherwise no curated item)
2. The tutorial reward reads `dropRules.tutorialRewardItemId`; the weekly streak set piece reads `dropRules.weeklyStreakSetPieceItemIds` (keep the 7-day rule in `BalanceConfig`, since it is gameplay rather than loot).
3. `AchievementEngine`/`buildSeedAchievements`: the achievement definitions (ids, ladders, targets) stay app code; `rewardItemId = dropRules.achievementRewards[achievementId]`. The validator (019) has already guaranteed every app achievement id has a valid reward.
4. Remove `Rarity.giftDropWeight` and the moved `BalanceConfig` constants, and update the KDoc to point at `doc/content/content-pack.md`.
5. **Freeze content per run:** the run screen captures `content.active.value` when the run starts and passes that snapshot into `recordLevelCompletion`, so a hot swap mid-run (024) can't change the outcome.
6. `LixoApp.kt`/`LixoRepository`: pass `content.active.value.dropRules` to the engine at the call sites (:219/:277 and the gift and chest paths).

## Tests / verification
commonTest (rules built inline in each test, with `ActiveContent` from the mini fixture):
- `DropRulesChestTest`:
  - weights `{LEGENDARY: 1, others: 0+epsilon}` with a seeded rng → LEGENDARY
  - pity `{RARE:2, EPIC:3, LEGENDARY:4}` triggers at those counts
  - a chest costs exactly `chestCostHalfPoints`
  - retired and event/achievement items never roll
- `LevelRewardStabilityTest`: the level → item map is the same before and after adding 50 standard items to the pack; a level with no entry gives no curated item; an entry pointing at a missing item gives no curated item and no crash.
- `OnboardingFromRulesTest`: custom `onboarding.levels = [1, 4]` → onboarding rewards only on the 1st and 4th clears.
- `AchievementRewardMappingTest`: changing `achievementRewards["ach_weekly_streak_7"]` changes the granted item.
- The existing `LixoEnginesTest` reward and pity tests are ported to rules that match the old `BalanceConfig` values and still pass.

Instrumented: none new. Re-run `se.lixo.app.verify.AchievementLadderTest` and `ItemRevealContentTest` (022) as a regression check:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.AchievementLadderTest,se.lixo.app.verify.ItemRevealContentTest
```

## Open questions
- None.
