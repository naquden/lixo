# 033 — Rotate the avatar up to 90° to each side so back gear (capes, backpacks) can be seen

**Type**: feature

## Goal

Dragging the avatar sideways turns it up to 90° left or right, but not further. At the turned
angles, SHOULDERS_BACK gear (capes, backpacks, quivers, balloons) must clearly show beside the
body. Today most of it can never be seen. There is no back view and no 360° rotation.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/components/avatar/AvatarViewport.kt:43–67`:
  `yawDeg = (yawDeg + dragAmount * 0.45f) % 360f`, which has no limit.
- `LayerGeometry.kt:244–290` `boneTransforms`: `yawPx = sin(yaw) * 14f * pxPerAu`. Each bone is
  shifted by `yawPx * DEFAULT_PARALLAX[bone]`. Those factors (`domain/content/Skeleton.kt:85–96`)
  are TORSO 0.5, HEAD 1.0, ARM_* and HAND_* 0.5, LEG_* 0.3, TAIL -0.4.
- `LayerGeometry.kt:315–316`: the parallax for a layer comes from `layer.parallax * t.yawPx` when the
  layer sets one, otherwise from `BACK_BUCKET_PARALLAX (-0.6) * t.yawPx` for `ZBucket.BACK` layers.
- All 66 SHOULDERS_BACK items in the bundled pack are drawn on bone `TORSO` with z `BACK` and no
  `parallax` of their own. Their x ranges, in torso-local au where the torso is 0–80 wide and the
  arms add 38au on each side (so the body spans about -38 to 118), look like this:
  - capes: -30 to 110
  - cloaks: -8 to 88
  - balloons: 40 to 92
  - quivers: 58 to 80
- **Root cause:** from the front, every one of these lies inside the body's outline. The most the
  cape can move against the torso is (0.6 + 0.5) × 14 = 15.4au, which only helps capes a little and
  never brings backpacks or quivers out. Because yaw wraps around 360°, dragging further makes the
  sine go back down, so the angle can't be held where the shift is largest.
- `LayerRenderer.kt:106`: BLOCK-shading depth follows the sign of `t.yawPx`. This stays as it is.
- Callers with `interactiveRotation = true` are `WardrobeScreen.kt:236`, `AvatarMapScreen.kt:525`
  and `:1176`. `ContentPreviewScreen` and the screenshot tests use the default, `true`. Callers that
  pass `false` stay at yaw 0 and keep rendering exactly as they do today.
- No content changes. Content must not be generated or edited (memory rule).

## Steps

1. **Limit the yaw** (`AvatarViewport.kt`): change the drag to
   `yawDeg = (yawDeg + dragAmount * 0.45f).coerceIn(-MAX_YAW_DEG, MAX_YAW_DEG)` with
   `MAX_YAW_DEG = 90f`. The avatar stays where it's released, with no spring back (see Open questions).
   Add a parameter `initialYawDeg: Float = 0f`, clamped the same way, for tests and the debug preview.
2. **Separate peek for back gear** (`LayerGeometry.kt` / `Skeleton.kt`):
   - Add `const val BACK_PEEK_AU = 48f` in `Skeleton.kt`. This is how far, in au, `BACK`-bucket
     layers slide against the torso at full yaw.
   - Add `backPeekPx: Float = -sin(yaw) * BACK_PEEK_AU * frame.pxPerAu` to `BoneTransform`, filled
     in `boneTransforms`. It moves in the opposite direction to the body: when the body turns
     right, the back gear peeks out on the left.
   - At `:315–316`, change `layer.z == ZBucket.BACK -> BACK_BUCKET_PARALLAX * t.yawPx` to
     `t.parallaxPx + t.backPeekPx`. The gear follows its bone (the torso) and peeks on top of that,
     so it stays attached to the body. Remove `BACK_BUCKET_PARALLAX`, or keep it as a deprecated
     alias if other code uses it (grep first).
   - Leave the body parallax (`14f`, `DEFAULT_PARALLAX`) and layers with their own `layer.parallax`
     unchanged. Head and arms keep today's wobble, so the head doesn't slide off the torso.
   - The value 48au should make capes stick out about 18au past the arms and quivers about 8au past
     them at ±90°. Tune it against the screenshots in the tests below. Keep it between 40 and 60,
     and put the final value in the KDoc.
3. **Light turn effect** (`LayerRenderer.kt` `drawAvatar`): wrap `drawLayerList` (but not the stage
   or the aura) in `withTransform({ scale(1f - TURN_SQUASH * abs(sin(yaw)), 1f, pivot = Offset(frame.feet.x, frame.feet.y)) })`
   with `TURN_SQUASH = 0.15f`, so the avatar is 85% of its width at 90°. The front and back gear
   aren't swapped (there's no back view). Put this in a pure function
   `turnScaleX(yawDeg: Float): Float` in `LayerGeometry.kt`, so it can be tested.
4. **KDoc**: update the `AvatarViewport` doc (±90° limit, drag only) and the `BoneTransform` doc
   (`backPeekPx`).

## Tests / verification

- commonTest `composeApp/src/commonTest/kotlin/se/lixo/app/content/LayerTransformTest.kt` (or a new
  `AvatarTurnTest.kt`):
  - `boneTransforms(..., yaw = 0f, ...)`: `backPeekPx == 0`, and every parallax value equals
    today's. This is the regression check for the front view.
  - At yaw = 90: `backPeekPx == -BACK_PEEK_AU * pxPerAu`. At yaw = -90 it has the opposite sign.
  - `turnScaleX(0f) == 1f`, `turnScaleX(90f) == 0.85f`, `turnScaleX(-90f) == 0.85f`.
  - Clamping: a helper `clampYaw(deg)` (if the clamp is pulled out of the composable) gives
    `clampYaw(200f) == 90f` and `clampYaw(-500f) == -90f`.
  - Geometry: for `item_shoulders_back_sv_cape_1` and `item_shoulders_back_pilkoger_isbla` on the
    default avatar at yaw 90, the item's pixel bounds (`itemUnionPx`) stick out past the union of the
    torso and arm part bounds on the side the gear peeks out. At yaw 0 the quiver stays inside it.
- Instrumented test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/AvatarTurnTest.kt` renders
  the real `AvatarViewport` with a `LixoRepository` profile, following the `LayerRendererScreenshotTest`
  pattern:
  - `rendersTurnAngles`: cat and knight, each wearing a cape, then a quiver, then a balloon, at
    `initialYawDeg` -90, -45, 0, 45 and 90. Capture each as `033_<avatar>_<item>_yaw<deg>.png`.
    Check by eye that at ±90 the gear clearly shows beside the body and still looks attached, that
    the 0° images are identical to the current renders, and that nothing goes outside the stage.
  - `dragIsClampedAt90`: swipe right by much more than 90° worth of pixels (`400 / 0.45` px). The
    capture must equal the `initialYawDeg = 90` capture, within a small tolerance, with the idle
    clock frozen by `mainClock.autoAdvance = false`. Then swipe back left and check the -90 capture
    the same way.
- Regression: `LayerRendererScreenshotTest`, `BundledCatalogRenderTest`, `WardrobeScreenContentTest`,
  `FriendLiveGearTest`. At yaw 0 there should be no visual change.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.AvatarTurnTest,se.lixo.app.verify.LayerRendererScreenshotTest,se.lixo.app.verify.BundledCatalogRenderTest,se.lixo.app.verify.WardrobeScreenContentTest`
  Use `emulator-5554` if the S25 isn't connected. Don't use the Pixel 7, which holds real data.
- Host tests: `./gradlew :composeApp:testAndroidHostTest`. Note that it is not `:androidApp:testDebugUnitTest`,
  which has no test sources.

## Open questions

- Should the avatar spring back to the front when released? Default: **no**. It stays at the angle
  where it was let go, so you can look at the cape without holding your finger down.
- Should TAIL layers also get a stronger peek? Default: **no**. Tails already have their own -0.4
  parallax and aren't hidden.
