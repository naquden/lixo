# 004 — Track achievements and unlock their chest rewards

**Type**: feature

**Depends on**: 001 (uses `RewardSource.ACHIEVEMENT`, `UnlockedReward`, the per-source rule, and the celebration source label).

## Goal

Achievements should actually progress and unlock, plus a new series of star-collection achievements. When one completes, the child gets its reward item as a chest, labeled "Prestation", which can stack with other sources in the same run (per 001). The Trophies screen shows real per-profile progress.

## Context

- Model: `domain/model/Models.kt:368` `Achievement(id, titleSv, descriptionSv, rarity, requiresAiMode, targetCount, currentCount, isUnlocked, rewardItemId)`.
- Seed: `data/repository/Repositories.kt:972` `buildSeedAchievements()` has **hard-coded demo progress** (`currentCount` 2/4/6/1). They're held in a single global `_achievements` StateFlow (`Repositories.kt:108`), not per profile, and **nothing updates them**.
- Reward items exist and are achievement-gated (`unlockAchievementId`, `Repositories.kt:672-745`). The engine excludes them from star/chest/gift pools, so today they're unobtainable.
- UI: `ui/screens/social/TrophiesScreen.kt:64` shows `currentCount / targetCount`, passed from `ui/LixoApp.kt:373`.
- Persistence pattern: the repository keeps state in in-memory `MutableStateFlow`s and updates the profile via `updateProfile` (`Repositories.kt:221`). Per-profile data lives on `ChildProfile` (`Models.kt`) / `ProfileEntity` (`data/local/Entities.kt`). Follow the same pattern; don't wire up Room DAOs as part of this task.
- Data available per run:
  - Reading: `ReadingScoreResult` (`domain/engine/ForcedAlignmentEngine.kt:59`) has `achievedWpm`, `referenceWpm`, `eligibleForPronunciationAchievement` (AI mode + no struggled words), and `eligibleForGift` (= AI mode).
  - Math: `MathSessionScoreResult` (`domain/engine/MathEngine.kt:20`) only has totals. Per-problem data (`problem.type`, `wrongAttempts`, `hintsRevealed`) is available in `ui/screens/math/MathActivityScreen.kt:351-366` but isn't aggregated.
  - Streak: `recordLevelCompletion` computes `nextStreakDays`/`didIncrementStreak` (`ProgressionAndGiftEngine.kt:~230`).

## Achievement rules (from the descriptions)

| id | Counts when | Target | Counting |
|---|---|---|---|
| `ach_speed_reader_5` "Blixtläsaren" | Reading run in AI mode with `achievedWpm >= referenceWpm * READING_THREE_STAR_WPM_RATIO` | 5 | **distinct levels** (no farming one story) |
| `ach_pronunciation_master_10` "Kristallklart Uttal" | Reading run with `eligibleForPronunciationAchievement` | 10 | distinct levels |
| `ach_math_clock_money` "Tid- & Pengamästaren" | Each CLOCK or MONEY problem solved with `wrongAttempts == 0 && hintsRevealed == 0` | 15 | cumulative problems |
| `ach_weekly_streak_7` "Veckans Hjälte" | Streak reaches 7 days (`nextStreakDays >= 7` on an incrementing run) | 7 | progress = `min(currentStreakDays, 7)`, unlocks once |
| `ach_stars_<N>` (8 new, see below) | `profile.totalStars` after the run (net new stars, so already anti-farm) | N | progress = `min(totalStars, N)` |

**Distinct stories are required** for both reading achievements (confirmed by the user): replaying the same story never adds progress.

### Star-collection achievements (new)

The campaign max is 160 levels × 3★ = 480★. The milestones are spaced out on a widening curve and kept **off** the avatar milestones (DOG 12, KNIGHT 30, PRINCESS 55, WIZARD 90), so the big moments don't land in the same run:

| id | titleSv | Stars | Reward rarity |
|---|---|---|---|
| `ach_stars_20` | Stjärnfrö | 20 | RARE |
| `ach_stars_45` | Stjärnplockare | 45 | RARE |
| `ach_stars_75` | Stjärnsamlare | 75 | RARE |
| `ach_stars_120` | Stjärnjägare | 120 | EPIC |
| `ach_stars_175` | Stjärnväktare | 175 | EPIC |
| `ach_stars_250` | Stjärnkapten | 250 | EPIC |
| `ach_stars_350` | Stjärnmästare | 350 | LEGENDARY |
| `ach_stars_480` | Stjärnlegend (alla stjärnor) | 480 | LEGENDARY |

Description pattern: "Samla N stjärnor i kampanjen." `requiresAiMode = false`. Define the milestone list once in `BalanceConfig.STAR_ACHIEVEMENT_MILESTONES` and build the 8 `Achievement`s from it (titles in `LixoStrings` or alongside, following the existing seed style).

- These are separate from 001's star-threshold item unlocks (`RewardSource.STARS`). Achievements are visible goals on the Trophies screen. If both land in the same run, they stack as different sources (STARS + ACHIEVEMENT), which is allowed.
- **Existing profiles** already above some milestones unlock them at the next run. The "1 achievement chest per run + pending" rule (step 4) hands them out one per run instead of all at once.
- **Reward items**: `tools/item_pipeline/generate_wardrobe_items.py` reserves 8 generated items (rarity per the table, different slots, not star-unlockable) and emits `unlockAchievementId = "ach_stars_<N>"` for them. Regenerate `GeneratedWardrobeCatalog.kt`. Don't hand-edit. The engine already excludes `unlockAchievementId != null` items from the star/chest/gift pools. If 001 has changed the generator, build on top of its version.

