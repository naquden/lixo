# 018 — Review follow-ups from 013–015 (wardrobe re-validation on app update, owned achievement rewards, ladder clipping, housekeeping)

**Type**: bug
**Depends on**: 014, 015 (both done, in working tree).

## Goal
Fix the findings from the final review of workflow run `wf_6a066538-e23`. One is HIGH (015), the rest are LOW.

## Context / findings
1. **HIGH: remote items skipped for `minAppVersionCode` never come back after an app update.** `LixoRepository.syncWardrobeFromCloud` (`composeApp/src/commonMain/kotlin/se/lixo/app/data/repository/Repositories.kt`, ~`:312`) returns early when `meta.version == wardrobeVersion`, and `wardrobeVersion` is loaded from the Room cache, which stores only accepted items. Example: v5 publishes an item that needs versionCode 20. A device on 19 skips it. After updating to 20, the cached version is still 5, so nothing is fetched and the item never appears. The same happens when a newer app relaxes any `RemoteWardrobeValidator` rule.
2. **LOW: an achievement reward is silently lost when the item is already owned.** `ProgressionAndGiftEngine.kt` ~`:440` hits `item.id in workingUnlockedIds -> continue`. Plan 014 reserved 36 previously chest-eligible items, so existing profiles may already own a tier's reward. The tier then unlocks with no "Prestation" moment and no "Ny utmaning!" step.
3. **LOW: the ladder strip is clipped on 360dp phones.** `ui/components/challenge/ChallengeCard.kt` ~`:196`, `LadderStrip`, is a plain `Row` of 26dp tiles + 4dp spacing. The 10 COLLECT_STARS tiers need 296dp, but only 288dp is available at 360dp width.
4. **LOW: a test-only destructive helper ships in production.** `rewindAndroidTestDatabaseToVersion6` in `composeApp/src/androidMain/kotlin/se/lixo/app/platform/PlatformAndroid.kt` ~`:61`.
5. **LOW: the data layer depends on the UI.** `buildSeedAchievements` in `Repositories.kt` imports `se.lixo.app.ui.theme.LixoStrings` for the challenge strings.
6. **LOW, housekeeping:** `composeApp/schemas/se.lixo.app.data.local.LixoDatabase/7.json` is untracked (1–6 are tracked). `__pycache__/*.pyc` files are tracked in git and churn on every run.

## Steps
1. **(1)** Add `appVersionCode INTEGER NOT NULL DEFAULT 0` to the `wardrobe_catalog_meta` entity. Since 7 is not released yet, extend the 6→7 migration and the `7.json` schema instead of adding a v8. If 7 already shipped anywhere, add a 7→8 migration instead. `WardrobeCatalogCache.loadVersion()` returns `(version, appVersionCode)`, and `upsert` stores the current app version code. In `syncWardrobeFromCloud`, return early only when `meta.version == wardrobeVersion && cachedAppVersionCode == currentAppVersionCode`. Otherwise re-fetch, re-validate and upsert (upsert-only still holds).
2. **(2)** In `recordLevelCompletion`, when an unlocked achievement's reward is already owned, still emit a celebration moment: add the achievement to `LevelCompletionOutcome.newlyUnlockedAchievements` (already done) and have `buildCelebrationSteps` emit `CelebrationStep.NextChallenge` for **every** newly unlocked achievement that has a next tier, not only the awarded ones. Optionally add a label-only "Prestation: <title>" step reusing the trophy dialog without a chest. Do not hand out a duplicate item or a replacement.
3. **(3)** Change `LadderStrip` to `FlowRow` (or shrink to 22dp tiles / 3dp spacing when `tiers.size > 8`) so 10 tiers fit at 360dp.
4. **(4)** Move `rewindAndroidTestDatabaseToVersion6` into `androidApp/src/androidTest/...` (a test util next to `RemoteWardrobeItemTest`). If it needs internal access, keep it `internal` + `@VisibleForTesting` instead.
5. **(5)** Move the `challenge*Sv` string builders out of `LixoStrings` into `domain/model/AchievementStrings.kt` (next to `AchievementLadders.kt`). `LixoStrings` delegates to them if the UI uses them.
6. **(6)** Add `__pycache__/` and `*.pyc` to `.gitignore`, then run `git rm --cached` on the tracked `.pyc` files (the working tree only; don't commit). Leave `7.json` in the tree for the user to commit with the migration.

## Tests / verification
commonTest:
- `RemoteWardrobeTest`: an item with `minAppVersionCode = 20` is rejected at app version 19. The same remote version re-synced at app version 20 → accepted, `fetchWardrobeItems` called. The same version + the same app version → not called.
- `CelebrationStepsTest`: an outcome with a newly unlocked achievement whose reward was already owned (no ACHIEVEMENT reward) still contains `NextChallenge` for that chain.
- Full commonTest suite + pytest.

Instrumented (Pixel_10 emulator `emulator-5554`):
- `AchievementLadderTest`: new `ladderStrip_fitsAt360dp`. Wrap `TrophiesScreen` in `Box(Modifier.width(360.dp))` and assert the last COLLECT_STARS tier node (`ladder_tier_ach_stars_480`) `isDisplayed()`. Capture a screenshot.
- `RemoteWardrobeItemTest` + `ProfilePersistenceTest` still pass (migration).
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.AchievementLadderTest,se.lixo.app.verify.RemoteWardrobeItemTest,se.lixo.app.verify.ProfilePersistenceTest`

## Open questions
- None.
