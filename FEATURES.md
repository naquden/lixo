# Features & Bugs

Task queue. Each task links to a plan in `doc/plans/`. Run all pending tasks in one workflow later.

## Pending


## Done

- [x] 018 — Review follow-ups from 013–015: wardrobe items skipped by minAppVersionCode never re-fetched after app update (HIGH), owned achievement rewards skip celebration, ladder strip clipped at 360dp, test helper in prod, housekeeping (bug, after 015) → [plan](doc/plans/018-review-followups-013-015.md)  
  _Verified 2026-11-08 (wf_14b957d0-336): `verify/AchievementLadderTest` 5/5 (new `ladderStrip_fitsAt360dp`), `RemoteWardrobeItemTest` 3/3, `ProfilePersistenceTest` 3/3 on emulator-5554 (Pixel_10); screenshot temp/verify018/. v7 schema changed in place (appVersionCode) → `adb shell pm clear se.lixo.app` on dev devices that ran a 015 build. .pyc untracking left for user (`git rm --cached`; .gitignore updated). Known LOW: owned-reward NextChallenge also built for pending achievements from earlier runs; no label-only step for owned final-tier unlocks._
- [x] 017 — "Läsa själv": "Jag har läst klart" unlocks after a fixed 15 s instead of 0.35 s/word (min 4 s) (feature) → [plan](doc/plans/017-manual-reading-15s-delay.md)  
  _Verified 2026-11-08 (wf_14b957d0-336): `verify/ManualReadingDelayTest` 1/1 on emulator-5554 (Pixel_10): 'Läser... (15s)' → '(1s)' at 14 s → enabled at 15 s; screenshots temp/verify017/. Note: actual button label is 'KLAR MED LÄSNINGEN' (`READING_MANUAL_DONE_BTN`), not 'Jag har läst klart'._
- [x] 016 — English course: correct answer always first option (and only 3 options on "star"); shuffle 4 unique choices per question (bug, after 013/014) → [plan](doc/plans/016-english-choice-order.md)  
  _Verified 2026-11-08 (wf_14b957d0-336 + test fix: dropped `performScrollTo()` on the fixed NÄSTA UPPGIFT button): `verify/EnglishChoiceOrderTest` 2/2 on emulator-5554 (Pixel_10), `EnglishChoicesTest` 7/7; screenshots temp/verify016/ (hat: answer at pos 3; star: 4 options)._
- [x] 015 — Remote wardrobe items: publish new items (e.g. chest legendaries) via Firestore without an app release, cached on device (feature) → [plan](doc/plans/015-remote-wardrobe-items.md)  
  _Verified 2026-11-08 (wf_6a066538-e23): `verify/RemoteWardrobeItemTest` 3/3 (incl. DB 6→7 migration), `ProfilePersistenceTest` 3/3 on emulator-5554 (Pixel_10); screenshots in temp/verify015/. Review HIGH: items skipped for minAppVersionCode never re-fetched after app update → task 018. Firestore rules deploy + first upload are manual._
- [x] 014 — Achievement ladders: 9 tiered trophy chains (48 tiers, rising rarity) + picture-first read-aloud challenge cards (feature, after 004) → [plan](doc/plans/014-achievement-ladders.md)  
  _Verified 2026-11-08 (wf_6a066538-e23): `verify/AchievementLadderTest` 4/4, `AchievementTrackingTest` 2/2 on emulator-5554 (Pixel_10); catalog regenerated (ids unchanged), pytest pass; screenshots in temp/verify014/. Known LOW → 018: owned reward skips celebration, ladder strip clipped at 360dp, LixoStrings import in data layer._
- [x] 013 — Bottom nav: "Karta" → "Lär dig" with open-storybook icon, "Kompisar" → "Avatar" (feature) → [plan](doc/plans/013-nav-learn-and-avatar-tabs.md)  
  _Verified 2026-11-08 (wf_6a066538-e23): `verify/BottomNavLabelsTest` 2/2 + `screenshots/ScreenshotTest` 5/5 on emulator-5554 (Pixel_10); screenshots in temp/verify013/._
- [x] 012 — Rarity follow-ups from 011 review: pity floor stuck when tier exhausted, idle teaser animations, 'LEGENDARISK' label wrap, misplaced WardrobeScreen KDoc (bug, after 011) → [plan](doc/plans/012-rarity-followups.md)  
  _Verified 2026-11-08: `verify/WardrobeRarityCountTest` 2/2 (new `wardrobe_legendaryBadgeStaysOnOneLine`), `RarityRevealTest` 3/3 on emulator-5554; `LixoEnginesTest` 82/82 (2 new pity tests), `ConfettiPhysicsTest` 10/10, pytest 28/28. Screenshots in temp/verify012/. Known LOW: Flitkista `cap=` diagnostic log rescans catalog per tier and logs post-unlock cap; TextAutoSize import out of order in WardrobeScreen.kt._
