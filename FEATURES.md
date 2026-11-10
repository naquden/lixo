# Features & Bugs

Task queue. Each task links to a plan in `doc/plans/`. Run all pending tasks in one workflow later.

## Pending

- [ ] 033 — Rotate avatar up to ±90° sideways so capes/backpacks (SHOULDERS_BACK) peek out (feature) → [plan](doc/plans/033-avatar-turn-90.md)
- [ ] 034 — Wardrobe item cards sized from screen + font scale, not fixed 124dp (name never clipped) (bug) → [plan](doc/plans/034-wardrobe-card-proportional-size.md)
- [ ] 034 — Wardrobe: avatar stage and item area proportioned to screen height (Column weights, cards fill grid cells, short-screen/large-font fallback) (feature, after 031) → [plan](doc/plans/034-wardrobe-proportional-layout.md)

## Done

- [x] 032 — Shared hand items: any HAND_LEFT/HAND_RIGHT item can be equipped in either hand, mirrored onto that side (feature, after 031) → [plan](doc/plans/032-shared-hand-items.md)  
  _Verified 2026-11-10 (wf_e6e2f7a7-f90) on emulator-5554: `SharedHandItemsTest` 3/3 (incl. swordMovesBetweenHands), `WardrobeScreenContentTest` 5/5, `FriendLiveGearTest` 1/1, `BundledCatalogRenderTest` 4/4 (new everyAvatar_holdsHandItemsInOppositeHands); commonTest SharedHandItemsTest 6/6, ProfileStoreTest 15/15; screenshots temp/verify/031_032/032_*.png, swapped_avatar_*.png. Friend gear list now labels by equipped slot. LOW: friends on pre-032 builds draw swapped hand items on the authored side; on the cat, a right-hand item in the left hand sits slightly inward on the arm._

- [x] 031 — Wardrobe: avatar always visible while browsing and choosing items (pinned avatar, grid fills remaining height) (feature) → [plan](doc/plans/031-wardrobe-avatar-always-visible.md)  
  _Verified 2026-11-10 (wf_e6e2f7a7-f90) on emulator-5554 (S25 not attached): `WardrobeScreenContentTest` 5/5 (new avatarStaysVisible_whileBrowsingItems, slotSwitch_resetsGridToFirstCard, smallScreen_avatarAndOneRowVisible), `WardrobeRarityCountTest` 2/2 after fixing a 1px row-height mismatch (cards `wrapContentHeight(unbounded)` + fixed 124dp); host tests 333/333; screenshots temp/verify/031_032/. LOW: no vertical scroll fallback, so at large font scale or on a very short window the item area can shrink to nothing; 124dp card height doesn't follow font scale (name line may clip)._

- [x] 030 — Friends that work: anonymous identity, add by code/QR (multi-use 10-min window, no accept step), private names, live avatar + gear, no stars (feature) → [plan](doc/plans/030-friends-that-work.md)  
  _Verified 2026-11-10 on emulator-5554 (S25 not attached): FriendRevealTest 4/4, FriendCodeFlowTest 7/7, FriendLabelTest 2/2, FriendLiveGearTest 1/1, FriendDeepLinkTest 1/1, FirebaseFriendCloudEmulatorTest 1/1 (real FirebaseSyncService vs Auth+Firestore emulator, 14 scenarios) + Screenshot/ProfilePersistence/BottomNav/Wardrobe regression green; host tests 324/324, rules emulator tests 15/15, pytest 50/50; XML temp/verify/030_final/, screenshots temp/verify/lixo_screenshots/friends/. Manual: deploy rules + auth (anonymous), upload pack with nicknameWords, App Check console registration + debug tokens, enforcement last. iOS not compiled (kmp.ios.enabled=false). Simplified: landing anim = ring pulse, portal = fading circle. LOW: L3 device-clock countdown, no per-uid card/invite cap, L4 dirty-card retry in-memory only._

- [x] 029 — Dog tail visible past the left arm (bug) → [plan](doc/plans/029-dog-tail-visible.md)  
  _Verified 2026-11-09 (wf_9f965e6a-624): `BundledCatalogRenderTest` 3/3 on emulator-5554, pytest 47/47 (new `test_tails_clear_left_arm`); bare_avatar_dog.png tail visible right of left arm; screenshots in temp/verify/lixo_screenshots/verify027_029/. LOW: tracked __pycache__ files despite .gitignore (`git rm -r --cached`)._

- [x] 028 — Item previews on standard bones with base-body ghost, not chibi cat (bug) → [plan](doc/plans/028-item-preview-standard-bones.md)  
  _Verified 2026-11-09 (wf_9f965e6a-624): `BundledCatalogRenderTest#everyItem_rendersInItemPreview` + class 3/3 on emulator-5554; items_*.png show authored proportions, ghost has no cat parts; screenshots in temp/verify/lixo_screenshots/verify027_029/. Implemented via `PlacedLayer.authoredBoneSize` instead of plan's ghostScale map._

- [x] 027 — Held items (HAND_LEFT/RIGHT) scale uniformly, round shields stay round on slim avatars (bug) → [plan](doc/plans/027-held-items-uniform-scale.md)  
  _Verified 2026-11-09 (wf_9f965e6a-624): `BundledCatalogRenderTest` 3/3 on emulator-5554 (S25 not attached), LayerTransformTest green; round shields round on princess/witch, swords keep proportions, gloves still stretch; screenshots in temp/verify/lixo_screenshots/verify027_029/. LOW: held-item anchor ignores HAND bone offset overrides (none bundled today)._

