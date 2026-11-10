# 032 — Shared hand items: any HAND_LEFT/HAND_RIGHT item can be held in either hand

**Type**: feature

## Goal

The right and left hand share one item pool. Any unlocked hand item, whether authored as
`HAND_RIGHT` or `HAND_LEFT`, can be equipped in either hand. It is drawn on the hand it's
equipped in, mirrored when that is the other side from the one it was authored for.

## Context

- Bundled pack (`composeApp/src/commonMain/composeResources/files/content_pack.json`) has:
  - 67 `HAND_RIGHT` items, all layers on bone `ARM_R`, none with `mirror`.
  - 65 `HAND_LEFT` items, all layers on bone `HAND_L`, none with `mirror`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/wardrobe/WardrobeScreen.kt:144–151`
  filters by `it.slot == selectedSlot`, so each hand only lists its own items.
  `:380` and `:419` check equipped state by `selectedSlot`, which already works per hand.
- `composeApp/src/commonMain/kotlin/se/lixo/app/data/repository/Repositories.kt:462–483`
  (`equipItemOnActiveProfile`) only checks that the item is unlocked and exists, not its slot. Any
  item can already be stored in either hand, so no repository or Room change is needed.
  `AvatarAppearance.equipped` stays `Map<EquipmentSlot, String>`.
- Rendering: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/components/avatar/LayerGeometry.kt`
  - `:462–485` `buildAvatarDrawList`: `items = EquipmentSlot.entries.mapNotNull { equipped[slot] → item }`
    drops the slot it was equipped in. Layers are placed via `expandMirror` (`:441–451`) on
    `layer.bone`, the side the item was authored for. Root cause: a right-hand item equipped in
    `HAND_LEFT` would still draw on the right arm.
  - The mirroring machinery already exists: `Bone.mirrorOf()` (`domain/content/Skeleton.kt:36`),
    `PlacedLayer(bone = mirrorBone, mirrored = true)`, and the x flip in the bone matrix (`:390–398`),
    which uses `STANDARD_BONE_BOX[p.bone].width` for items.
  - `:199–204` `isHeldItemLayer` keys on `p.item.slot in HELD_ITEM_SLOTS` and the bone in
    `HELD_ITEM_BONES` (both sides), so it still holds after remapping. `heldItemAnchors` (`:207`)
    handles `ARM_L` and `ARM_R`.
  - `:597` `avatarContentDescription` needs no change: it lists names in slot order.
- Other places that read `equipped`:
  - `ui/screens/social/AvatarMapScreen.kt:589` and `:1303` (friends' gear). These go through
    `buildAvatarDrawList` or only look at rarity, so they need no change beyond the renderer.
  - `ui/screens/debug/ContentPreviewScreen.kt:142` checks `equipped[item.slot]`. This is debug only
    and can stay.
  - `ItemPreview` / `buildItemDrawList` (`:508`) shows an item on its authored side. No change.

## Steps

1. **Domain helper.** In `domain/model/Models.kt` (next to `EquipmentSlot`), add:
   ```kotlin
   val EquipmentSlot.isHand: Boolean get() = this == EquipmentSlot.HAND_LEFT || this == EquipmentSlot.HAND_RIGHT
   /** Whether [item] may be equipped in [slot]: same slot, or both are hand slots (shared pool, plan 032). */
   fun EquipmentSlot.accepts(itemSlot: EquipmentSlot): Boolean = this == itemSlot || (isHand && itemSlot.isHand)
   ```
2. **Wardrobe list.** In `WardrobeScreen.kt:146`, change the filter to `selectedSlot.accepts(it.slot)`.
   The sort (rarity desc, then id) stays, so both hands show the same list. Equipped and "Ingen"
   state keep using `selectedSlot` as they do now.
3. **Repository guard.** In `equipItemOnActiveProfile`, also require
   `slot.accepts(content.active.value.itemsById.getValue(itemId).slot)`, so non-hand items still
   can't go into the wrong slot. This tightens the rules only for invalid, non-hand combinations.
   Existing saved data can't contain those, because the UI never offered them.
   - **One hand at a time:** when `slot.isHand` and the same `itemId` is already in the other hand
     slot, remove it from that slot in the same `nextEquipped` update. The item moves hands; it is
     never held twice. Example: the sword is in `HAND_RIGHT`, and equipping it in `HAND_LEFT` gives
     `HAND_LEFT = sword` and leaves `HAND_RIGHT` empty.
4. **Renderer: draw on the equipped side.** In `buildAvatarDrawList`, keep each item paired with its
   equipped slot: `val equippedItems = EquipmentSlot.entries.mapNotNull { slot -> equipped[slot]?.let { content.itemsById[it] }?.let { slot to it } }`.
   Set `items = equippedItems.map { it.second }`, which keeps `AvatarDrawList.items`, `hidesParts`
   and `hasLegendary` unchanged.
   - For each `(slot, item)`: `val swap = slot.isHand && item.slot.isHand && slot != item.slot`.
   - When `swap` is true, add a `sideSwap = true` parameter to `expandMirror` that, for each placed
     layer, moves it to `bone.mirrorOf() ?: bone` and inverts `mirrored`. A `Mirror.X` layer produces
     both sides anyway, and swapping them leaves the set the same. Non-swapped items go through
     exactly the same path as today.
   - Check the `p.mirrored` flip (`:390–398`) for a remapped item layer. It uses
     `STANDARD_BONE_BOX[p.bone].width` of the new bone, which matches the shoe/leg `Mirror.X` path.
     If the result looks off, align it with how mirrored `LEG_R` shoe layers resolve.
   - The same item never appears in both hands (step 3), so each item renders once.
5. **Z order.** `sortLayers` sorts by `layer.z`. Right-hand items on `ARM_R` and left-hand items on
   `HAND_L` may sit in different buckets (`ARM`/`HAND`). Confirm with the screenshots that a swapped
   item isn't hidden behind the body. If it is, give swapped layers the z bucket that the target
   side's native items use (`HAND` for the left, the `ARM_R` items' bucket for the right). That
   override goes in `PlacedLayer`, not in the content.
