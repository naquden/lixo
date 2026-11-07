# Features & Bugs

Task queue. Each task links to a plan in `doc/plans/`. Run all pending tasks in one workflow later.

## Pending

## Done

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

