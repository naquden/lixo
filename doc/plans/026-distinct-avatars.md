# 026 — Distinct avatars: own silhouette, colours and signature parts per avatar

**Type**: feature
**Depends on**: 021

## Goal
All 6 avatars (cat, dog, knight, princess, wizard, witch) must look **substantially** different, even when fully equipped. Each avatar gets its own body proportions (silhouette), default colours, signature parts (hair, beard, muzzle, skirt…) and idle pose. Equipped items stretch to fit each body.

Nothing is deployed: no backwards compatibility, no `schemaVersion` bump, no migration. Change the schema, validator and pack in place.

## Context
- Today every avatar JSON (`tools/content_pipeline/pack/templates/avatars/avatar_*.json`) has the same `skinHex #4A90E2` and `defaultTintHex #F5A623`. All of them use `"body": "standard"`, the 4 humanoids have no extra parts, and cat/dog differ only by ears (`body.py` `EARS`), which every hat covers.
  - The role names are confusing: role `skin` is the **shirt** colour (torso/arms, `body.py:18-26`), and role `tint` is the body colour (head/hands/legs).
- `build_pack.py:182-187` assembles parts: `STANDARD_BODY` + optional `EARS[...]` + optional `DEFAULT_TAIL`.
- Schema: `ContentAvatar` (`composeApp/.../domain/content/ContentPack.kt:53`) already has:
  - `bones: Map<Bone, BoneOverride>` (size/offset, `:80`), validated at `ContentPackValidator.kt:314-327`
  - `pose: AvatarPose` (headBob/breath/armSwing/tailWag, `:86`)
- Renderer: `boneLayoutAu` (`ui/components/avatar/LayerGeometry.kt:85`) already lays out bones from the effective sizes. But `boneMatrix` (`:294`) scales every layer uniformly by `pxPerAu`, so item layers authored in the standard bone box (e.g. an 80×80 shirt) do **not** fit a resized torso. This is the key gap.
- Avatar parts may only use the `skin/tint/eye/outline` roles (`ContentPackValidator.kt:46`), and colours resolve in `resolveRoleColor` (`LayerGeometry.kt:349`, avatar draw list `:386-397`). So hair or a beard can't have its own colour today.
- Items hide avatar parts with `hidesParts`. Today only PANTS → `pants`, SHOES → `shoes` and TAIL → `tail` do this, so any part with another name survives every outfit.
- The face (eyes and smile) is drawn in HEAD-local 56×56 au (`LayerRenderer.kt:138-150`).

## Steps
1. **Item layers stretch to the avatar's bones** (`LayerGeometry.kt`):
   - Add `itemScale: Offset` to `BoneTransform` = effective size / `STANDARD_BONE_BOX` size, per bone.
   - In `layerMatrix`, after `m.scale(pxPerAu, pxPerAu)`, also apply `m.scale(itemScale.x, itemScale.y)` for **item** layers only. Avatar part layers are authored in the effective box and stay unscaled. Pass a flag on `PlacedLayer` (it already knows whether it comes from an item or a part).
   - Apply the same idea in `faceMatrix`: scale the 56×56 face to the effective HEAD size, so eyes and smile stay centred on bigger or taller heads.
   - `ItemPreview` (default avatar, standard bones) is unchanged.
2. **Avatar palette**:
   - Add `palette: AvatarPalette? = null` (`primary`, `secondary`, `accent` hex strings) to `ContentAvatar`.
   - In the validator, allow PRIMARY/SECONDARY/ACCENT on avatar part layers only when `palette != null`, and check that the hex strings parse.
   - In the avatar draw list (`LayerGeometry.kt:386-397`), resolve those roles from the avatar palette.
