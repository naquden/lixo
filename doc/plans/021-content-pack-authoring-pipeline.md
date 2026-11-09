# 021 — Authoring pipeline: port shapes/avatars to layers, build the bundled pack

**Type**: feature
**Depends on**: 020

## Goal
Build a Python pipeline that produces the content pack (019 schema) from source files. Do a one-time port of today's 8 shapes per slot (9 slots) and the 6 avatars into layer templates, and keep the deterministic item generator. The pipeline writes the **bundled snapshot** that the app seeds from, and the fixtures the tests use.

## Context
- The current generator is `tools/item_pipeline/generate_wardrobe_items.py`. It is deterministic (seeded) and produces 576 items: 9 slots, 8 styles, colourways, the rarity visual ladder (`_rarity_visuals`), star-unlock thresholds (`_global_star_unlock_thresholds`) and `ACHIEVEMENT_REWARDS`. Ids are `item_<slot>_<styleKey>_<colorwayKey>`. Its `--remote-legendaries` mode and `remote_items/` come from 015 and will be superseded.
- Handmade items are in `buildSeedWardrobeCatalog()` (`data/repository/Repositories.kt:877–1005`): the tutorial hat `item_hat_starter_explorer`, signature items, achievement and weekly items.
- The shapes to port are the `when(styleIndex)` branches in `Avatar3DController.kt` (see 019 Context for the line numbers). Each slot function's comments name its styles (e.g. HAT: 0 wizard cone, 1 crown, 2 knight dome, 3 beanie + pom-pom, 4 top hat, 5 cap, 6 hood, 7 horned dome).
- Body and avatars: `drawLowPolyAvatarScene` :1085–1342, plus the CAT/DOG ears.
- Pytest lives in `tools/tests/test_pipelines.py`, and upload helpers in `tools/content_pipeline/upload_content.py`.
- The bundled resource location is `composeApp/src/commonMain/composeResources/files/` (as with `3d_sample.glb`).

## Steps
1. Create `tools/content_pipeline/pack/` (stdlib only, plus PyYAML if it is already available; otherwise use JSON sources):
   - `templates/slots/<slot>.py`: `STYLES: list[StyleTemplate]`, in the same order as today's style indices. Each template is a function `(params) -> list[Layer dict]`. Port every `when` branch to SVG path strings in **bone-local au** (subtract the bone origin from today's scale=1 coordinates). Use the palette roles primary/secondary/accent, the same z buckets as today's draw order, `patternClip` on the main shape, and `block` shading where today's code uses `drawBlock`.
   - Port the body-modifying cases as layers: shorts are short LEGWEAR over the avatar's skin legs; the robe and dress go in TORSO_OVER; the horns are extra HAT layers; the tail styles are TAIL-bone layers.
   - `templates/avatars/<id>.json`: the 6 avatars (`avatar_cat`, `avatar_dog`, `avatar_knight`, `avatar_princess`, `avatar_wizard`, `avatar_witch`):
     - body parts (torso, head, arms, legs as block shapes) and ears (cat triangles, dog rounded ears)
     - a default tail for the non-humanoid avatars
     - `unlock`: cat is `default`, dog 12, knight 30, princess 55, wizard 90, witch 135 stars (from the `AvatarType` enum)
     - `humanoid`, `skinHex` and `defaultTintHex` (`0xFFF5A623`)
   - `catalog/generated.json`: write this by porting `generate_wardrobe_items.py` into `gen_catalog.py`, which emits item rows `{id, slot, rarity, subjectTheme, template: "<slot>:<styleIndex>", palette, pattern, ornament, scale, pool}` instead of Kotlin. Keep the ids, names and rarities byte-identical to today (`test_ids_names_rarities_unchanged` must still pass against the existing fixture).
   - `catalog/handmade.json`: the handmade items from `buildSeedWardrobeCatalog`, with `pool` set to achievement, setpiece or level as fits.
   - `drop_rules.json`: values from `BalanceConfig` and `Rarity.giftDropWeight`; `starUnlocks` from the generator's threshold curve; `achievementRewards` from `ACHIEVEMENT_REWARDS` plus the handmade ones.
   - `levelRewards`: generate them once by replaying today's `buildLevelRewardPool` + `levelRewardItemIdFor` for levels 1..`CAMPAIGN_MAX_ORDER` (160), then **freeze** the result in `drop_rules.json`. After this they are only ever appended to, never regenerated.
   - `strings/sv.json`: all item, avatar and event names and descriptions.
   - `events/`: empty for now (025 adds Jul 2026).
2. `build_pack.py`:
   - merge the sources and expand the templates into layers
   - set `version` from `--version` (default: published + 1) and write `out/content_pack.json` with canonical formatting (sorted keys, no whitespace)
   - copy it to `composeApp/src/commonMain/composeResources/files/content_pack.json`
   - write `tools/tests/fixtures/mini_pack.json` (one item per slot, all avatars)
3. `validate_pack.py`: the same rules as `ContentPackValidator` (019), plus the **stability diff** against `published/pack_v<N>.json` (the latest one):
   - no item, avatar or event id removed
   - existing `levelRewards` entries, `achievementRewards` and `starUnlocks` unchanged (only additions)
   - an item's `slot` never changes
   - `version` > the published version

   Exit non-zero with a list of errors. `--against <file>` overrides the published file.
4. `tools/content_pipeline/pack/README.md`: the generate → build → validate → preview → upload flow. 024 adds upload and 025 adds the holiday example.
5. Remove `tools/item_pipeline/remote_items/` and the `--remote-legendaries` mode. The `generate_wardrobe_items.py` Kotlin output is deleted in 022; for now, mark it deprecated in its docstring.
6. Preview from a file: extend `ContentPreviewScreen` (020) to also load `<filesDir>/content_pack_preview.json` when it exists, so the user can run `adb push out/content_pack.json /sdcard/...` and then `run-as` to copy it in. Document the exact commands in the README.

## Tests / verification
pytest (`tools/tests/test_pipelines.py`):
- deterministic: two builds are byte-identical
- every template produces paths that pass the bounds and caps checks
- ids, names and rarities are unchanged against the existing fixture
- `levelRewards` covers levels 1..160 and every one points at an existing item
- stability diff: removing an id, changing a levelReward, or changing a slot each fail; adding an item passes

commonTest:
- `BundledPackValidTest`: reads the bundled `content_pack.json`; `ContentPackValidator` returns Accepted with **0 skips**, 6 avatars and ≥ 580 items.

Instrumented:
- `se.lixo.app.verify.BundledCatalogRenderTest`: loads the bundled pack and renders `ItemPreview` for every item in batches with no exception; renders each avatar fully equipped with style 0–7 items of each slot; saves screenshots to `temp/verify021/`.
- Also capture the **old** renderer (`Avatar3DViewport`, still present) with the same items into `temp/verify021/old/`, so the port can be compared side by side. The opus review stage looks at the pairs.

Run:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
```

## Open questions
- PyYAML vs JSON for the sources: use JSON unless PyYAML is already installed (keep it stdlib-only like the existing pipelines).
