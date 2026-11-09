# 029 — Dog tail visible behind the arm

**Type**: bug

**Goal**: The dog's "short wagging tail" signature part should be visible in the bare and equipped renders. It is currently drawn entirely behind the dog's left arm.

## Context
- `tools/content_pipeline/pack/templates/avatars/extras.py:159` `dog_tail`: `poly([(0, 6), (8, -24), (18, -22), (12, 8)])`, so x is 0..18 au.
- TAIL bone origin is `(torsoRight - 6, legsTop - 14)` (`composeApp/src/commonMain/kotlin/se/lixo/app/ui/components/avatar/LayerGeometry.kt:136`). ARM_L starts at `torsoRight` and the dog's ARM_L is 40 au wide (`bones.ARM_L.size = [40, 70]`). At yaw 0 the tail therefore needs x > 6 + 40 = 46 au to stick out past the arm.
- `cat_tail` (`extras.py:99`) reaches x = 70 with a vertical tip, and it is visible.
- `tools/tests/test_pipelines.py:538` counts `tail` as one of the dog's signature parts, but nothing checks that it is visible.
- Evidence: `temp/verify/lixo_screenshots/verify026/bare_avatar_dog.png` (no tail visible). Found by the 026 review (LOW).

## Steps
1. Rewrite `dog_tail(sizes)` in `extras.py` so that it is relative to the arm width: `aw = sizes["ARM_L"][0]`. Use a stubby tail that goes out past the arm and tips up:
   `join(round_rect(0, 0, aw + 28, 12, 6), poly([(aw + 18, 4), (aw + 24, -18), (aw + 34, -16), (aw + 30, 8)]))`, fill `tint`, shade -0.22, flat shading (as now). Keep the bucket (`TAIL`), z order and part name `tail`.
   The original `aw + 18` / `aw + 24` shape only clears the arm at rest (wag 0): `BundledCatalogRenderTest` captures each screenshot 500 ms after (re)composition, when the dog's `tailWag` 1.8 idle animation has rotated the tip to about -29°, and at that pose only ~45% of that shape's tip was visible past the arm — so the shape is moved 10 au further out (bar to `aw + 28`, tip to `aw + 34`) to stay visible through most of the wag cycle.
2. Rebuild the bundled pack: `python3 tools/content_pipeline/pack/build_pack.py` (writes `composeApp/src/commonMain/composeResources/files/content_pack.json`), then run `validate_pack.py`. Bounds/caps must still pass.

## Tests / verification
pytest (`tools/tests/test_pipelines.py`):
- new `test_tails_clear_left_arm`: for every built avatar with a `tail` part, the max x of the tail layers' path bounds (use the same path-bounds helper as `test_every_template_passes_bounds_and_caps`) is greater than `6 + bones.ARM_L.size[0] + 4`.
- the existing avatar tests (`test_avatars_pairwise_distinct`, `test_avatars_have_two_signature_parts`, `test_built_avatars_validate`) stay green.

commonTest: `BundledPackValidTest` still Accepted, 0 skips, 6 avatars.

Instrumented (emulator-5554, or R5CXC0681EM when attached):
- `se.lixo.app.verify.BundledCatalogRenderTest` (all 3 green). Review `bare_avatar_dog.png` (tail tip visible to the right of the left arm). Note: in `avatar_avatar_dog_style*.png` every slot is equipped, including TAIL, and `SLOT_HIDES_PARTS` hides the dog's own `tail` part whenever a TAIL item is equipped — so those renders show the equipped item's tail, not this fix, and only `bare_avatar_dog.png` demonstrates it.

```
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
```

## Open questions
