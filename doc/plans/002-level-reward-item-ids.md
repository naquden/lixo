# 002 — Per-level reward items point to non-existent catalog ids

**Type**: bug

**Depends on**: 001 (reward pacing). Run after 001; this plan plugs into 001's onboarding schedule.

## Goal

Every campaign task's `rewardItemId` must resolve to a real wardrobe item, so per-level rewards actually unlock. At the same time, they must not break the reward pacing from 001: no reward item on every level forever.

## Context / root cause

There are two sources of tasks, and they disagree:

1. **Local seed (offline, first launch)**: `composeApp/src/commonMain/kotlin/se/lixo/app/data/repository/Repositories.kt:765` `buildSeedCampaignLevelsAndTasks(wardrobeItems)` assigns `rewardItemId` from `levelRewardPool` (round-robin over the slots of the **real** catalog, ~line 773-790). These ids are valid.
2. **Firestore (after sync)**: `tools/content_pipeline/upload_content.py` uploads `composeApp/src/commonMain/composeResources/files/seed_campaign_content.json` as-is. Its 162 `rewardItemId`s use an old naming scheme (`item_hat_2`, `item_shoulders_back_1`, `item_armor_shirt_N`, …). The current generator (`tools/item_pipeline/generate_wardrobe_items.py`) names items `item_<slot>_<style>_<colorway>` (e.g. `item_hat_keps_djuphavsbla`). **0 of the 162 match.** Only `item_hat_starter_explorer` exists (handmade).
3. Sync merge, `Repositories.kt:586-591`: `remoteTasks.forEach { next[task.id] = task }` **overwrites** the local tasks wholesale, including the valid local `rewardItemId`.
4. Engine `ProgressionAndGiftEngine.kt:148`: `allWardrobeItems.find { it.id == rewardItemIdForTask }` returns null, so nothing unlocks, silently.

Result: on synced devices, level reward items never unlock. On offline/unsynced devices, **every** level gives its reward item, which would conflict with 001's taper if left as-is.

There's no validation anywhere: `upload_content.py --validate-only` and `tools/tests/test_pipelines.py` don't check `rewardItemId` against the catalog.

## Design decision

Item ids belong to the **app** (generated catalog). Curriculum content (JSON/Firestore) shouldn't hard-code them; the generator says ids must never be renamed, but the JSON was written against an older scheme. So:

- **The app is the single source of truth for level reward items.** `rewardItemId` is derived in code from the level order via `levelRewardPool`, for both seeded and synced tasks.
- **Plug into 001's schedule.** A level's `rewardItemId` is used *as* the onboarding-track item, a curated, deterministic item instead of a random roll. It's only awarded when the first clear hits a slot in `BalanceConfig.ONBOARDING_REWARD_LEVELS`; off-schedule levels give no level item. If it's invalid or already owned, fall back to 001's random roll.

## Steps

1. **Sync merge** (`Repositories.kt:586-591`): when merging a remote task, keep the local `rewardItemId` (`next[task.id] = task.copy(rewardItemId = current[task.id]?.rewardItemId)`). If no local task exists, compute it from `levelRewardPool` by level order (extract the pool and its lookup into a reusable function, e.g. `levelRewardItemIdFor(order, catalog)`, used by both seed and sync).
2. **Remove `rewardItemId` from the JSON**: strip the field from all tasks in `seed_campaign_content.json` (a one-off script or `jq`, keeping formatting stable). Make `upload_content.py` `validate_bundle` **reject** any task that contains `rewardItemId` (with a message that says the app owns item ids), so it can't regress.
   - Firestore still has the old field until the next upload. Step 1 makes the app ignore it either way. Mention re-running the upload in the summary, but **don't run the upload** (it's outward-facing; leave it to the user).
3. **Engine** (`ProgressionAndGiftEngine.kt` step 1 + 001's onboarding block): merge them. On `!isReplay` with `clearedLevelsCountBeforeRun + 1 in ONBOARDING_REWARD_LEVELS`, award `rewardItemIdForTask` if it resolves and isn't owned, otherwise roll as in 001. On non-schedule levels, ignore `rewardItemIdForTask`. Tag it `RewardSource.LEVEL`; it uses the shared progression slot from 001 (max 1 progression item per run). Update the KDoc.
4. **Unknown-id logging**: if `rewardItemIdForTask` is non-null but not in the catalog, log a warning (existing logging facility, if there is one) instead of failing silently.
5. Achievement `rewardItemId`s (`Repositories.kt:982-1015`) are already valid handmade items. Don't change them, but cover them in the test below.

## Tests / verification

- `LixoEnginesTest.kt`: every task from `buildSeedCampaignLevelsAndTasks(buildSeedWardrobeCatalog())` has a `rewardItemId` that exists in the catalog, isn't an achievement/streak item, and the ids are unique per level.
- Same check for every `Achievement.rewardItemId`.
- Sync merge: given a remote task with `rewardItemId = "item_hat_2"`, the merged task keeps the valid local id; a remote-only task gets the computed id.
- Engine: an onboarding-slot first clear awards the level's `rewardItemId`; an off-schedule first clear awards nothing from step 1; an owned/invalid id falls back to the roll; a replay never awards it.
- `tools/tests/test_pipelines.py`: the bundle has no `rewardItemId` field; `validate_bundle` rejects a task that has one.
- Run `python3 tools/content_pipeline/upload_content.py --validate-only`, the Python tests, and the full commonTest suite; all must pass.
- In the app (synced profile): clear level 1 → the chest shows that level's curated item (check the id via log).

## Open questions

- After merging, re-upload content to Firestore (`python3 tools/content_pipeline/upload_content.py`) so the remote docs drop the stale field. User action, not part of the automated run.