6. **Strings.** Slot pill names stay "Höger hand" / "Vänster hand". No content or pack changes, and
   no content generation (memory rule).

## Tests / verification

- commonTest `composeApp/src/commonTest/kotlin/se/lixo/app/content/LayerTransformTest.kt` (or a new
  `SharedHandItemsTest.kt` next to it), using `SampleContentPack` (`item_hand_right_test_wand` on
  `HAND_R`, `item_hand_left_test_shield` on `HAND_L`):
  - The wand equipped in `HAND_LEFT` gives layers on `HAND_L` with `mirrored = true`, and none on `HAND_R`.
  - The shield equipped in `HAND_RIGHT` gives layers on `HAND_R` with `mirrored = true`.
  - Native equips are unchanged: the wand in `HAND_RIGHT` draws on `HAND_R` with `mirrored = false`.
  - `EquipmentSlot.accepts`: hand↔hand is true, `HAT` with a hand item is false, same slot is true.
- commonTest for the repository (next to `ProfileStoreTest.kt`):
  - Equipping a `HAND_LEFT` item in `HAND_RIGHT` is stored.
  - Equipping a `HAT` item in `SHOES` is ignored.
  - With the sword in `HAND_RIGHT`, equipping it in `HAND_LEFT` gives `HAND_LEFT = sword` and removes
    `HAND_RIGHT`. Other slots are untouched.
- Instrumented test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/SharedHandItemsTest.kt`
  (real `WardrobeScreen` + `LixoRepository`, set up like `WardrobeScreenContentTest`):
  - Unlock `item_hand_right_math_sword_1` and one bundled `HAND_LEFT` item.
  - On the "Vänster hand" pill, both item names exist. Click the sword, then assert that
    `equipped[HAND_LEFT] == sword.id` and that the viewport content description contains the sword.
  - On the "Höger hand" pill, the `HAND_LEFT` item is listed and can be equipped.
  - Move test: equip the sword in the right hand, switch to "Vänster hand" and tap the sword. Assert
    `HAND_LEFT == sword` and that `HAND_RIGHT` is absent. On the "Höger hand" pill, the sword card is
    not marked equipped and "Ingen" is selected.
  - Capture `032_sword_left_hand.png` and `032_shield_right_hand.png` (`onRoot().captureToImage()`)
    and check by eye that each item sits in the correct hand and is mirrored, not behind the body.
  - Optionally extend `BundledCatalogRenderTest` with every hand item rendered in the opposite hand
    on all 6 avatars, saved as a sheet.
- Regression: `WardrobeScreenContentTest`, `WardrobeRarityCountTest`, `FriendLiveGearTest`,
  `BundledCatalogRenderTest`, plus `LayerTransformTest` / `ZOrderTest`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.SharedHandItemsTest,se.lixo.app.verify.WardrobeScreenContentTest,se.lixo.app.verify.FriendLiveGearTest`

## Open questions

- Should the item preview on the cards show the item on the selected hand's side (mirrored on the
  other hand's tab)? Default: **no**, the preview stays as authored.
- Rarity counts (`rarityCollectionCounts`) count the whole catalog, so they are unaffected.
- Execution order: run after 031. Both touch `WardrobeScreen.kt`, in separate areas.
