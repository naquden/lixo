# Run all pending FEATURES.md tasks (001–004)

## Context

`FEATURES.md` has 4 pending tasks, each with a full plan in `doc/plans/`. Together they rework the reward system:
- **001**: reward pacing (no chest floods or droughts, onboarding track, one chest per source per run, re-curved star thresholds)
- **002**: per-level reward item ids are invalid on synced devices; the app owns the ids and uses them as the onboarding reward
- **003**: holo Flitkista chest meter in the top-right corner replaces the points text
- **004**: real per-profile achievement tracking, plus 8 star-collection achievements with chest rewards

Goal: implement all four in one batch run, with each task building and testing green before the next one starts.

## Execution

Use the existing `~/.claude/workflows/model-split-implement.js` (it runs tasks **sequentially**, one at a time: opus analyze → sonnet implement → haiku build/test → haiku verify by screenshot → opus diagnose/review). Models are fixed by the script, per the global CLAUDE.md.

Args, in dependency order:
```
{ tasks: [
  "Implement FEATURES.md task 001 exactly per doc/plans/001-reward-pacing-early-chests.md",
  "Implement FEATURES.md task 002 per doc/plans/002-level-reward-item-ids.md (builds on 001's RewardSource/onboarding schedule)",
  "Implement FEATURES.md task 004 per doc/plans/004-achievement-tracking.md (builds on 001; reuse 001's generator changes)",
  "Implement FEATURES.md task 003 per doc/plans/003-flitkista-holo-meter.md (reuse 001's RewardSource.FLITKISTA label)"
] }
```
Order rationale: 001 introduces the shared types (`RewardSource`, `UnlockedReward`, `ONBOARDING_REWARD_LEVELS`, `clearedLevelsCountBeforeRun`) and the generator re-curve that 002 and 004 build on. 004 touches the generator again (achievement items), so it runs after 001's regen. 003 is mostly separate UI work; running it last avoids conflicts in `CelebrationSequence.kt`, `LevelCompleteCard.kt`, and the reading/math completion screens, which 001 also edits.

## Shared hot-spot files (later tasks must build on earlier edits, not overwrite them)

- `domain/engine/ProgressionAndGiftEngine.kt` (`recordLevelCompletion`): 001, 002, 004
- `domain/model/BalanceConfig.kt`: 001, 004
- `tools/item_pipeline/generate_wardrobe_items.py` → regenerated `GeneratedWardrobeCatalog.kt`: 001, 004. Always regenerate with the script, never hand-edit.
- `ui/LixoApp.kt:185/:232` (completion call sites): 001, 002, 004
- `ui/components/celebration/CelebrationSequence.kt`: 001, 004
- `ui/screens/{reading,math}/*ActivityScreen.kt` completion card: 001 (source labels), 003 (meter, points text removed)
- `data/repository/Repositories.kt`: 002 (sync merge, `levelRewardItemIdFor`), 004 (seed achievements reset to 0, star achievements)
- `ChildProfile` / `ProfileEntity` (+ Room migration if used): 004

## Rules for the run

- Don't stage or commit (global CLAUDE.md). Leave everything in the working tree for `git diff` review.
- **Don't run** `tools/content_pipeline/upload_content.py` without `--validate-only` (it publishes to Firestore). Re-uploading is a user action after 002.
- When a task finishes green: tick it in `FEATURES.md` (`- [x]`) and move it to `## Done`.
- If a task fails after the workflow's retries: leave it unticked, record the failure under it in `FEATURES.md`, and continue only with tasks that don't depend on it (if 001 fails, skip 002 and 004; 003 can still run).

## Verification

After each task, and once at the end:
- `./gradlew :composeApp:allTests` (or the commonTest/host test task the Discover phase finds). The full suite must pass, including the new tests listed in each plan.
- `python3 tools/content_pipeline/upload_content.py --validate-only` and `python3 -m pytest tools/tests` (002, 004).
- App check on the Pixel (haiku verify, view-hierarchy bounds for taps):
  1. New profile → math levels 1–8 → 1 chest per level 1–6, then level 8; chest labels shown; never a burst from one source.
  2. Map HUD top-right shows the holo chest partly filled, with no points text; the completion card's corner meter animates.
  3. Trophies screen shows 0/N for a new profile, including the 8 star achievements; reaching 20★ awards "Prestation: Stjärnfrö".
- Final opus review of the combined diff (built into the workflow).
