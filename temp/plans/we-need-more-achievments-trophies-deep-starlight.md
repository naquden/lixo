# 013 — Achievement ladders: more tiered trophies + picture-first, read-aloud challenge cards

**Type**: feature
**Depends on**: 004 (AchievementEngine), 011/012 (rarity colors). Deliverable of this planning turn (per CLAUDE.md): write this as `doc/plans/013-achievement-ladders.md` and add
`- [ ] 013 — Achievement ladders: tiered trophies + picture-first read-aloud challenge cards (feature, after 004) → [plan](doc/plans/013-achievement-ladders.md)` under `## Pending` in `FEATURES.md`. Don't implement.

## Goal
More achievements, set up like a classic game: each challenge is a ladder. The first tier is easy. When you finish it, the next tier appears with a bigger goal and a rarer reward. The trophy color keeps showing the reward rarity. Kids who are just learning to read should understand the challenge without reading: big action icons, a number, short text, and a button that reads the challenge aloud.

## Context
- Model `Achievement` — `domain/model/Models.kt:405` (`id, titleSv, descriptionSv, rarity, requiresAiMode, targetCount, currentCount, isUnlocked, rewardItemId`). It's `@Serializable` and part of `SeedContentBundle` (`Models.kt:449`), so new fields need defaults.
- Seeds: `buildSeedAchievements()` `data/repository/Repositories.kt:1174`. It has 4 handwritten achievements (`ach_speed_reader_5`, `ach_pronunciation_master_10`, `ach_math_clock_money`, `ach_weekly_streak_7`; their reward items are handmade at `Repositories.kt:829-920`) plus 8 star milestones from `BalanceConfig.STAR_ACHIEVEMENT_MILESTONES` (`BalanceConfig.kt:184`, titles `STAR_ACHIEVEMENT_TITLES_SV` ~`Repositories.kt:1155`).
- Engine: `domain/engine/AchievementEngine.kt`. `AchievementRule` is picked **by id** (`forAchievementId`). `countedLevelIds` and `progress` are keyed by achievement id. `withProfileProgress` overlays profile state for the UI.
- Per-profile state: `ChildProfile.achievementProgress: Map<String,Int>`, `achievementCountedLevelIds: Map<String,Set<String>>`, `unlockedAchievementIds`, `pendingAchievementRewardIds` (`Models.kt:167-170`). They're persisted as JSON columns (`data/local/Entities.kt:27-29`, `ProfileMappers.kt:47-93`), so **new keys need no DB migration**.
- Integration: `ProgressionAndGiftEngine.recordLevelCompletion` (`ProgressionAndGiftEngine.kt:242`). It allows 1 achievement chest per run and queues the rest as pending (`BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN`). `RunFacts` is built in `ui/LixoApp.kt:224` (reading) and `:282` (math).
- Celebration: `ui/components/celebration/CelebrationSequence.kt:31` (`CelebrationStep`, `buildCelebrationSteps`). The ACHIEVEMENT chest label is "Prestation: <title>".
- UI: `ui/screens/social/TrophiesScreen.kt` shows a flat list of text rows (icon is always `Trophy`, title + long description, rarity badge, progress bar `x/y`). It's called at `ui/LixoApp.kt:416`. `ttsEngine` is available in `LixoApp` (`:94`, `TextToSpeechEngine.speak(text, "sv-SE")`, `platform/PlatformContracts.kt:76`).
- Icons: `ui/theme/LixoIcons.kt` has Book, Calculator, Check, Star, Flame, Bolt, Mic, Timer, Speaker, Lock, Trophy, Gift. No coin yet.
- Reward items: `tools/item_pipeline/generate_wardrobe_items.py:488` (`STAR_ACHIEVEMENT_REWARDS`, `_assign_star_achievement_items`) reserves one generated item per star milestone, one slot each. The catalog has plenty of stock: 225 C / 144 U / 99 R / 72 E / 36 L. `GeneratedWardrobeCatalog.kt` is generated, don't hand-edit it.
- Available stats per run: subject, levelId, AI mode, WPM vs reference, pronunciation eligibility, math first-try per type, stars earned this run, total stars, streak days.

## Design

