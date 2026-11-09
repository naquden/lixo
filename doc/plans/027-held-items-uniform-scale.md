# 027 — Held items keep their shape on slim/broad avatars

**Type**: bug

**Goal**: Items in the HAND_LEFT / HAND_RIGHT slots (shields, swords, wands) should not be stretched differently in x and y to fit an avatar's hand box. A round shield should stay round on the princess and witch. Gloves and sleeves (ARMS_GLOVES) keep the per-axis stretch.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/components/avatar/LayerGeometry.kt:251` — `BoneTransform.itemScale = eff / STANDARD_BONE_BOX` per axis.
- `LayerGeometry.kt:329` (`layerMatrix`) — every item layer gets `m.scale(itemScale.x, itemScale.y)`, then the `item.scale` pivot scale in standard coordinates, then mirroring in standard width.
- Hands are derived from the arm: `(armW, armH * 0.475)` (pipeline). Standard hand box is 38×38. Princess hand is 28×37.05 → itemScale (0.737, 0.975); witch is 28×38.95 → (0.737, 1.025). That is why round shields turn into tall ovals (see `temp/verify/lixo_screenshots/verify026/avatar_avatar_princess_style0.png`, `…_style3.png`, `avatar_avatar_witch_style0.png`, `…_style3.png`).
- `ITEM_SCALE_PIVOT[HAND_L/HAND_R] = (0.5, 0.5)` (`LayerGeometry.kt:190`).
- `ContentItem.slot: EquipmentSlot` (`domain/content/ContentPack.kt:110`). `PlacedLayer.item` holds it.
- Found by the 026 review (LOW).

## Steps
1. In `LayerGeometry.kt` add a helper `private fun isHeldItemLayer(p: PlacedLayer) = p.item != null && p.item.slot in setOf(EquipmentSlot.HAND_LEFT, EquipmentSlot.HAND_RIGHT) && p.bone in setOf(Bone.HAND_L, Bone.HAND_R)`.
2. In `layerMatrix`, inside the `p.item != null` branch, replace `m.scale(t.itemScale.x, t.itemScale.y)` for held-item layers with a uniform scale that keeps the hand-box centre fixed:
   ```kotlin
   val std = STANDARD_BONE_BOX[p.bone] ?: Size.Zero
   val k = sqrt(t.itemScale.x * t.itemScale.y)   // geometric mean
   m.translate(0.5f * t.effectiveSize.width, 0.5f * t.effectiveSize.height)
   m.scale(k, k)
   m.translate(-0.5f * std.width, -0.5f * std.height)
   ```
   Everything after that (the `item.scale` pivot step, `sideOffsetAu`, mirroring in standard width) stays as it is, because it already works in standard coordinates.
   Non-held item layers keep `m.scale(t.itemScale.x, t.itemScale.y)`.
3. Update the KDoc of `layerMatrix` and the "Item stretch" section of `doc/content/content-pack.md` to say that held items (HAND_LEFT/HAND_RIGHT slots on HAND bones) scale uniformly by the geometric mean, centred on the hand box.

## Tests / verification
commonTest (`composeApp/src/commonTest/kotlin/se/lixo/app/content/LayerTransformTest.kt`):
- new `heldItemScalesUniformlyOnNarrowHand`: an avatar with `HAND_R` size 28×37.05 and a HAND_RIGHT item with one 38×38 square layer on HAND_R. Its placed bounds (`placedBoundsPx` with a unit `RigFrame`) have width == height (±0.01), and the centre equals the centre of the effective hand box.
- new `glovesStillStretchPerAxis`: an ARMS_GLOVES item layer on HAND_R on the same avatar has placed size 28×37.05.
- the existing `itemLayersScaleWithOversizedBone` / `itemLayerStretchesToAvatarTorso_partLayerDoesNot` stay green.

Instrumented (emulator-5554, or R5CXC0681EM when attached):
- `se.lixo.app.verify.BundledCatalogRenderTest` (all 3 tests green). Review `avatar_avatar_princess_style0/3.png` and `avatar_avatar_witch_style0/3.png`: shields are round and swords keep their proportions, and items still sit in the hand on the knight and dog.

```
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BundledCatalogRenderTest
```

## Open questions