- [x] 011 — Rarity: rarity-first drop odds 40/28/18/10/4, chest pity, visual ladder, chest teaser, collection counts (feature) → [plan](doc/plans/011-rarity-odds-and-feel.md)  
  _Verified 2026-11-08: `verify/RarityRevealTest` 2/2, `WardrobeRarityCountTest` 1/1, `ItemCatalogScreenshotTest` 1/1, `ProfilePersistenceTest` 3/3 on emulator-5554 (DB 5→6 migration OK); unit + pytest 28/28 (ids/names/rarities identical to fixture). Screenshots in temp/verify011/. Known LOW: pity floor never resets once every eligible item of that tier is owned; teaser infinite transitions run for all rarities; 'LEGENDARISK' label wraps in wardrobe card; misplaced WardrobeScreen KDoc._
- [x] 010 — Persist level progress (regression from 008: map relocks, stars and chests are handed out again after restart) (bug, after 008) → [plan](doc/plans/010-persist-level-progress.md)  
  _Verified 2026-11-07: `verify/ProfilePersistenceTest` 3/3 (incl. `coldStart_keepsLevelProgress`) + `ItemCatalogScreenshotTest` 1/1 on emulator-5554, `ProfileStoreTest` 10/10; screenshots in temp/verify010/. Known LOW: a level clear is saved in several non-atomic writes; 008-era installs have no level_progress rows._
- [x] 007 — Tutorial: skipped words block completion; allow "Gå vidare" once last word is read (bug) → [plan](doc/plans/007-tutorial-stuck-unread-words.md)  
  _Verified 2026-11-07: `verify/TutorialUnreadWordsTest` 2/2 on emulator-5554 + 2 unit tests; screenshots in temp/verify007/._
- [x] 008 — Created profiles lost on app restart; persist profiles in Room, debug may only touch Saga (bug) → [plan](doc/plans/008-persist-profiles.md)  
  _Verified 2026-11-07: `verify/ProfilePersistenceTest` 2/2 on emulator-5554, `ProfileStoreTest` 7/7; screenshots in temp/verify008/. Review HIGH: level progress not persisted → task 010._
- [x] 009 — Instrumented test that screenshots every wardrobe item, grouped in grids (feature) → [plan](doc/plans/009-item-catalog-screenshots.md)  
  _Verified 2026-11-07: `verify/ItemCatalogScreenshotTest` 1/1 on emulator-5554; 27 sheets / 582 items, in temp/verify009/._
- [x] 004 — Track achievements (incl. 8 star-collection milestones) and award chest rewards (feature, after 001) → [plan](doc/plans/004-achievement-tracking.md)  
  _Verified 2026-11-07: `verify/AchievementTrackingTest` 2/2 on R5CXC0681EM (test fixed: double setContent), screenshots in temp/verify004/._
- [x] 001 — Reward pacing: 1 chest per level at start, no floods or droughts, taper later (bug) → [plan](doc/plans/001-reward-pacing-early-chests.md)  
  _Done 2026-11-07 (wf_dcb12828-ae6): unit tests 8 new rewardPacing_* pass; `verify/RewardPacingTest` written, device result not confirmed._
- [x] 002 — Per-level reward item ids don't exist in catalog; app owns ids, use them as onboarding rewards (bug, after 001) → [plan](doc/plans/002-level-reward-item-ids.md)  
  _Done 2026-11-07: `verify/LevelRewardItemTest` + `RewardPacingTest` pass on Pixel 7; pipeline tests 25/25, `--validate-only` OK. Re-upload content to Firestore yourself._
- [x] 005 — Gula ord: visa vad AI hörde vs ordet i hjälp-popupen (feature) → [plan](doc/plans/005-heard-word-compare-popup.md)  
  _Done 2026-11-07: `verify/HeardWordPopupTest` 4/4 on Pixel 7._
- [x] 006 — Release builds: no "Saga" dummy profile; first-run profile setup screen (feature) → [plan](doc/plans/006-no-dummy-profile-in-release.md)  
  _Done 2026-11-07: `verify/FirstProfileSetupTest` 2/2 + 4 unit tests. Known LOW (profiles not persisted) fixed by 008. Resolved by 008 (profiles persisted in Room)._
- [x] 003 — Flitkista holo chest meter in top-right corner replaces points text (feature) → [plan](doc/plans/003-flitkista-holo-meter.md)  
  _Verified 2026-11-07: `verify/FlitChestMeterTest` 4/4 on R5CXC0681EM, screenshots in temp/verify003/. Known LOW: HUD meter clipped to a rounded square (FlitChestMeter.kt:121)._

