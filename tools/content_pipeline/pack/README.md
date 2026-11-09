# Content pack authoring pipeline

Builds the app's bundled content pack (`doc/content/content-pack.md`, schema v1) from source
files in this directory: hand-authored style templates (`templates/`), the deterministic item
generator (reused from `tools/item_pipeline/generate_wardrobe_items.py`), and a few hand-authored
catalog/string files.

**Publishing to installed apps:** follow the runbook in [`doc/content/publishing.md`](../../../doc/content/publishing.md).

## Flow

1. **Generate** — `python3 tools/content_pipeline/pack/gen_catalog.py`
   Writes `catalog/generated.json` (576 items) and `strings/generated.sv.json` from
   `generate_wardrobe_items.generate_catalog()`. Run with `--init-drop-rules` **once** to also
   write `drop_rules.json` (star-unlock thresholds, achievement rewards, level rewards 1..160,
   onboarding levels, rarity weights, pity). A second `--init-drop-rules` run exits 1: this file
   is frozen (see below).

2. **Build** — `python3 tools/content_pipeline/pack/build_pack.py --version N`
   Expands every item/avatar's template into layers, merges the sources, and writes:
   - `out/content_pack.json` (canonical: sorted keys, compact separators, UTF-8, no trailing
     newline)
   - `composeApp/src/commonMain/composeResources/files/content_pack.json` (byte-identical copy;
     this is what the app bundles)
   - `tools/tests/fixtures/mini_pack.json` (all 6 avatars, one item per slot)

   `--version` defaults to the latest `published/pack_v<N>.json` + 1, or 1 if none exist.

3. **Validate** — `python3 tools/content_pipeline/pack/validate_pack.py [PACK] [--against FILE]`
   A Python port of `ContentPackValidator`/`ContentPaths`. Every rule violation is reported as an
   error (unlike the Kotlin validator, which can skip individual bad entries): the bundled pack
   must have **0** issues. Also runs the **stability diff** against the latest
   `published/pack_v<N>.json` (or `--against`):
   - no item/avatar/event id removed
   - `levelRewards`/`achievementRewards`/`starUnlocks` entries never change, only grow
   - an item's `slot` never changes
   - `version` strictly increases
   `no published pack — stability diff skipped` and exit 0 is expected until the first pack is
   published (024).

4. **Preview from a file** — `ContentPreviewScreen` (020/021) loads
   `<filesDir>/content_pack_preview.json` when present, before falling back to the bundled pack:
   ```
   adb push out/content_pack.json /data/local/tmp/content_pack_preview.json
   adb shell run-as se.lixo.app sh -c 'mkdir -p files && cp /data/local/tmp/content_pack_preview.json files/content_pack_preview.json'
   # ... open the debug screen; subtitle reads "förhandsfil vN" ...
   adb shell run-as se.lixo.app rm files/content_pack_preview.json   # clears it
   ```

5. **Upload** — `python3 tools/content_pipeline/pack/upload_pack.py [--validate-only | --dry-run]`
   Pushes `out/content_pack.json` to Firestore so already-installed app builds can hot-swap it
   without a release, via `ContentRepository.sync` (`doc/plans/024-cloud-content-sync.md`).
   - **Prerequisites**: `firebase login` (for the upload itself) and, once ever,
     `firebase deploy --only firestore:rules` (so the app's public reads of `content`/`content_chunks`
     are allowed).
   - Re-runs step 3's validation + stability diff first; any failure aborts before anything is
     written, uploaded or published.
   - **Firestore layout**: `content/meta` holds `{ packs: { "1": { version, chunkCount, sha256,
     bytes, publishedAt } } }` (keyed by `schemaVersion`, so a future schema-2 app and today's
     schema-1 app can coexist); `content_chunks/{schema}_{version}_{i}` holds one ~900 KB slice
     (`{ data: "<chunk text>" }`) whose concatenation is byte-identical to the uploaded pack.
   - **Write order**: every chunk is committed before the `content/meta` write (its own separate
     `documents:commit` each), so a client never observes a meta bump without its chunks. The
     meta write uses an `updateMask` scoped to this schema's entry only.
   - `--validate-only` stops after validation (no network, no writes). `--dry-run` prints every
     write (name + byte size, never the chunk data) and uploads nothing.
   - On success, `out/content_pack.json` is copied byte-identical to
     `published/pack_v<N>.json` -- **commit this file to git**: it's the stability diff's next
     baseline. Chunk docs for versions older than the last 3 published are then deleted
     (a pruning failure is only a warning, not a failed upload).

## Frozen data — append only