### Ladders (chains)
A tier unlocks when the **chain counter** reaches its target. Tiers share one counter, so "Read 3 stories" counts the first story too. Rarity rises up the ladder. Existing ids stay as tiers, so saved progress and unlocks are kept.

| chain (`AchievementAction`) | icon | counts | tiers: target → rarity |
|---|---|---|---|
| `READ_STORY` | Book | distinct reading levels cleared (subject != MATH) | 1 C · 3 U · 8 R · 20 E · 50 L |
| `SOLVE_MATH` | Calculator | distinct math levels cleared | 1 C · 3 U · 8 R · 20 E · 50 L |
| `COLLECT_STARS` | Star | `totalStarsAfterRun` | **5 C · 10 U** (new) · 20 R · 45 R · 75 R · 120 E · 175 E · 250 E · 350 L · 480 L (existing `ach_stars_*`) |
| `PERFECT_LEVEL` | 3×Star | distinct levels finished with 3★ this run | 1 C · 5 U · 15 R · 40 E · 80 L |
| `FIRST_TRY` | Check | math problems solved first try, any type (sum of `mathFirstTryByType`) | 10 C · 30 U · 75 R · 150 E · 300 L |
| `STREAK_DAY` | Flame | `streakDaysAfterRun` (max, not summed) | 2 C · 3 U · 5 R · **7 L = `ach_weekly_streak_7`** · 14 L · 30 L |
| `CLOCK_MONEY` | Timer (+ new Coin) | first-try CLOCK+MONEY problems | 3 C · 8 U · **15 E = `ach_math_clock_money`** · 40 L |
| `READ_FAST` (AI) | Bolt | distinct AI reading levels at ≥ 3★ WPM | 1 U · 3 R · **5 E = `ach_speed_reader_5`** · 12 L |
| `READ_ALOUD` (AI) | Mic | distinct AI levels with no struggled words | 1 U · 4 R · **10 L = `ach_pronunciation_master_10`** · 20 L |

That's 48 achievements (36 new). New ids: `ach_<chain>_<target>`, e.g. `ach_read_story_3`. Chain counter key in `achievementProgress`/`achievementCountedLevelIds` is `chain:<action>`.

### Kid-friendly card (Trophies screen)
Show **one card per chain**, displaying its current tier (the lowest tier not yet unlocked):
- **Left**: big action icon (56dp) in a circle tinted with the tier's rarity color, plus a small number badge with the target (e.g. "3").
- **Picture equation row** (no reading needed): `[action icon] × 3  →  [Trophy in rarity color]`. The AI chains get a small Mic chip.
- **Short text** (`titleLarge`, max ~4 words, early-reader Swedish): "Läs 3 sagor", "Lös 10 tal rätt direkt", "Spela 3 dagar i rad", "Få 3 stjärnor på 5 banor".
- **Speaker button** (`LixoIcons.Speaker`, 48dp touch target, contentDescription "Läs upp"): calls `ttsEngine.speak(spokenSv)`, e.g. "Läs tre sagor. Då får du en sällsynt skatt!" The rarity name is part of the spoken text.
- **Progress**: if target ≤ 10, a row of pips where each filled pip shows the action icon. Otherwise a bar with a big "12 / 30".
- **Ladder strip**: one small trophy per tier. Done tiers are filled in their rarity color with a check. The current tier is outlined and gently pulsing. Future tiers are faded with a lock, still in their rarity color, so the bigger reward ahead stays visible.
- **Finished chain**: the card shows every trophy filled plus "Klart!" with a Check icon.
- Order: chains with the most progress toward their current tier come first, finished chains go last.
- Header subtitle becomes "Klara utmaningar. Få skatter!"

### "New challenge" moment
After an ACHIEVEMENT chest in the celebration, add a `CelebrationStep.NextChallenge(achievement)` card if the chain has a next tier: "Ny utmaning!", with the same picture equation and a speaker button.

