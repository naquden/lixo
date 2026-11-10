# 034 — Wardrobe: size the avatar stage and the item area in proportion to screen height

**Type**: feature

## Goal

On the wardrobe screen, the avatar stage and the item area should split the screen height in
proportion. Today the stage has a fixed height cap, so tall screens end up with empty space under
the item grid. The user suggested `fillMaxHeight(0.Xf)` and asked whether Compose has a more modern
way.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/wardrobe/WardrobeScreen.kt`. This
  describes the code after 031, which is in the working tree but not yet committed.
  - `:180–186`: the root is `BoxWithConstraints`, and
    `avatarStageHeight = (maxHeight * 0.30f).coerceIn(150.dp, 240.dp)`. The cap at 240dp is the
    problem. On a phone with about 800dp the stage stops at 240dp, and the 031 screenshot
    `temp/verify/031_032/wardrobe_avatar_pinned.png` shows about 330px of empty space below the
    2 grid rows.
  - `:228–233`: the avatar `LixoCard` uses `.height(avatarStageHeight)`.
  - `:370`: the items area is `Box(Modifier.fillMaxWidth().weight(1f))`.
  - `:400–408`: inside it, `BoxWithConstraints` works out
    `rows = ((maxHeight + 12.dp) / (WARDROBE_CARD_HEIGHT + 12.dp)).toInt().coerceIn(1, 3)`, and the
    grid gets `height(WARDROBE_CARD_HEIGHT * rows + 12.dp * (rows - 1))`.
  - `:99`: `WARDROBE_CARD_HEIGHT = 124.dp` is fixed. The cards use `.wrapContentHeight(Alignment.Top,
    unbounded = true).height(WARDROBE_CARD_HEIGHT)` (`:423`, `:467`) to get around the 1px row
    remainder that `GridCells.Fixed` produces (the 031 fix). Inside each card, the preview is a
    fixed `64.dp` (`ItemPreview`).
  - The leftover space below the grid is the cards' fixed height times the row count, plus spacing,
    which doesn't divide the area evenly.
- Two LOW review findings from 031 (in `FEATURES.md`, under Done/031) are closely related and are
  fixed here as well:
  - there is no vertical scroll fallback, so with a large font or a very short window the item area
    can shrink to nothing
  - the 124dp card height doesn't scale with font size
- Compose Multiplatform 1.10.3 (`gradle/libs.versions.toml`). The app is portrait only
  (`androidApp/src/main/AndroidManifest.xml`).

### Which Compose tool to use

- `Modifier.fillMaxHeight(0.4f)` takes a fraction of the parent's **whole** height constraint, and
  ignores the header, chips and controls that also sit in the Column. The fractions plus the fixed
  rows can add up to more than the screen, and the Column then clips. So it's not the right tool here.
- **The idiomatic choice is `Modifier.weight(w)` on the `ColumnScope` children.** Weights share out
  only the space that is **left** after the unweighted children (header, chips, swatches, picker,
  pills) have been measured. `avatar.weight(0.42f)` plus `items.weight(0.58f)` always adds up to the
  screen exactly, without `BoxWithConstraints`, clamps or magic numbers.
- Window size classes (`currentWindowAdaptiveInfo()`, material3-adaptive) are meant for picking a
  different layout per width or height class. That's more than this needs in a portrait-only app.
  It's mentioned only as an option for a future tablet layout. No new dependency is needed.
- `BoxWithConstraints` stays in two places: inside the items area, to pick the row count and the card
  size, and at the root, to choose the scrolling fallback on very short screens.

## Steps

1. **Proportional split.** In the root Column:
   - Avatar `LixoCard`: replace `.height(avatarStageHeight)` with
     `.weight(WARDROBE_STAGE_WEIGHT)`, where `WARDROBE_STAGE_WEIGHT = 0.42f`.
   - Items area (`:370`): `.weight(WARDROBE_ITEMS_WEIGHT)`, where `WARDROBE_ITEMS_WEIGHT = 0.58f`.
   - Remove `avatarStageHeight`. The root `BoxWithConstraints` is then only needed for step 3.
   - Put both weights as named constants at the top of the file, with KDoc that explains them
     (share of the height left after the header and controls).
2. **Cards fill the grid cells.** In the items area's `BoxWithConstraints`:
   - Card size floor:
     `val minCard = with(LocalDensity.current) { 64.dp + 36.dp + (14.sp + 16.sp).toDp() }`.
     This covers the preview, padding and badge, plus the two text lines at the current font scale,
     which fixes the second LOW finding.
   - `rows = ((maxHeight + gap) / (minCard + gap)).toInt().coerceIn(1, 3)`, with `gap = 12.dp`.
   - Work in whole pixels, so every row is exactly the same height and no remainder is left over:
     `val cellPx = floor((maxHeightPx - gapPx * (rows - 1)) / rows)`, and
     `gridHeightPx = cellPx * rows + gapPx * (rows - 1)`. Turn these back into dp for
     `LazyHorizontalGrid(...).height(gridHeight)` and set the card height to `cellDp`. This replaces
     the `wrapContentHeight(unbounded = true)` workaround. Remove it and the comment at `:412`.
   - Cap the card height at about `cellDp.coerceAtMost(minCard * 1.4f)`, so cards don't stretch
     too tall when the area fits only 1 row. Centre the grid vertically in the area if any space is
     left over.
   - Card width: `cardWidth = (cellDp * 0.84f).coerceIn(96.dp, 132.dp)`. Today it's a fixed 104dp,
     which gives the cards a ratio of about 104:124.
   - Preview size: `(cellDp - (minCard - 64.dp)).coerceIn(56.dp, 96.dp)` instead of a fixed `64.dp`,
     so the item picture grows with the card.
   - Remove `WARDROBE_CARD_HEIGHT`, or keep it only as the 1.0 font-scale reference in KDoc.
3. **Fallback for short screens or large fonts** (the first LOW finding). At the root, compute the
   height the fixed rows need. A practical estimate is `fixedRowsHeight = 230.dp * fontScale`; or
   measure the controls with `SubcomposeLayout`, but only if the estimate turns out to be fragile.
   - If `maxHeight - fixedRowsHeight < 150.dp + minCard`, there is no room for both a usable stage
     and one row. In that case, render the same content in a `Column(Modifier.verticalScroll(...))`
     with the stage at a fixed `150.dp` and the items area at a fixed `minCard`, which gives 1 row.
     The avatar can then scroll out of view, but only on these extreme screens.
   - Otherwise, use the weighted layout from steps 1 and 2.
   - Pull the shared content (header, chips, stage, controls, items) into private composables that
     take a `Modifier`, so both branches use exactly the same UI and none of it is copied.
4. **KDoc**: update the `WardrobeScreen` KDoc (`:140–150`) to describe the weight split, the cards
   that fill the cells and the fallback.

## Tests / verification

- commonTest (`composeApp/src/commonTest/kotlin/se/lixo/app/WardrobeLayoutTest.kt`). Pull the size
  maths into a pure function, for example
  `wardrobeGridMetrics(areaHeightPx: Int, gapPx: Int, minCardPx: Int): GridMetrics(rows, cellPx, gridHeightPx)`:
  - `cellPx * rows + gapPx * (rows - 1) == gridHeightPx <= areaHeightPx`, with no remainder, for
    many area heights (for example 200 to 1200px in steps of 7).
  - `rows` is 1 when the area is just above `minCardPx`, 3 when it is very tall, and never 0.
  - `cellPx >= minCardPx` whenever the area height is at least `minCardPx`.
- Instrumented tests (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/WardrobeScreenContentTest.kt`):
  - Update `smallScreen_avatarAndOneRowVisible` (360×640dp). It still passes, and with the weighted
    layout the stage is now larger than 150dp.
  - New `tallScreen_fillsHeight`: render inside `Box(Modifier.size(412.dp, 900.dp))`. Assert that
    the avatar viewport's height is at least `0.30 × 900dp` (`fetchSemanticsNode().size`). Also
    assert that the bottom of the item grid (`wardrobe_item_grid`) is within 24dp of the bottom of
    the screen, minus the padding. Capture `034_tall.png`.
  - New `largeFont_itemsStillReachable`: wrap in
    `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f))` at
    360×640dp. Assert that at least one item card is displayed, or can be reached with
    `performScrollTo()` in the fallback, and that its name text isn't clipped (the card node is at
    least as tall as the name node's bottom). Capture `034_large_font.png`.
  - Regression: `WardrobeRarityCountTest` 2/2. The badge heights must be equal, which the whole-pixel
    cells guarantee without the workaround. Also `equipItem_updatesViewport`,
    `avatarStaysVisible_whileBrowsingItems`, `slotSwitch_resetsGridToFirstCard` and `SharedHandItemsTest`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.WardrobeScreenContentTest,se.lixo.app.verify.WardrobeRarityCountTest,se.lixo.app.verify.SharedHandItemsTest`
  Use `emulator-5554` if the S25 isn't connected. Don't use the Pixel 7, which holds real data.
- Host tests: `./gradlew :composeApp:testAndroidHostTest`. Note that it is not `:androidApp:testDebugUnitTest`,
  which has no test sources.
- Evidence: in the 034 screenshots and the updated `wardrobe_avatar_pinned.png`, there is no large
  empty band below the grid, the avatar is clearly larger on tall screens, and every card and name is
  shown in full.

## Open questions

- Split ratio: is a stage:items ratio of 0.42:0.58 right? With 2 rows of cards that gives a stage of
  about 300dp on a typical phone. A bigger avatar (0.5:0.5) leaves only 1–2 rows. Default: 0.42:0.58.
  Tune it from the screenshots.
  Resolved in implementation: 0.5:0.5. Measured fixed rows are about 262-280dp, so 0.42 would shrink
  the 360x640 stage below today's 182dp and fail the 0.30-height tall-screen check.
- Run order: 033 (turning the avatar) also touches the stage, but only inside `AvatarViewport`, so
  the order doesn't matter. Run 034 after 031/032 are committed, because it builds on 031's layout.
