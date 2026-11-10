# 034 — Wardrobe item cards sized from the screen and the font scale

**Type**: bug

## Goal

Wardrobe item cards and the "Ingen" card must not have a fixed size. Their width and the preview
size are proportioned to the available screen and grid size, and their height is derived from
that plus the actual text height at the current font scale. The rarity badge and item name must
never be clipped, including at a large system font size. All cards stay the same height as each other.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/wardrobe/WardrobeScreen.kt`
  - `:94–99`: `private val WARDROBE_CARD_HEIGHT = 124.dp`. Its KDoc derives the value from sp-based
    text lines (the labelSmall line height of 14sp, the badge and the name) but turns them into fixed dp.
  - `:400–408`: `rows = ((maxHeight + 12.dp) / (WARDROBE_CARD_HEIGHT + 12.dp)).toInt().coerceIn(1, 3)`,
    and the grid height is `WARDROBE_CARD_HEIGHT * rows + 12.dp * (rows - 1)`.
  - `:421–423` and `:465–467`: both cards use `Modifier.width(104.dp).wrapContentHeight(Alignment.Top, unbounded = true).height(WARDROBE_CARD_HEIGHT)`.
    That keeps them pixel-identical, which plan 031 needed to fix a 1px difference between rows.
  - `:432`, `:484` and `:487`: the preview and the "Ingen" icon box are a fixed `size(64.dp)`.
  - `:186`: the avatar stage is `(maxHeight * 0.30f).coerceIn(150.dp, 240.dp)`. This already scales
    with the screen and isn't changed here.
- **Root cause:** the card height is fixed in dp, but the text inside it is in sp. At a font scale
  above 1.0 the badge and the name need more than 124dp, and the fixed height clips the name line.
  The width (104dp) and the preview (64dp) don't follow the screen width either, so cards look small
  on large phones or tablets and squeezed on small ones.
- Known follow-up from 031 (FEATURES.md, 031 LOW): when the font is large or the window very short,
  the item area can shrink to nothing. The card sizing here also covers that by shrinking the preview
  when one row doesn't fit.
- `androidApp/src/androidTest/kotlin/se/lixo/app/verify/WardrobeRarityCountTest.kt:160–180`
  (`wardrobe_legendaryBadgeStaysOnOneLine`): checks that the badge heights are equal and the badge
  is one line. The cards must stay equal in height.

## Steps

1. **Pure sizing function.** Add it to `WardrobeScreen.kt` as `internal`, or put it in a new
   `WardrobeCardMetrics.kt` in the same package so commonTest can reach it:
   ```kotlin
   internal data class WardrobeCardMetrics(val width: Dp, val height: Dp, val preview: Dp, val rows: Int)

   /** gridWidth/gridHeight = the items area; textBlock = badge + name height at the current font scale. */
   internal fun wardrobeCardMetrics(gridWidth: Dp, gridHeight: Dp, textBlock: Dp): WardrobeCardMetrics
   ```
   - Width: `((gridWidth - GAP * 2) / VISIBLE_COLUMNS).coerceIn(88.dp, 160.dp)`, where
     `VISIBLE_COLUMNS = 3.3f`. A third of the next card always shows, as a hint that the row scrolls
     sideways. `GAP = 12.dp`.
   - Preview: `(width * 0.62f).coerceIn(40.dp, 96.dp)`.
   - Height: `CARD_CHROME + preview + textBlock`, where
     `CARD_CHROME = 3.dp lip + 2 * 10.dp padding + 4.dp + 3.dp spacers = 30.dp`.
   - Rows: `((gridHeight + GAP) / (height + GAP)).toInt().coerceIn(1, 3)`.
   - Fit fallback: if `height > gridHeight` (not even one row fits), lower `preview` to
     `max(40.dp, gridHeight - CARD_CHROME - textBlock)` and recompute `height`, with `rows = 1`. If
     the card still doesn't fit at a 40dp preview, accept that it overflows, because the text must
     not be clipped (see Open questions).
2. **Text height from the real typography and font scale.** In the composable:
   - `val density = LocalDensity.current`
   - `val labelSmall = MaterialTheme.typography.labelSmall`
   - `val textBlock = with(density) { labelSmall.lineHeight.toDp() } + 2.dp /* badge v-padding */ + with(density) { labelSmall.lineHeight.toDp() } /* name */`
     - If `lineHeight` is `TextUnit.Unspecified`, fall back to `fontSize * 1.3f`.
     - The badge uses `autoSize` with a maximum of 10sp, so its line is at most the labelSmall line
       height. That keeps the value an upper bound.
3. **Use it.** In the grid's `BoxWithConstraints` (`:400`), compute
   `val m = remember(maxWidth, maxHeight, textBlock) { wardrobeCardMetrics(maxWidth, maxHeight, textBlock) }`, then:
   - set the grid to `rows = GridCells.Fixed(m.rows)` and height `m.height * m.rows + 12.dp * (m.rows - 1)`;
   - give both cards `Modifier.width(m.width).wrapContentHeight(Alignment.Top, unbounded = true).height(m.height)`,
     which keeps the 031 pixel-identical rule;
   - make the preview box, `ItemPreview` and the "Ingen" icon box `size(m.preview)`, and the "Ingen"
     icon `m.preview * 0.375f` (24/64 as today). The check badge stays 18dp.
   - Remove `WARDROBE_CARD_HEIGHT` and update the KDoc and the grid comment.
4. **Empty state** (`:342–361`): unchanged.

## Tests / verification

- commonTest `composeApp/src/commonTest/kotlin/se/lixo/app/WardrobeCardMetricsTest.kt` (the
  function is `internal` in commonMain, so it can be reached from commonTest in the same module):
  - Width is proportional: a 320dp grid gives a smaller width than a 372dp grid, which gives a
    smaller width than 720dp. The width stays between 88 and 160dp, and about 3.3 cards fit across.
  - The height grows with `textBlock`: a 28dp text block gives a smaller height than 42dp (font
    scale 1.5), and `height == 30.dp + preview + textBlock`.
  - Rows: a grid 300dp high with about 124dp cards gives 2 rows. 900dp gives 3, the maximum. 60dp
    gives 1 row, using the fallback.
  - Fallback: with `gridHeight = 110.dp` and `textBlock = 42.dp`, the preview shrinks to fit
    (`height <= 110.dp`) and stays at 40dp or more.
- Instrumented tests, added to `WardrobeRarityCountTest` or a new
  `androidApp/src/androidTest/kotlin/se/lixo/app/verify/WardrobeCardSizingTest.kt`, rendering the
  real `WardrobeScreen` with a real `LixoRepository` and some unlocked HAT items:
  - `largeFont_nameNotClipped`: wrap in `CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f))`.
    For the first item name node, check `GetTextLayoutResult`: `didOverflowHeight == false` and
    `lineCount == 1`. Its `boundsInRoot` must lie inside the bounds of its card's merged clickable
    node. Capture `034_font150.png`.
  - `cardsScaleWithScreen`: render inside `Box(Modifier.size(360.dp, 640.dp))` and then
    `Box(Modifier.size(600.dp, 960.dp))`, using two tests or `StateRestorationTester`. The card width
    in the large box must be larger, and every card in the same render must have the same height
    (compare all merged card nodes). Capture `034_small.png` and `034_large.png`.
  - `wardrobe_legendaryBadgeStaysOnOneLine` (existing) still passes.
- Regression: `WardrobeScreenContentTest` (the 031 tests, avatar still visible), `SharedHandItemsTest`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.WardrobeCardSizingTest,se.lixo.app.verify.WardrobeRarityCountTest,se.lixo.app.verify.WardrobeScreenContentTest,se.lixo.app.verify.SharedHandItemsTest`
  Use `emulator-5554` if the S25 isn't connected. Don't use the Pixel 7, which holds real data.
- Host tests: `./gradlew :composeApp:testAndroidHostTest`. Note that it is not `:androidApp:testDebugUnitTest`.

## Open questions

- What should happen when not even one row fits at a 40dp preview, for example at font scale 2.0
  on a short screen? Default: the card overflows its cell rather than clipping text. A full fix, a
  vertical-scroll fallback for the whole screen, is the other 031 LOW item and could become its own task.
- Should about 3.3 visible cards (`VISIBLE_COLUMNS`) be the target? Default: yes, because the partly
  visible card shows that the row scrolls sideways.
