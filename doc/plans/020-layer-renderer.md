# 020 — Generic layer renderer for avatars and items

**Type**: feature
**Depends on**: 019

## Goal
Add one generic Canvas interpreter that draws any avatar and its equipped items from content pack layers (019), keeping today's pseudo-3D look: block shading, yaw parallax, idle pose, patterns, ornaments and the legendary aura. Also add a single-item preview and a debug preview screen. The old `Avatar3DController` stays in place until 022 switches the app over.

## Context
- The current renderer is `ui/components/avatar3d/Avatar3DController.kt`. These pieces should be reused as functions, extracted or moved into the new file:
  - `drawBlock` :244: the pseudo-3D brick with a yaw side face, gradient and highlight. It becomes the `shading = block` implementation for an arbitrary `Path`. Generalise it: draw the side face as the path translated by `depth*sign(yaw)`, darkened, behind the path.
  - `drawPatternFill` :289–378 (STRIPES/DOTS/STARS/ZIGZAG/CHECKER, clipped to the path) and `drawOrnamentOverlay`/`drawGlowHalo` :394–472 (TRIM/GEM/GLOW/SPARKLE, LEGENDARY = glow + sparkle).
  - `withItemDecoration` :476: its order is halo → shape → pattern → ornaments.
  - `computeProceduralIdlePose` :79 (head bob, torso breath, arm swing, tail wag, aura phase), and `yawParallax = sin(yaw)*14*scale` with turntable drag `yaw += drag*0.45f` (:169).
  - The shadow, stage ring and legendary aura particles at :1111–1141.
  - The face (eyes and smile by index) at :1309–1336. Keep the face as app code (FACE bucket): avatars only choose `eyeStyles`/`smileStyles`.
- Public API used by screens: `Avatar3DViewport(appearance, catalogById, modifier, interactiveRotation)` :134 and `WardrobeItemPreview(item, modifier)` :195. They are called from WardrobeScreen, CampaignMapScreen, AvatarMapScreen, ProfileSelectAndTutorialScreen, celebration `ItemReveal.kt` and `UnlockDialogs.kt`.

## Steps
1. Create `ui/components/avatar/LayerRenderer.kt` (pure DrawScope functions):
   - `fun boneTransforms(avatar: ContentAvatar, pose: ProceduralIdlePose, yaw: Float, canvasSize: Size): Map<Bone, BoneTransform>`. Each `BoneTransform` holds an origin in px, a scale (avatar bone size / standard box), a rotation and pivot (arms around the shoulder, tail wag around its root), a breath scaleY for TORSO, and parallax px. HAND_L/HAND_R inherit the arm rotation. Fit the whole avatar into the canvas the same way `drawLowPolyAvatarScene` computes `scale` today.
   - `fun DrawScope.drawAvatar(content: ActiveContent, appearance: AvatarAppearanceV2, pose, yaw, auraPhase)`:
     1. Collect layers from the avatar parts (minus the `hidesParts` of equipped items) and from the equipped items (unknown ids skipped silently).
     2. Expand `mirror = x` layers onto the opposite L/R bone.
     3. Sort by `(z.ordinal, order)` and draw each layer.
     4. Draw the FACE bucket with the existing face code.
     5. Draw the shadow and stage first; draw the aura when any equipped item is LEGENDARY.
   - Each item's layers are drawn inside one decoration group (halo first, then shape, then pattern on the `patternClip` layers, then ornaments on the union bounds), keeping the existing look.
   - `fun DrawScope.drawItemPreview(content, item, auraPhase)`: draws only the item's layers at rest with yaw 0.35, fitted to `content.itemBounds(id)` with a 10% margin. With `preview.ghostBody`, the default avatar's matching body parts are drawn underneath at alpha 0.25.
   - Colours: resolve palette roles, then apply `shade` (lerp towards white or black).
2. `ui/components/avatar/AvatarViewport.kt`: composables `AvatarViewport(content: ActiveContent, appearance, modifier, interactiveRotation)` and `ItemPreview(content, item, modifier)`, with the same animation clock and turntable gesture as today. `ItemPreview` only animates for SPARKLE or LEGENDARY.
3. Temporary model: until 022 replaces it, define `AvatarAppearanceV2(avatarId: String, tintHex: Long?, eyeIndex, smileIndex, equipped: Map<EquipmentSlot, String>)` in `domain/content/`. 022 renames it to `AvatarAppearance` and deletes the old one.
4. `ui/screens/debug/ContentPreviewScreen.kt`: an avatar selector row; a grid of every item shown with `ItemPreview`; tapping an item equips it on a large `AvatarViewport`. Load from a given `ActiveContent`. Register it only when `PlatformFeatureFlags` (or the existing debug flag) is on. 021 adds loading a pack from a file.
5. Performance: paths come from `content.parsedPath(layer)` (019) and are translated or scaled with `withTransform`, never re-parsed. Cache the mirrored copies of paths too.

## Tests / verification
commonTest:
- `LayerTransformTest`:
  - an avatar bone twice the standard size scales item layers ×2
  - a mirrored layer lands on the opposite bone with x flipped
  - HEAD parallax at yaw 90° equals 14·scale
  - arm rotation pivots on the shoulder
  - preview framing fits the bounds with the margin
- `ZOrderTest`: a shuffled list of layers sorts by (bucket, order); `hidesParts` removes the ears part; an unknown equipped id is skipped without throwing.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/`, ScreenshotTest pattern: `createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme {}`, using the 019 `test_pack.json` loaded through `ActiveContent.from`):
- `LayerRendererScreenshotTest`: renders `AvatarViewport` with all slots equipped plus one `hidesParts` helmet, and `ItemPreview` for each slot; asserts the content description exists; saves `onRoot().captureToImage()` to `temp/verify020/`.
- `ContentPreviewScreenTest`: the grid shows every fixture item name (`onNodeWithText`); tapping one equips it on the viewport (content description changes).

Run:
```
ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.LayerRendererScreenshotTest,se.lixo.app.verify.ContentPreviewScreenTest
```

## Open questions
- None.