- `drop_rules.json`'s `starUnlocks`, `achievementRewards` and `levelRewards` are **persisted
  facts about already-shipped progression**: profiles' saved unlocks and the reward a given
  level has always given depend on them never changing retroactively. Only ever append.
- Style/colorway display strings in `tools/item_pipeline/generate_wardrobe_items.py` (`SLOTS`,
  `COLORWAYS`, `LEGENDARY_COLORWAYS`) are persisted in item ids — never rename, only append.
- `catalog/handmade.json` rows are likewise append-only (never remove or change `slot`).

## Adding a new style (append only, never renumber)

1. Add a new `StyleTemplate` to the end of the relevant `templates/slots/<slot>.py`'s `STYLES`
   list (so its `styleIndex` is the next free index — never reorder existing styles).
2. Re-run `gen_catalog.py` (without `--init-drop-rules`), `build_pack.py --version N+1`,
   `validate_pack.py`.

## Adding a new handmade item

1. Append a row to `catalog/handmade.json` and its `<id>.name`/`<id>.desc` to `strings/sv.json`.
2. If it should star-unlock or reward an achievement, add it to `gen_catalog.py`'s
   `HANDMADE_STAR_UNLOCKS`/`HANDMADE_ACHIEVEMENT_REWARDS` **before** the next `--init-drop-rules`
   run — once `drop_rules.json` is frozen, hand-edit it (append only) instead of regenerating.
3. `build_pack.py --version N+1`, `validate_pack.py`.

## Holiday special (events)

A holiday special ("Jul 2026", `event_jul_2026`) is defined entirely in the content pack: a time
window, event-only items, a boosted drop chance, a themed chest and a banner. Running one is an
upload, not an app release.

**File formats**

- `events/<id>.json` — one `ContentEvent` object per file (not an array): `id`, `nameKey`,
  `start`/`endExclusive` (`YYYY-MM-DD`, local calendar dates), `tz` (always `"local"`),
  `itemIds`, `chestTheme` (`bodyHex`, `trimHex`, `ornament`), `banner` (`titleKey`, `bgHex`,
  `fgHex`), `dropBoost` (`eventItemChance`, `presentGiftChance`), `afterEnd` (`retire` or
  `joinStandard`). All `events/*.json` files are loaded, sorted by filename.
- `catalog/<id>.json` — an array of item rows, same as `handmade.json`, except each row ships its
  own `layers` array directly (hand-authored, no `template` key -- and `palette.accent` must be
  given explicitly, since there's no template to derive it from) plus `"pool": "event"` and
  `"eventId": "<event id>"`. Every `catalog/*.json` file other than `handmade.json`/
  `generated.json` is loaded (sorted by filename) and appended after them, so existing items'
  indices and `mini_pack.json` stay unchanged.
- `strings/<id>.sv.json` — the event's `nameKey`/`banner.titleKey` plus every item's
  `<id>.name`/`<id>.desc`. Every `strings/*.sv.json` file other than `sv.json`/
  `generated.sv.json` is merged in (sorted by filename), with a collision check across all of
  them.

**Semantics**

- An event is active when `start <= today < endExclusive`, evaluated against the local calendar
  date (`EventEngine`/`TrustedClock`, never UTC).
- While active, `dropBoost.eventItemChance`/`presentGiftChance` give the Flitkista chest/present
  gift a chance to roll from the event's item pool first, before the standard roll.
- After `endExclusive`: `afterEnd: "retire"` (the default) means the event's items never drop
  again; `afterEnd: "joinStandard"` means they become ordinary chest-eligible items. Either way,
  an owner keeps an event item forever.

**Steps**

1. Add the catalog file (`catalog/<id>.json`, hand-authored `layers`), the event file
   (`events/<id>.json`) and the strings file (`strings/<id>.sv.json`).
2. `python3 tools/content_pipeline/pack/build_pack.py --version N`, then
   `python3 tools/content_pipeline/pack/validate_pack.py`.
3. Check the preview screenshots: `ContentPreviewScreen` via the preview-file flow (step 4 of the
   main Flow above), or `se.lixo.app.verify.ItemCatalogScreenshotTest`.
4. `python3 tools/content_pipeline/pack/upload_pack.py --dry-run`, then the real upload.
5. Commit the sources (`events/`, the new `catalog/`/`strings/` files), the bundled snapshot
   (`composeApp/.../content_pack.json`) and `published/`.

`published/` holds every previously-uploaded pack (`pack_v<N>.json`), the stability diff's
baseline; it starts empty and `upload_pack.py` writes the first entry after the first successful
upload.
