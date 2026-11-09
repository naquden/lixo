# Publishing new content (no app release)

This is the runbook for shipping new avatars, items, events or drop-rule changes to installed apps.
The format is described in [content-pack.md](content-pack.md). Pipeline internals are in
[`tools/content_pipeline/pack/README.md`](../../tools/content_pipeline/pack/README.md).

## How it works

```
sources (tools/content_pipeline/pack/)  ──build_pack.py──▶  out/content_pack.json  (version N)
                                                              │  also copied to the app's bundled
                                                              │  composeResources/files/content_pack.json
                                        ──validate_pack.py──▶ rules + stability diff vs published/
                                        ──upload_pack.py────▶ Firestore content_chunks/1_N_i, then content/meta
                                                              + published/pack_vN.json (commit it)
apps: ContentRepository.sync() at startup and on resume (at most every 15 min)
      → version N > active? → download chunks → check sha256 → validate → cache → hot swap
```

- Each pack replaces the whole previous one; there are no partial patches. A pack must contain
  everything, old items included.
- The app only switches to a pack whose `version` is **greater** than the one it already runs
  (bundled or cached).
- If the app rejects a pack, it silently keeps the content it already has. Validate locally, so
  you never rely on that fallback.
- The current published version is the newest `tools/content_pipeline/pack/published/pack_v<N>.json`.
  It is also in Firestore at `content/meta` → `packs."1".version`.

## Prerequisites (once per machine)

- `firebase login`. The account needs access to the Firebase project `lixo-fd45d`.
- Firestore rules are already deployed (`firebase deploy --only firestore:rules`). Redeploy only
  when `firebase/firestore.rules` changes.
- Python 3 (stdlib only). Run every command from the repo root.

## Standard publish steps

1. **Edit the sources** (see the recipes below).
2. **Build** (the version defaults to the latest published version + 1):
   ```
   python3 tools/content_pipeline/pack/build_pack.py
   ```
3. **Validate.** It must print no errors:
   ```
   python3 tools/content_pipeline/pack/validate_pack.py
   ```
   This includes the **stability diff** against the last published pack. The diff fails if an item,
   avatar or event id was removed, a `levelRewards`/`achievementRewards`/`starUnlocks` entry
   changed, an item's slot changed, or the version did not increase.
4. **Look at it** before kids do. Use either of these:
   - Preview file on a debug build (emulator or device):
     ```
     adb push tools/content_pipeline/pack/out/content_pack.json /data/local/tmp/content_pack_preview.json
     adb shell run-as se.lixo.app sh -c 'mkdir -p files && cp /data/local/tmp/content_pack_preview.json files/content_pack_preview.json'
     # open the debug ContentPreviewScreen; its subtitle reads "förhandsfil vN"
     adb shell run-as se.lixo.app rm files/content_pack_preview.json
     ```
   - Instrumented render of the bundled pack. Every item and avatar is rendered, with screenshots on
     the device:
     ```
     ANDROID_SERIAL=<device> ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
     ```
5. **Dry run.** It prints the Firestore writes and uploads nothing:
   ```
   python3 tools/content_pipeline/pack/upload_pack.py --dry-run
   ```
6. **Upload.** Chunks are written first and meta last, so clients never see a half-uploaded pack:
   ```
   python3 tools/content_pipeline/pack/upload_pack.py
   ```
   On success the script writes `published/pack_v<N>.json` and prunes chunk docs older than the
   last 3 versions.
7. **Check what is live.** Public read, no login:
   ```
   curl -s "https://firestore.googleapis.com/v1/projects/lixo-fd45d/databases/(default)/documents/content/meta"
   shasum -a 256 tools/content_pipeline/pack/out/content_pack.json   # must equal meta sha256
   ```
8. **Commit.** Include the changed sources, `published/pack_v<N>.json` (the next stability
   baseline; required) and the bundled `composeApp/src/commonMain/composeResources/files/content_pack.json`,
   so the next app release ships the pack as its offline seed.

Devices pick up the new pack on their next start, or on a resume at least 15 minutes after their
last sync.

## Recipes

