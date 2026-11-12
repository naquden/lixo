# 046 — Campaign map: level number on each node + auto-scroll to the next available level

**Type**: feature

## Goal

1. Show the level number on each level node in the campaign map (world path).
2. When the player enters a world page of the map (first opening the map, returning from a level, switching world with the selector or by swiping), the path scrolls by itself to the next level to play, so the child doesn't have to scroll down to find it.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/map/CampaignMapScreen.kt`
  - `CampaignMapScreen` (:106): `HorizontalPager` of worlds (:295). Each page calls `WorldPage(levels = levels.filter { it.subject == world.subject }.sortedBy { it.order }, …)` (:303–314). `renderAvatar = pagerState.settledPage == page`.
  - `WorldPage` (:654): `LazyColumn` (:674) with **no `LazyListState`**, so every page opens at the top (level 1). `itemsIndexed(levels)` (:687) where `localIndex` is the 0-based position in that world. `currentLevel` (:666) comes from `progressionEngine.currentLevelForSubject(...)`: first unlocked level with 0 stars, else the world's last level. The "START" bubble and avatar are drawn next to it.
  - Pop-in stagger (:703–720): delay `localIndex * 35 ms`, capped at 900 ms. If the list opens mid-path (e.g. item 40), every visible node waits the full 900 ms.
  - `CampaignLevelNode` (:809): 72 dp circle, tag `MAP_LEVEL_NODE_TAG_PREFIX + level.id` (:855), shows only an icon (lock when locked, else a subject icon, 32 dp, :833–883). **No number anywhere on the node.**
- `CampaignLevel.order` (`domain/model/Models.kt:251`) is a **global** order (1..160 + geography 161..190), so it can't be shown as is. The number to show is the position in the world: `localIndex + 1` (Världsresan level_161 → "1").
- `ProgressionAndGiftEngine.currentLevelForSubject` (`domain/engine/ProgressionAndGiftEngine.kt:263`): defines "next available level". Reuse it, don't add a second rule.
- Existing test using this screen: `androidApp/src/androidTest/kotlin/se/lixo/app/verify/GeographyWorldMapTest.kt` (pattern for setting up `CampaignMapScreen` with `DummyData.populatedRepository()`).

## Steps