Progress only counts from now on. There's no backfill (no history is stored), except that the streak achievement reads the current streak.

## Steps

1. **Model / persistence**:
   - `ChildProfile` + `ProfileEntity`: add `achievementProgress: Map<String, Int> = emptyMap()`, `achievementCountedLevelIds: Map<String, Set<String>> = emptyMap()` (for the distinct-level rules), and `unlockedAchievementIds: Set<String> = emptySet()`. Update any entity ↔ model mappers and the Room schema/migration if `ProfileEntity` is used by `LixoMigrations.kt` (bump the DB version + add a migration with defaults). If Firebase profile sync (`data/remote/FirebaseSyncService.kt`) serializes the profile, include the new fields with defaults.
   - `buildSeedAchievements()`: set every `currentCount = 0`, `isUnlocked = false` (remove the demo values). These become static definitions.
   - Add a `ruleId`/rule mapping: keep it in code via an `AchievementRule` sealed type keyed by achievement id. No new content fields.
2. **Math per-type stats**: add `firstTryCorrectByType: Map<MathProblemType, Int>` to `MathSessionScoreResult`. Fill it where the session score is built (`MathEngine` scoring function / `MathActivityScreen` attempt states: a problem counts if it's solved and `wrongAttempts == 0 && hintsRevealed == 0`).
3. **`AchievementEngine`** (new, pure, `domain/engine/AchievementEngine.kt`):
   - Input: `RunFacts` (subject, levelId, isAiMode, achievedWpm, referenceWpm, pronunciationEligible, mathFirstTryByType, streakDaysAfterRun), the current profile achievement state, and the definitions.
   - Output: the updated state + `newlyUnlocked: List<Achievement>`. Skip already-unlocked achievements, cap progress at `targetCount`, and respect `requiresAiMode`.
4. **Engine integration**: call `AchievementEngine` from `ProgressionAndGiftEngine.recordLevelCompletion` (pass `RunFacts` via new parameters, built in `ui/LixoApp.kt:185` reading / `:232` math). For each newly unlocked achievement, add its `rewardItemId` item as `UnlockedReward(item, RewardSource.ACHIEVEMENT)`, unless it's already owned. At most 1 achievement chest per run (001's per-source rule); any further unlocks in the same run stay unlocked, and their items are awarded on the next run (keep a `pendingAchievementRewardIds` set on the profile). Add `newlyUnlockedAchievements` to `LevelCompletionOutcome`. Keep the weekly streak set piece (step 6) as its own `WEEKLY_STREAK` source; it may stack with the streak achievement on day 7.
5. **Celebration**: the Chest step for `ACHIEVEMENT` shows the label "Prestation" plus the achievement `titleSv` (e.g. "Prestation: Blixtläsaren"). Use the label mechanism from 001.
6. **Trophies screen**: `LixoApp.kt:373` passes the definitions merged with the active profile's `achievementProgress` / `unlockedAchievementIds` (a `currentCount`/`isUnlocked` overlay). `TrophiesScreen` itself needs no logic change.

## Tests / verification

commonTest:
- `AchievementEngine`:
  - The speed reader counts only AI runs at ≥ the 3★ reference WPM. The same level twice counts once. It unlocks on the 5th distinct level.
  - Pronunciation: manual-mode / struggled runs don't count. It unlocks at 10 distinct levels.
  - Clock/money: a session with 3 first-try CLOCK + 2 first-try MONEY + 1 hinted CLOCK adds 5. It unlocks on crossing 15, and progress is capped at 15.
  - Streak: unlocks when streak reaches 7, once only.
- Star achievements: 19★ → none; a run reaching 20★ → `ach_stars_20`. A profile at 100★ with nothing unlocked → `ach_stars_20` this run, `ach_stars_45` next, `ach_stars_75` after (1 chest per run, the rest pending). Replays with no net new stars add nothing.
- Every `ach_stars_*` has exactly one catalog item with matching `unlockAchievementId` and the rarity from the table. Milestones are strictly ascending, ≤ 480, and none equals an `AvatarType.unlockStarsRequired`.
  - An already-unlocked achievement never re-triggers. `requiresAiMode` is enforced.
- `recordLevelCompletion`: an achievement unlock yields an `UnlockedReward` with `ACHIEVEMENT` and the correct item. It stacks with FLITKISTA in the same run. Two achievements in one run give 1 chest now and 1 on the next run.
- Seed: all `currentCount == 0`; every `rewardItemId` exists in the catalog and has a matching `unlockAchievementId`.
- `MathSessionScoreResult.firstTryCorrectByType` is computed correctly for a mixed session.
- Run the full commonTest suite.
- In the app: a new profile's Trophies screen shows 0/N for all. Solve clock/money problems first-try and the progress rises. On completion, a chest labeled "Prestation: …" appears.

## Open questions

- None. Distinct stories for the reading achievements are confirmed.