## Steps
1. **Model** (`Models.kt`): add `enum class AchievementAction { READ_STORY, SOLVE_MATH, COLLECT_STARS, PERFECT_LEVEL, FIRST_TRY, STREAK_DAY, CLOCK_MONEY, READ_FAST, READ_ALOUD }`. Add to `Achievement`: `action: AchievementAction? = null`, `tier: Int = 0`, `shortSv: String = ""`, `spokenSv: String = ""`.
2. **Ladder definitions**: new `domain/model/AchievementLadders.kt` with `data class LadderTier(target: Int, rarity: Rarity, legacyId: String? = null)` and `val ACHIEVEMENT_LADDERS: Map<AchievementAction, List<LadderTier>>` matching the table, plus `fun achievementId(action, tier)` (returns `legacyId` when set, otherwise `ach_${action.name.lowercase()}_${target}`). Point `BalanceConfig.STAR_ACHIEVEMENT_MILESTONES` at the COLLECT_STARS targets ≥ 20 (keep the `ach_stars_<N>` ids as legacyIds). Add the new 5/10 star tiers as `ach_stars_5`/`ach_stars_10`, kept off the avatar milestones.
3. **Strings** (`ui/theme/LixoStrings.kt`): per action add `shortSv(target)`, `spokenSv(target, rarity)`, and a chain title (keep the existing titles for legacy tiers; new tiers use simple titles such as "Sagoläsare 1/2/3…"). Short Swedish, numbers as digits in `shortSv` and words not needed in `spokenSv` (TTS reads digits).
4. **Seeds** (`Repositories.kt:1174`): build every achievement from `ACHIEVEMENT_LADDERS`. Legacy tiers keep their current `rewardItemId`/title/rarity. Every other tier looks up `GENERATED_WARDROBE_ITEMS.first { it.unlockAchievementId == id }` (error if missing, same as now). `requiresAiMode = action in {READ_FAST, READ_ALOUD}`.
5. **Item pipeline** (`generate_wardrobe_items.py`): replace `STAR_ACHIEVEMENT_REWARDS` with `ACHIEVEMENT_REWARDS: List[(achievement_id, rarity)]` covering all non-legacy-handmade tiers (8 existing star ids + 36 new). The 4 handmade legacy ids are excluded. `_assign_achievement_items` assigns slots round-robin across `SLOTS` (consecutive rewards in different slots). Picking keeps the same eligibility filter. Keep the existing 8 star assignments stable: process them first with today's slot order. Regenerate `GeneratedWardrobeCatalog.kt`. Update the pytest fixture/tests for the new reserved count. Item ids must not change.
6. **Engine** (`AchievementEngine.kt`):
   - `RunFacts`: add `earnedStarsThisRun: Int = 0` (fill it in `recordLevelCompletion`, same as `totalStarsAfterRun`).
   - Choose the rule by `def.action` instead of id (keep `forAchievementId` only as a fallback for legacy ids without action).
   - First compute **one counter per chain** per run: distinct-level chains (READ_STORY, SOLVE_MATH, PERFECT_LEVEL, READ_FAST, READ_ALOUD) add `levelId` to `countedLevelIds["chain:X"]` when they qualify, and counter = set size. Cumulative chains (FIRST_TRY, CLOCK_MONEY) add to `progress["chain:X"]`. Max chains (COLLECT_STARS, STREAK_DAY) take the current value, and the stored counter never decreases.
   - **Legacy carry-over**: if `chain:X` is missing, seed it from the legacy id's state. Use the union of `countedLevelIds[legacyId]` for READ_FAST/READ_ALOUD, and `progress[legacyId]` for CLOCK_MONEY.
   - Then for each tier not yet unlocked: `progress[def.id] = min(counter, target)` and unlock when counter ≥ target. Several tiers can unlock in one run; the existing pending queue in `recordLevelCompletion` hands them out at 1 per run.
   - `withProfileProgress`: unchanged contract (it uses `progress[def.id]`).
   - Add `fun currentTiers(defs, profile): List<Achievement>`: per chain, the lowest unlocked=false tier, or the top tier if the chain is done. Also add `fun nextTier(def, defs)`.