3. **Per-avatar body templates** (`body.py`): replace the single `STANDARD_BODY` with `body_parts(sizes) -> parts`. It draws torso/arms/hands/legs/pants/shoes/head as block shapes filling each bone's **effective** size, so one function serves every proportion. The avatar JSON gets `"bones": {...}` (written into the pack as `ContentAvatar.bones`), `"palette"`, `"pose"` and `"extras": [<signature part ids>]`. In `build_pack.py:182`, build the parts from `body_parts(bones)` + ears + tail + extras.
4. **The six avatars** (sizes in au as w×h; standard torso 80×80, head 56×56, arm 38×80, leg 40×80):

   | avatar | silhouette (bones) | shirt (`skinHex`) / body (`defaultTintHex`) | palette p / s / a | signature parts (z bucket) | pose |
   |---|---|---|---|---|---|
   | cat | chibi: head 66×60, torso 70×62, legs 34×58, arms 32×62 | #FFFFFF / #F5A623 orange | #FFFFFF muzzle, #F48FB1 nose/inner ear, #C77700 stripes | big triangle ears with pink inside (HEAD), white muzzle + pink nose + whiskers (FACE), tabby stripes on head (HEAD, order 2), striped long curled tail (TAIL) | tailWag 1.3, headBob 1.2 |
   | dog | stocky: head 62×56, torso 90×72, legs 42×66, arms 40×70 | #E57373 red / #C08A5A brown | #F3E0C8 snout, #3E2723 nose/spot, #EF5350 tongue | long floppy ears hanging to the jaw (HEAD_OVER, sides), snout patch + black nose + tongue (FACE), eye spot (HEAD, order 2), short wagging tail (TAIL) | tailWag 1.8, armSwing 1.2 |
   | knight | broad, tall: torso 100×88, arms 46×86, legs 44×84, head 56×54 | #7F8C9A steel / #F2C29B peach | #6D4C41 hair, #B0BEC5 steel, #C0392B red | shoulder pauldrons sticking out past the arms (ARM, on ARM_L/R, mirrored), breastplate rivets + red sash (TORSO, order 2 — hidden under shirts, the pauldrons still show), brown sideburns + square jaw shadow (HEAD_OVER/FACE) | breath 0.8, headBob 0.7 |
   | princess | slim, tall: torso 66×74, arms 28×78, legs 30×88, head 54×58 | #F48FB1 pink / #F2C29B peach | #F6C945 gold hair, #F06292 rose, #FFF59D tiara | long gold hair falling behind the shoulders to the waist (BACK) + side locks (HEAD_OVER), a flared A-line skirt **as her `pants` part** (so PANTS items still replace it), eyelashes + rosy cheeks (FACE), small tiara (HEAD_OVER; a hat covers it) | headBob 1.1, armSwing 0.7 |
   | wizard | tall and thin: torso 70×94, arms 30×90, legs 34×68, head 54×60 | #5E35B1 purple / #F2C29B peach | #ECEFF1 beard, #FFD54F stars, #3949AB robe hem | huge white beard from the chin to the belt (HEAD_OVER, on the HEAD bone, extending ~60 au down over the torso, so it shows over shirts), bushy eyebrows + long nose (FACE), an ankle-long robe as his `pants` part with a star hem | headBob 0.6, breath 0.7, armSwing 0.6 |
   | witch | thin, elongated head: torso 64×80, arms 28×82, legs 30×84, head 50×64 | #2E7D32 dark green / #A5D6A7 pale green | #263238 hair, #8E24AA purple, #FF7043 stockings | wild spiky black hair, wide behind the head and shoulders (BACK) + strands (HEAD_OVER), long hooked nose + pointy chin + wart (FACE), striped stockings as her `legs` part and a ragged short skirt as her `pants` part | headBob 1.3 (jittery), armSwing 1.1 |

   Keep every path inside `expandedBoneBox` (1.5× overhang). The beard and hair must use the overhang deliberately. Name the parts so no item `hidesParts` them (except `pants`/`legs`/`tail`, as noted).
5. Run `build_pack.py` to rebuild `content_pack.json` and `mini_pack.json`. The stability diff should pass: ids, unlocks and drop rules are unchanged.
6. Update `doc/content/content-pack.md`: avatar `palette`, `bones`, `extras`, and that item layers stretch per bone.

## Tests / verification
pytest (`tools/tests/test_pipelines.py`):
- every pair of avatars differs in (`skinHex`, `defaultTintHex`) **and** in `bones`
- every avatar has ≥ 2 signature parts beyond the body
- every part path fits its effective bone box (the bounds check reused from 021)
- the build is still byte-deterministic, and the stability diff passes against `published/pack_v1.json`

commonTest:
- `ContentPackValidatorTest`: palette roles are accepted on avatar parts when the avatar has a palette and rejected when it doesn't; a bad palette hex is rejected
- `LayerTransformTest`: an item layer on a 100×88 torso is scaled (1.25, 1.1); an avatar part layer is not; the face on a 66×60 head is scaled
- `BundledPackValidTest`: still Accepted with 0 skips, 6 avatars
- new `AvatarSilhouetteTest`: `boneLayoutAu` gives each avatar a distinct overall bounds (width/height), and a fully equipped style-0 avatar's shirt union covers its TORSO bone rect (items fit)

Instrumented (emulator-5554):
- `se.lixo.app.verify.BundledCatalogRenderTest`: the existing tests (`everyAvatar_rendersBare`, `everyAvatar_wearsStyles0to7_inEverySlot`, `everyItem_rendersInItemPreview`) run green and produce 6 bare + 48 equipped + 20 item-sheet PNGs in `lixo_screenshots/verify021/`. The review stage looks at the bare and equipped shots to confirm each avatar is recognisable, with items sitting on the new bodies (no gaps, no floating hats).

```
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
```

## Open questions
