# 009 — Screenshot every wardrobe item

**Type**: feature

**Goal**: Add an instrumented test that produces screenshots of every item in the game. Items are grouped into grid sheets to keep the number of screenshots low.

**Context**
- The real catalog comes from `LixoRepository().wardrobeCatalog`, built by `buildSeedWardrobeCatalog()` (`Repositories.kt`, about 579 items: 3 handmade plus `GeneratedWardrobeCatalog.kt`) across 9 `EquipmentSlot`s and 5 `Rarity`s.
- The real item renderer is `WardrobeItemPreview(item, modifier)` (`ui/components/avatar3d/Avatar3DController.kt:196`), the same one used by wardrobe cards (`WardrobeScreen.kt:370`) and the chest reveal.
- SPARKLE/GLOW ornaments start an `infiniteRepeatable` animation, which would make `waitForIdle()` hang. The test must turn off clock auto-advance.
- Screenshot pattern: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FirstProfileSetupTest.kt`. It uses `createAndroidComposeRule<ComponentActivity>()`, `AndroidPlatformContext.init`, an appops `MANAGE_EXTERNAL_STORAGE` grant, a `Downloads/lixo_screenshots` output folder, and `onRoot().captureToImage()` → PNG.

**Steps**
1. Create `androidApp/src/androidTest/kotlin/se/lixo/app/verify/ItemCatalogScreenshotTest.kt` (FQCN `se.lixo.app.verify.ItemCatalogScreenshotTest`). Reuse the `setUp` and `shot()` helpers from `FirstProfileSetupTest`, with output going to `Downloads/lixo_screenshots/items/`. Clear old PNGs there first.
2. Load items with `LixoRepository(FakeCloudGateway()).wardrobeCatalog.value`. Group them by `slot` (in `EquipmentSlot.entries` order), and within each slot sort by `rarity` ordinal, then `id`.
3. Split each slot group into pages of `ITEMS_PER_SHEET = 30` (5 columns × 6 rows), which gives roughly 20 to 25 sheets in total.
4. Add a composable `ItemSheet(title: String, items: List<WardrobeItem>)` with fixed size 1080×1600 px (use `Modifier.requiredSize` in dp from density), inside `LixoTheme` on a white background.
   - Header text: `"<slot.name> — sida n/m (<count> föremål)"`.
   - A non-lazy grid built from `Row`s and `Column`s, so every cell is composed.
   - Each cell (about 200 dp wide):
     - A 72 dp `WardrobeItemPreview`.
     - A 2 dp border coloured with `Color(item.rarity.hexColorPrimary)`.
     - Below it, the `item.nameSv` (1 line, ellipsis) and the `item.id` in small muted text, so a broken item can be traced from the image.
5. A single test, `screenshotAllItems()`:
   - Set `compose.mainClock.autoAdvance = false`.
   - Use one `setContent` holding a `mutableStateOf(sheetIndex)`. For each sheet: set the index, call `compose.mainClock.advanceTimeBy(500)`, then `shot("items_%02d_<slot>_p<n>")`. This avoids calling `setContent` twice, which caused the earlier 004 bug.
   - Also write `items_index.txt` listing sheet file → item ids.
6. Assertions:
   - The total number of items rendered across sheets equals the catalog size.
   - The ids are unique.
   - Each sheet's first and last item ids are found with `onNodeWithText(id).assertExists()`.

**Tests / verification**
- Instrumented only (it produces visual evidence): `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.ItemCatalogScreenshotTest`
- Pull the results: `adb -s emulator-5554 pull /sdcard/Download/lixo_screenshots/items temp/verify009/`. Then check that the sheet count is ceil(per-slot count / 30) summed over slots, that each PNG shows a full grid, and that `items_index.txt` lists every id.

**Open questions**: none.

**Verification device**: The user started a Pixel 10 emulator, `emulator-5554`, and it is used for verification instead of R5CXC0681EM, which is not connected.
- When running the pending tasks, set `verifyNotes` to `"Device emulator-5554 (Pixel 10 emulator). ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn,fqcn>"`.
- On the emulator, the 007 test feeds STT through the fake engine (there is no real mic). 008 uses the real Room DB on the emulator.