- [x] 026 — Distinct avatars: own silhouette (bone sizes), colours, palette, signature parts (hair/beard/muzzle/skirt), items stretch per bone (feature, after 021) → [plan](doc/plans/026-distinct-avatars.md)  
  _Verified 2026-11-09 (wf_3b7e3745-12a): `BundledCatalogRenderTest` 3/3 on emulator-5554 (S25 not attached); AvatarSilhouetteTest 2/2, LayerTransformTest 9/9, ContentPackValidatorTest 18/18, BundledPackValidTest 1/1, pytest 46/46; 6 bare + 48 equipped + 20 item sheets in temp/verify/lixo_screenshots/verify026/, 6 avatars distinct. LOW: round shields/swords stretch non-uniformly on slim avatars; ItemPreview now uses cat (chibi) bones; dog tail hidden behind arm; HAT_REACH_AU=67 hard-coded; feet moved to 108*base on all screens (check AvatarMapScreen margin)._

- [x] 021 — Authoring pipeline: port 72 shapes + 6 avatars to layers, build bundled content_pack.json, stability diff (feature, after 020) → [plan](doc/plans/021-content-pack-authoring-pipeline.md)  
  _Verified 2026-11-09: `BundledCatalogRenderTest` 3/3 on emulator-5554 (fixed @Before wiping the other test's PNGs; added `everyAvatar_rendersBare`); 20 item sheets + 48 equipped + 6 bare avatars in temp/verify/lixo_screenshots/verify021/; python tests 40/40 (per_slot fix already in). Port faithful to 2f19e59 (avatars differed only by cat/dog ears). Avatars look alike → 026._
- [x] 025 — Events/holiday specials: TrustedClock, EventEngine, event drops, themed chest, banner, Jul 2026 sample (feature, after 023 + 024) → [plan](doc/plans/025-content-events.md)  
  _Verified 2026-11-09 (wf_44921b77-24a): `EventBannerTest` 3/3, `EventChestThemeTest` 2/2 on emulator-5554; screenshots temp/verify/lixo_screenshots/verify_025_*.png. LOW: TrustedClock high-water mark raised from unanchored device time (forward-set clock sticks until first server anchor)._
- [x] 024 — Cloud sync of content pack: Firestore meta/chunks, sha256, Room cache, hot-swap, upload_pack.py (feature, after 022) → [plan](doc/plans/024-cloud-content-sync.md)  
  _Verified 2026-11-09 (wf_44921b77-24a): `ContentHotSwapTest` 2/2 on emulator-5554; screenshots temp/verify/lixo_screenshots/024_{before_sync,after_sync,restart_cached}.png. Firestore rules deploy + first upload manual. LOW: rejected pack re-downloaded every sync (remember rejected version/sha); commit published/pack_v1.json so stability diff runs._
- [x] 023 — Drop rules from content: odds, pity, onboarding, explicit levelRewards, achievementRewards (feature, after 022) → [plan](doc/plans/023-drop-rules-from-content.md)  
  _Verified 2026-11-09 (wf_44921b77-24a): drop-rule unit tests green; `FlitChestMeterTest` 4/4, `LevelRewardItemTest` 1/1, `RewardPacingTest` 2/2 on emulator-5554._
- [x] 022 — App runs on the content pack (bundled): drop AvatarType/GeneratedWardrobeCatalog/styleIndex renderers/015 merge, Room reset (feature, after 021) → [plan](doc/plans/022-app-on-content-pack.md)  
  _Verified 2026-11-09 (wf_44921b77-24a): `WardrobeScreenContentTest` 2/2, `ItemRevealContentTest` 3/3, `ProfilePersistenceTest` 3/3, `AchievementLadderTest` 5/5 on emulator-5554; screenshots temp/verify/lixo_screenshots/022_*.png. Room reset to v1 + destructive fallback → `adb shell pm clear se.lixo.app` on dev devices. Engine tests moved to androidHostTest (no iOS run). LOW: remove destructive fallback before release; drop dead wardrobeItems block from seed_campaign_content.json._
- [x] 020 — Generic layer renderer for avatars/items + item preview + debug ContentPreviewScreen (feature, after 019) → [plan](doc/plans/020-layer-renderer.md)  
  _Verified 2026-11-09 (wf_44921b77-24a): LayerTransformTest, ZOrderTest green; `verify/LayerRendererScreenshotTest` 2/2, `ContentPreviewScreenTest` 2/2 on emulator-5554; screenshots temp/verify/lixo_screenshots/verify020/._
- [x] 019 — Content pack: schema, skeleton contract (Bone/ZBucket), validator, ActiveContent index (feature) → [plan](doc/plans/019-content-pack-schema.md)  
  _Verified 2026-11-09 (wf_44921b77-24a, emulator-5554 Pixel_10): ContentPackParseTest 5/5, ContentPackValidatorTest 15/15, ContentPathsTest 4/4, ActiveContentIndexTest 4/4; bundled content_pack.json ~590 KB; doc/content/content-pack.md written. No UI._
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

