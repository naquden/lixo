# 028 — Item previews on standard bones with a neutral ghost body

**Type**: bug

**Goal**: `ItemPreview` should draw items at their authored (standard-bone) proportions, as plan 026 step 1 promised. It currently stretches every item to the cat's chibi bones and draws the cat's whiskers/stripes as the ghost body.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/components/avatar/LayerGeometry.kt:451` `buildItemDrawList` uses `content.defaultAvatar` (the cat, order 0), and `ItemDrawList.avatar` is that avatar.
- `LayerGeometry.kt:505` `previewFrame` and `LayerRenderer.kt:282` `drawItemPreview` both call `boneTransforms(drawList.avatar, …)`. Since 026 every bundled avatar overrides its bones (cat: HEAD 66×60, TORSO 70×62, LEG 34×58, ARM 32×62), so no avatar has standard bones and every preview gets the cat's itemScale.
- Ghost layers (`buildItemDrawList`, `ghostBody == true`) use all of the cat's parts, including signature parts (ears, muzzle, stripes, tail). They are authored in the cat's effective bone sizes, and part layers are not scaled by `layerMatrix` (`LayerGeometry.kt:331`, only `p.item != null` is scaled).
- The pipeline's base body parts are `avatar_body.BODY_PART_NAMES` (torso, arms, hands, legs, pants, shoes, head). See `tools/tests/test_pipelines.py:538`.
- Docs: `doc/plans/026-distinct-avatars.md:28` ("ItemPreview (default avatar, standard bones) is unchanged") and `doc/content/content-pack.md:429` ("A null avatar keeps the old, unshrunk scale (ItemPreview's default avatar)").
- Found by the 026 review (LOW).

## Steps
1. In `buildItemDrawList`, keep `avatar = content.defaultAvatar` for skin/tint/palette, but set `ItemDrawList.avatar = avatar.copy(bones = emptyMap())`. Then item layers get `itemScale = (1, 1)` in both `previewFrame` and `drawItemPreview`, and nothing else has to change for item layers.
2. Ghost body: only use parts whose `part` is in the base body set `{torso, arms, hands, legs, pants, shoes, head}`. Add a `BASE_BODY_PARTS` constant next to `buildItemDrawList` with a comment that it mirrors `avatar_body.BODY_PART_NAMES`. Still apply the `hidesParts` and `placedBones` filters.
3. Ghost layers are authored in the default avatar's effective bone sizes, but are now drawn on standard bones. Add `val ghostScale: Map<Bone, Offset>` to `ItemDrawList`, holding `STANDARD_BONE_BOX[bone] / effectiveBoneSize(defaultAvatar, bone)` per axis for every bone (1,1 where the size is zero). Add an optional `partScale: Offset? = null` parameter to `layerMatrix`. When it is non-null and `p.item == null`, apply `m.scale(partScale.x, partScale.y)` where item layers apply `itemScale`. Mirror that layer in the *original* effective width (`effectiveBoneSize(defaultAvatar, bone).width`, passed as an extra `mirrorWidthAu` parameter or folded into a small `GhostLayer` wrapper), so the flip happens before the scale. Pass both through `drawLayerList` for the ghost list only (add an optional `ghostScale: Map<Bone, Offset>?` and `ghostMirrorWidth` lookup to `drawLayerList`). Item drawing and avatar drawing are unchanged.
4. Update `doc/content/content-pack.md` (Preview framing / Avatar frame) and the line in `doc/plans/026-distinct-avatars.md:28`: previews use standard bones and a base-body ghost rescaled to standard boxes.

## Tests / verification
commonTest (`LayerTransformTest.kt`):
- new `itemPreviewUsesStandardBones`: with `TestPackFixture` (or a bundled-pack load) where the default avatar has a non-standard TORSO, `buildItemDrawList(content, shirt).avatar.bones` is empty, and the shirt's placed bounds under `boneTransforms(drawList.avatar, REST_POSE, 0f, RigFrame(Offset.Zero, 1f))` equal the authored layer bounds (scale 1).
- new `ghostBodyIsBasePartsAtStandardSize`: for a `ghostBody = true` torso item, the ghost layers contain no signature parts (`partName` ∉ base set), and the ghost torso's placed bounds equal the standard TORSO box size (80×80).
- the existing `previewFramingFitsBoundsWithMargin` stays green.

Instrumented (emulator-5554, or R5CXC0681EM when attached):
- `se.lixo.app.verify.BundledCatalogRenderTest#everyItem_rendersInItemPreview` (run the whole class; all 3 green). Review the `items_*.png` sheets: items have their authored proportions (compare with the 021-era sheets in `temp/verify/lixo_screenshots/verify021/items_*.png`), and ghost bodies show no whiskers or stripes.

```
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
```

## Open questions
- An alternative is to keep chibi-cat previews, since the cat is the avatar a new player wears, and only fix the docs. This plan assumes standard proportions, because the wardrobe shows items for every avatar.