1. **Level number on the node** (`CampaignLevelNode`):
   - Add a `number: Int` parameter. `WorldPage` passes `localIndex + 1`.
   - **Unlocked nodes**: the number **replaces** the subject icon (Book/Calculator/Globe/Star are no longer drawn on unlocked nodes; drop that branch of the `icon` `when`). Draw it with a new private composable `Number3D(number: Int, faceColor: Color, lipColor: Color, modifier)` (in `CampaignMapScreen.kt`, or `ui/components/Number3D.kt` if reused), built from layered `Text`s in a `Box` so it works in commonMain on Android **and** iOS (no AGSL `RuntimeShader`: it's Android 13+ only and not available on iOS):
     1. **Extrusion**: 3 copies of the number offset `y = 1.dp, 2.dp, 3.dp` (drawn back to front), colour = node `lipColor` darkened ~25% (`lerp(lipColor, Color.Black, 0.25f)`), so the digit looks like a raised 3D block, matching the node's own lip style.
     2. **Face**: top copy with `style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, lerp(Color.White, faceColor, 0.35f))), shadow = Shadow(color = Color.Black.copy(alpha = 0.25f), offset = Offset(0f, 6f), blurRadius = 8f))`. The white-to-tint gradient gives the "shaded" look.
     3. **Highlight**: a thin gloss on the upper half: same text again with `Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.45f to Color.Transparent)` drawn on top (or clip the top-half copy with `Modifier.drawWithContent { clipRect(bottom = size.height * 0.5f) { this@drawWithContent.drawContent() } }`).
     - Font: `fontSize = 30.sp` for 1–2 digits, `24.sp` for 3 digits (worlds have ≤ 160 levels), `FontWeight.Black`, `maxLines = 1`, `softWrap = false`, `lineHeight` = fontSize, centred in the face circle. Mark the extrusion/highlight copies `Modifier.clearAndSetSemantics {}` so only the face copy carries the text `"N"`.
     - Gold 3-star nodes: use `LixoColors.GoldLip` for the extrusion (same rule: the node's `lipColor`). The current node's pulse already scales the whole node, so the number pulses with it.
     - Press feedback: the number sits inside the face `Box` that already offsets by `pressOffset`, so it moves with the face.
   - **Locked nodes**: keep the lock icon (shrink to 24 dp) with a small flat number under it (`labelMedium`, `FontWeight.ExtraBold`, `LixoColors.TextDisabled`), no 3D effect, so the child can see how far ahead a locked level is.
   - Semantics: the node's `clickable` merges children, so its tagged node gets text `"N"`. Also set `Modifier.semantics { contentDescription = "Bana $number" }` (put the string in `LixoStrings` as `MAP_LEVEL_NODE_CD_PREFIX = "Bana"`) for accessibility/tests.
   - Check the 3-star gold and current-pulse nodes still look right (3-digit numbers fit in 72 dp minus the 5 dp lip, no clipping of the extrusion at the bottom).
2. **Per-page list state + auto-scroll** (`WorldPage`):
   - Add a parameter `isActivePage: Boolean` (pass `pagerState.settledPage == page` from the pager, the same value as `renderAvatar`).
   - Work out `val targetIndex = levels.indexOfFirst { it.id == currentLevel?.id }.coerceAtLeast(0)`.
   - `val listState = rememberLazyListState(initialFirstVisibleItemIndex = (targetIndex - 1).coerceAtLeast(0))`, so on first composition the page already opens near the target (no visible jump from the top). Pass it to the `LazyColumn` (`state = listState`).
   - Centre the target when the page becomes active and whenever the target changes:
     ```kotlin
     LaunchedEffect(isActivePage, currentLevel?.id) {
         if (!isActivePage || levels.isEmpty()) return@LaunchedEffect
         val info = listState.layoutInfo
         val viewport = info.viewportEndOffset - info.viewportStartOffset
         val itemSize = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
             ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
         listState.animateScrollToItem(targetIndex, scrollOffset = -(viewport / 2 - itemSize / 2))
     }
     ```
     (`animateScrollToItem` clamps at the list ends, so the first and last levels are fine.) This covers entering the map, returning after a level (progress changes → new `currentLevel`), and every world switch (selector tap or swipe settles → `isActivePage` becomes true).
   - Don't re-scroll while the child scrolls the same page by hand: the effect only re-runs when `isActivePage` or the target id changes.
3. **Pop-in stagger relative to the viewport**: change the delay to use the distance from the first visible item instead of the absolute index, e.g. `((localIndex - listState.firstVisibleItemIndex).coerceAtLeast(0) * 35L).coerceAtMost(900L)` (read `firstVisibleItemIndex` once inside the `remember(level.id)` block), so nodes deep in a world don't all wait 900 ms.
4. Keep `MAP_LEVEL_NODE_TAG_PREFIX` and the node tags unchanged (used by other tests).

## Tests / verification

- **Instrumented** (new): `androidApp/src/androidTest/kotlin/se/lixo/app/verify/CampaignMapLevelNumberScrollTest.kt` (follow the `GeographyWorldMapTest` setup: `createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoTheme { CampaignMapScreen(...) }`, real `DummyData.populatedRepository()` levels + content, real `ProgressionAndGiftEngine`). Hold `selectedSubject` in a `mutableStateOf` and update it from `onSelectSubject`.
  - `levelNodes_showWorldLocalNumber`: with no progress, the node tagged `…level_161` (geography, selected `Subject.GEOGRAPHY`) has text `"1"` (exactly once in its merged semantics, i.e. the extrusion/highlight copies are hidden) and content description `"Bana 1"`; `…level_162` has `"2"` (locked, still numbered). For the Swedish world, its first level's node shows `"1"`. Capture each node with `onNodeWithTag(…).captureToImage()` → `verify046/node_unlocked.png`, `node_locked.png`, plus a 3-star gold node and a 3-digit number node (e.g. 3 stars on Swedish level 1, and scroll to a level ≥ 100 in a world that long) → `node_gold.png`, `node_3digit.png`, so the opus review can check the 3D look and that no subject icon is drawn on unlocked nodes.
  - `entering_scrollsToNextAvailableLevel`: build `progressByLevelId` with 1 star on the first 20 Swedish levels (`LevelProgress(profileId, levelId, levelOrder, starsEarned = 1, completedInAiMode = false, bestTimeSeconds = 30, bestWpm = 0)`); open on `Subject.SWEDISH`; `waitForIdle()`, then `onNodeWithTag(prefix + <21st Swedish level id>).assertIsDisplayed()` and the "START" text is displayed. Without the fix the node isn't composed/visible (the list starts at the top). Capture `onRoot().captureToImage()` → `lixo_screenshots/verify046/swedish_scrolled.png`.
  - `switchingWorld_scrollsThatWorld`: same progress plus 1 star on the first 15 math levels; start on Swedish, then click the world selector card "Sifferdalen" (`onNodeWithText("Sifferdalen").performClick()`), `waitForIdle()`, assert the 16th math level node is displayed. Screenshot `math_scrolled.png`.
  - `returningWithNewProgress_scrollsToNewTarget`: start with 20 Swedish levels done, then set progress to 30 done (state var) → the 31st Swedish level node is displayed.
- Regression: `GeographyWorldMapTest`, `EventBannerTest`, `FriendLiveGearTest` (use the map) still pass.
- **commonTest**: no new engine logic (reuses `currentLevelForSubject`); if a helper for the target index / world-local number is pulled out of the composable (e.g. `mapTargetIndex(levels, currentLevel)`), add a small unit test in `commonTest` for it (empty world → 0, all done → last index).
- Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.CampaignMapLevelNumberScrollTest,se.lixo.app.verify.GeographyWorldMapTest`

## Open questions

