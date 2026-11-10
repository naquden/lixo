# 031 — Wardrobe: avatar always visible while browsing and choosing items

**Type**: feature

## Goal

On the wardrobe screen the avatar must always stay visible while you browse and choose items.
Today you have to scroll down to pick an item and back up to see how it looks on the avatar.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/wardrobe/WardrobeScreen.kt`
  - `:155–160`: the whole screen is one `Column(fillMaxSize().verticalScroll(rememberScrollState()))`.
  - `:164–196`: the header row, then `RarityCollectionRow`.
  - `:201–214`: the avatar `LixoCard` at a fixed `height(240.dp)` with `AvatarViewport(interactiveRotation = true)`.
  - `:219–256`: tint swatches. `:261–305`: avatar picker (`horizontalScroll`). `:310–337`: slot pills (`horizontalScroll`).
  - `:342–361`: empty-state card. `:368–490`: `LazyHorizontalGrid(rows = GridCells.Fixed(3))` at a fixed
    `height(420.dp)`, with cards `width(104.dp)` and their height decided by content.
  - `:119–126`: KDoc describing the layout.
- Root cause: header, chips, avatar and controls take about 540dp before the grid starts, and the grid
  adds 420dp more. The total is well past one screen (the app is portrait only, `androidApp/src/main/AndroidManifest.xml:35`),
  so the avatar and the grid can't both be on screen.
- Call site: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:558`. Its signature stays the same.
- Existing tests: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/WardrobeScreenContentTest.kt`
  (`equipItem_updatesViewport` uses `performScrollTo()` on the slot pill and the item) and
  `WardrobeRarityCountTest.kt` (tags and texts, plus a badge-height comparison).
- UI only. No Room, content or data changes.

## Steps

1. **Fixed top, filling bottom.** Replace the root scrolling `Column` with
   `BoxWithConstraints(fillMaxSize().background(LixoColors.Background).padding(horizontal = 20.dp, vertical = 16.dp))`
   containing a non-scrolling `Column(fillMaxSize(), horizontalAlignment = CenterHorizontally)`:
   - Header row and `RarityCollectionRow`, unchanged.
   - Avatar card: height `(maxHeight * 0.30f).coerceIn(150.dp, 240.dp)` instead of `240.dp`.
   - Tint swatches, avatar picker and slot pills, unchanged apart from tighter spacers (20/14dp down to about 10dp).
   - Items area: `Box(Modifier.fillMaxWidth().weight(1f))` holding either the empty-state card
     (aligned to the top) or the grid.
   - Keep every click handler, string, test tag and the card contents unchanged.
2. **Adaptive grid.** Inside the items `Box`, use `BoxWithConstraints(Modifier.fillMaxSize())`:
   - Add `private val WARDROBE_CARD_HEIGHT = 124.dp`. Give both the "Ingen" card and the item card
     `Modifier.width(104.dp).height(WARDROBE_CARD_HEIGHT)`.
   - `val rows = ((maxHeight + 12.dp) / (WARDROBE_CARD_HEIGHT + 12.dp)).toInt().coerceIn(1, 3)`.
   - `LazyHorizontalGrid(rows = GridCells.Fixed(rows), state = gridState, modifier = Modifier.fillMaxWidth().height(WARDROBE_CARD_HEIGHT * rows + 12.dp * (rows - 1)).testTag("wardrobe_item_grid"))`.
     Remove the fixed `420.dp`.
   - `val gridState = remember(selectedSlot) { LazyGridState() }`, so switching slots starts at "Ingen".
   - Keep `key = { it?.id ?: "__none__" }` and the lazy-grid comment.
3. **KDoc** (`:119–126`): say that the avatar stage is pinned and the item grid fills the remaining
   height with an adaptive row count.

## Tests / verification

- Build: the `:composeApp` Android compile task and `./gradlew :composeApp:testDebugUnitTest`
  (WardrobeRarityCountsTest, LixoEnginesTest) both stay green.
- Instrumented tests in `WardrobeScreenContentTest` (render the real `WardrobeScreen` with the real
  repository, following the existing `setUp`):
  - `avatarStaysVisible_whileBrowsingItems`: unlock several HAT items, then
    `onNodeWithContentDescription(<avatar desc>).assertIsDisplayed()`. Run `performTouchInput { swipeLeft() }`
    on `wardrobe_item_grid`, click an item, then assert the viewport is still displayed and its
    content description includes the new item. Capture `wardrobe_avatar_pinned`.
  - `slotSwitch_resetsGridToFirstCard`: swipe the HAT grid, switch to another slot and back, then
    `onNodeWithText(LixoStrings.WARDROBE_NONE_ITEM).assertIsDisplayed()`.
  - `smallScreen_avatarAndOneRowVisible`: render inside `Box(Modifier.size(360.dp, 640.dp))`, then assert
    the avatar viewport and at least one item card are `assertIsDisplayed()`. Capture `wardrobe_small_screen`.
  - `equipItem_updatesViewport` should still pass. If `performScrollTo()` on the grid item fails, use
    `onNodeWithTag("wardrobe_item_grid").performScrollToKey(sword.id)` before the click.
- Regression: `WardrobeRarityCountTest` 2/2. Fixed card heights must keep the badge heights equal.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.WardrobeScreenContentTest,se.lixo.app.verify.WardrobeRarityCountTest`
- Evidence: in every screenshot the avatar and the item cards are visible together, with nothing clipped.

## Open questions

- Should the rarity count chips stay in the pinned top? They take about 30dp, or about 60dp if they
  wrap. The default is to keep them. If the space is needed, they could move into the controls area.