### New item or items (permanent, normal drops)
- **Reuse an existing shape** (one of the 8 styles of a slot with a new palette): add a row to
  `catalog/handmade.json` with `"template": "<slot>:<styleIndex>"`, `palette`, `rarity`, `pattern`,
  `ornament`, `scale` and `"pool": "standard"`. Add `<id>.name` / `<id>.desc` to `strings/sv.json`.
- **New shape for a single item**: put a row with its own `layers` (SVG paths in bone-local
  units; see content-pack.md for the bone boxes and z buckets) in a new `catalog/<name>.json`.
  Hand-written rows must give `palette.accent`. Put the strings in `strings/<name>.sv.json`.
- **New reusable style**: append a `StyleTemplate` to the end of `templates/slots/<slot>.py`,
  never in the middle. Then re-run `python3 tools/content_pipeline/pack/gen_catalog.py` (without
  `--init-drop-rules`).
- Ids must match `^[a-z][a-z0-9_]{2,63}$` and are permanent. Never rename one.

### Holiday or seasonal event
Copy the Jul 2026 sample:
- `catalog/<event>.json`: items with `"pool": "event"`, `"eventId": "<event id>"` and their own
  `layers`.
- `events/<event>.json`: one object with these fields:
  - `id` and `nameKey`
  - `start` / `endExclusive` (local `YYYY-MM-DD`, end not included)
  - `tz: "local"` and `itemIds`
  - `chestTheme {bodyHex, trimHex, ornament}`
  - `banner {titleKey, bgHex, fgHex}`
  - `dropBoost {eventItemChance, presentGiftChance}` (0..1)
  - `afterEnd`: `retire` (stops dropping after the end) or `joinStandard` (becomes a normal drop)
- `strings/<event>.sv.json`: the event name, the banner title and every item name/desc.

Publish **before** `start`. Devices only see the event after their next sync. The event shows
from local midnight on `start` and is hidden from local midnight on `endExclusive`. Owners keep
event items forever.

### Change drop tuning
In `drop_rules.json` you can freely change `rarityWeights` (all 5 must be > 0),
`chestCostHalfPoints`, `progressRunHalfPoints`, `replayRunHalfPoints`, `pity` (RARE < EPIC <
LEGENDARY) and `onboarding.levels`.
`levelRewards`, `achievementRewards` and `starUnlocks` are **append-only**. You can add entries
(e.g. rewards for new levels), but never change or remove existing ones, because players' saved
progress depends on them.

### New avatar
Add `templates/avatars/avatar_<id>.json`, starting from an existing one: `order`, `humanoid`,
`skinHex`, `defaultTintHex`, `unlock` (`{"type":"stars","stars":N}`), plus `parts` and the
`<id>.name` string. Avatar ids are permanent.

### Take an item out of drops
Items can **never be deleted**, because owned items must keep rendering. The intended mechanism is
`"retired": true`, which keeps the item renderable for owners but removes it from every pool. It is
not wired up yet: `build_pack.py` (`_expand_item_row`) currently always writes
`"retired": false`, so the source rows can't retire an item. Until that is fixed, use
`"pool": "none"`. It has the same effect for drops; the pool value is not covered by the
stability diff.
Be careful with items that `starUnlocks` points at. The engine only grants star unlocks for standard,
non-retired items, so setting `pool: "none"` (or `retired`) on one of them silently stops that
unlock for players who haven't reached it yet.

## What needs an app release
- A new slot, bone, z bucket, rarity tier, pattern or ornament. These are the skeleton contract
  in `domain/content/Skeleton.kt` and the domain enums.
- A new achievement, because achievement ids are app code. The pack only chooses which item an
  achievement rewards.
- Any change to the pack format. Bump `schemaVersion`. The app reads only
  `content/meta → packs."<its schema>"`, so a schema-2 pack never reaches schema-1 apps.

## Rollback and fixes
- You can't go back to a lower version, because apps never move to one. To undo a change,
  publish the previous content again as a **new** version (e.g. restore the sources from git,
  then build, validate and upload).
- Don't remove items in the fix. The stability diff blocks it, and so does the on-device
  validator, which rejects packs that drop an id from the active pack.
- If an upload failed partway, re-run `upload_pack.py`. Meta is written last, so clients never
  saw the partial upload.