7. **Celebration**: add `CelebrationStep.NextChallenge(next: Achievement)` after the EquipPrompt of an ACHIEVEMENT reward, when `nextTier` exists (pass the definitions into `buildCelebrationSteps` / the `LevelCompletionOutcome`; simplest is adding `nextChallenges: Map<String, Achievement>` to the outcome in `recordLevelCompletion`). Render it as a dialog that reuses the new `ChallengeCard` composable. Pass `ttsEngine` into `CelebrationSequenceHost` (with a default no-op for tests).
8. **Icons** (`LixoIcons.kt`): add `Coin` (circle path) and `StarTriple` (three small stars). Map them in `fun AchievementAction.icon(): ImageVector`.
9. **UI** (`TrophiesScreen.kt`): change the signature to `TrophiesScreen(achievements: List<Achievement>, ttsEngine: TextToSpeechEngine)`. Group achievements by `action` and render one `ChallengeCard` per chain as described in the Design section. Use `LixoColors.rarityColor`, `LixoStrings.rarityName`, `LixoProgressBar`, `LixoCard`, `LixoIcon`. Add a semantics test tag `challenge_<action>` per card and `ladder_tier_<id>` per trophy pip. Update `LixoApp.kt:416` to pass `ttsEngine`.
10. Update the KDoc on `Achievement`/`BalanceConfig.STAR_ACHIEVEMENT_MILESTONES` to point at plan 013.

## Tests / verification
commonTest (`AchievementEngineTest.kt`, `LixoEnginesTest.kt`, `CelebrationStepsTest.kt`):
- Ladders: targets are strictly ascending per chain. Rarity is non-decreasing per chain. Every id is unique. Each legacy id appears exactly once with its old target/rarity. No star tier equals an `AvatarType.unlockStarsRequired`.
- Seed/catalog: every non-handmade tier has exactly one catalog item with matching `unlockAchievementId` and the ladder rarity, and all 48 `rewardItemId`s exist.
- READ_STORY: the first reading run unlocks tier 1. The same level twice counts once. Tier 2 unlocks at the 3rd distinct level (the counter is shared, tier 1's level counts). Math runs don't count.
- PERFECT_LEVEL counts only 3★ runs. A 2★ run followed by a 3★ replay of the same level counts once.
- FIRST_TRY: a session with 4 first-try (mixed types) adds 4.
- STREAK_DAY: streak 3 unlocks tiers 2 and 3. If the streak later breaks, the counter doesn't drop.
- Legacy carry-over: a profile with `achievementCountedLevelIds["ach_speed_reader_5"] = 4 levels` gets READ_FAST tiers 1 and 3 unlocked on its next qualifying run, and tier 5 (`ach_speed_reader_5`) on the 5th distinct level. A profile that already has `ach_math_clock_money` unlocked never re-triggers it.
- Multi-unlock: a fresh profile's first 3★ reading run unlocks READ_STORY 1 + PERFECT 1 → 1 chest now, 1 pending next run.
- `currentTiers`: returns the lowest locked tier per chain, or the top tier when the chain is done.
- `buildCelebrationSteps`: an ACHIEVEMENT chest followed by NextChallenge when a next tier exists, and none after a final tier.
- pytest for the item pipeline: reserved count = 44, slots round-robin, ids unchanged vs fixture. Run the full commonTest suite.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/AchievementLadderTest.kt`, using the `AchievementTrackingTest` pattern, real `LixoRepository` seeds + `AchievementEngine`, `LixoTheme`):
- `freshProfile_showsOneCardPerChainAtTier1`: 9 `challenge_*` nodes; "Läs 1 saga" exists; a `ladder_tier_*` exists for future tiers; a "Läs upp" content description exists. Capture a screenshot.
- `afterTier1_nextChallengeAppears`: run `recordLevelCompletion` for one reading level → the card now shows "Läs 3 sagor" and tier 1 is filled. Capture a screenshot.
- `speakerButton_speaksInstruction`: a fake `TextToSpeechEngine` records the `speak()` text after `performClick` on "Läs upp" and contains "sagor".
- `celebration_showsNextChallenge`: `CelebrationSequenceHost` with an ACHIEVEMENT outcome → "Ny utmaning!" exists after advancing.
- Update `AchievementTrackingTest` for the new `TrophiesScreen` signature.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.AchievementLadderTest,se.lixo.app.verify.AchievementTrackingTest`

## Open questions
- None blocking. Notes: no backfill for READ_STORY/SOLVE_MATH/PERFECT from old level_progress (they count from now on; stars and streak read current values). Reserving 36 more items removes them from chest pools (9 of them LEGENDARY). The pity logic already handles exhausted tiers (012).
