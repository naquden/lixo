# 025 — Events / holiday specials from content (time windows, event pool, themed chest, banner)

**Type**: feature
**Depends on**: 023, 024

## Goal
Holiday specials (e.g. "Jul 2026", Dec 1–26) are defined entirely in the content pack: a time window, event-only items, a boosted drop chance, a themed chest look and a banner. Running one is an upload, not an app release. After an event ends, its items stop dropping and owners keep them.

## Context
- The schema `events[]` and the item `pool: event` + `eventId` come from 019. The validator already skips bad events.
- Drops: `ProgressionAndGiftEngine.rollChest` / `rollPresentGift` with `DropRules` (023).
- The chest visuals are in `ui/components/celebration/` (`ChestRevealStage`, `RewardChest`, `ItemReveal.kt`); there is a home/map screen for the banner (`ui/screens/map/CampaignMapScreen.kt`).
- `fetchContentMeta` returns the server time (024).
- No time-limited content exists anywhere today.

## Steps
1. **`domain/engine/TrustedClock.kt`** (pure, with an injectable `deviceNow()` and `monotonicNow()`):
   - `onServerTime(serverMillis)` stores `(serverMillis, monotonicAtThatMoment, bootId)` and persists `lastServerMillis` (AppSettings).
   - `now()`: in the same boot, `server + (monotonic − monotonicAt)`; otherwise `max(deviceNow, lastServerMillis)`. Setting the clock back can never revive an ended event. Setting it forward offline can start an event early, which is accepted because events are cosmetic.
   - Platform monotonic time: Android `SystemClock.elapsedRealtime()`, iOS `NSProcessInfo.systemUptime`. Add an `expect`/`actual` in the existing platform-contracts file.
2. **`domain/engine/EventEngine.kt`** (pure):
   - `activeEvents(content, nowMillis, timeZone): List<ContentEvent>`: windows are local calendar dates, `start <= localDate < endExclusive`, using `kotlinx-datetime` if it is present (otherwise add it).
   - `eventPool(content, events, owned)`: event items that aren't retired.
   - `isDroppable(item, now)`:
     - `pool == standard`: when not retired
     - `pool == event`: when its event is active, or when the event has ended and `afterEnd == joinStandard`
     - `afterEnd == retire` (default): an ended event's items never drop again
3. **Drops** (`ProgressionAndGiftEngine`, with `activeEvents` passed in):
   - `rollChest`: when events are active, with probability `dropBoost.eventItemChance`, roll from the event pool (prefer unowned, using the same rarity weights limited to the rarities present); otherwise do the standard roll. Pity applies to both.
   - `rollPresentGift`: the same, with `presentGiftChance`.
   - Onboarding, level rewards and achievements are unaffected.
4. **UI:**
   - `ui/components/EventBanner.kt`: shows `string(banner.titleKey)` with `bgHex`/`fgHex` on the map/home screen while an event is active; tapping it opens a sheet listing the event items with `ItemPreview` and an owned check.
   - Chest theme: `RewardChest`/`ChestRevealStage` take an optional `ChestTheme(bodyHex, trimHex, ornament)`. Use the first active event's theme when a chest opens during an event.
5. **Wiring:**
   - `TrustedClock.onServerTime` is called from `ContentRepository.sync` (024).
   - `LixoApp` computes `activeEvents` from `content.active` + `TrustedClock.now()`, and recomputes on resume and at local midnight.
6. **Sample event in the pipeline (021):** add `events/jul_2026.json`:
   - `start 2026-12-01`, `endExclusive 2026-12-27`
   - a red/gold chest theme and a "God Jul!" banner
   - `eventItemChance 0.5`, `presentGiftChance 0.5`, `afterEnd retire`

   Add `catalog/jul_2026.json` with 9 items, one per slot (`pool: event`, `eventId: event_jul_2026`). Shape ideas: tomte hat, reindeer antlers (HEAD_OVER), scarf, mittens, Christmas jumper, red trousers, boots, candy cane, gift box, plus strings. Write the new shapes as layer paths; this demonstrates new shapes without an app release.
7. **README "Holiday special" section, end to end:**
   1. Add the catalog and event files and the strings.
   2. Run `build_pack.py`, then `validate_pack.py`.
   3. Check the preview screenshots (`ContentPreviewScreen` / `BundledCatalogRenderTest`).
   4. Run `upload_pack.py --dry-run`, then the real upload.
   5. Commit the sources, the snapshot and `published/`.

## Tests / verification
commonTest:
- `TrustedClockTest`:
  - same boot: device clock moved back → `now()` still advances with monotonic time
  - new boot with the device clock behind `lastServerMillis` → `now() == lastServerMillis`
  - device clock ahead → device time is used
- `EventEngineTest`:
  - active on Dec 1 00:00 local, inactive on Dec 27 00:00 local, inactive Nov 30 23:59
  - a time zone change shifts the window to local dates
  - `afterEnd retire` → event items not droppable after the end; `joinStandard` → droppable
- `EventDropTest`: `eventItemChance = 1.0` with an active event → the chest always gives an event item; no active event → never; pity is still honoured.

Instrumented:
- `se.lixo.app.verify.EventBannerTest`: a pack with the Jul event and a fixed clock inside the window → `onNodeWithText("God Jul!")` exists on the map screen; a clock outside the window → it doesn't exist.
- `se.lixo.app.verify.EventChestThemeTest`: a chest reveal during the event uses the theme and shows an event item name; screenshot to `temp/verify025/`.

Run:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.EventBannerTest,se.lixo.app.verify.EventChestThemeTest
```

## Open questions
- None blocking. The Jul 2026 item shapes are content and can be iterated after the task (no release needed).
