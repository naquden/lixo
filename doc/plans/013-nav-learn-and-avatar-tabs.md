# 013 — Bottom nav: "Karta" → learn tab with book icon, "Kompisar" → "Avatar"

**Type**: feature

## Goal
The first bottom-nav tab is the learning hub, not a map: swap the house icon for a learn/challenge icon (an open storybook with a star, taken from the app launcher icon) and rename "Karta" to a learning label. Rename the "Kompisar" tab to "Avatar" so kids see that this is where they change how they look (and also see their friends' avatars).

## Context
- Labels: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoStrings.kt:18-19`
  - `NAV_MAP = "Karta"`, `NAV_FRIENDS = "Kompisar"`.
  - `:42` `TUTORIAL_CONTINUE_TO_MAP_BTN = "Gå till kartan"`: the same "map" wording, shown at the end of the tutorial.
- Nav bar: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:336-362`. The first item uses `icon = LixoIcons.Home`, `label = LixoStrings.NAV_MAP`. The second uses `LixoIcons.Friends`, `NAV_FRIENDS`, and goes to `AVATAR_MAP` (it is also selected on `WARDROBE`).
- Icons: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoIcons.kt:38` (`Home`). Icons are single-color 24×24 path strings built by `vector(name, pathData)` (`:15`, **EvenOdd** fill, so overlapping subpaths cut holes). `LixoIcons.Book` (`:43`) already exists but is a closed book used for the Swedish subject, so don't reuse it for the tab.
- Inspiration: the launcher icon `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`. Its key motifs are "7. Open Magical Storybook" (line ~163), the wizard hat star emblem (~152) and "Magic Reading Sparkles" (~224).
- `NAV_MAP` is only referenced in `LixoApp.kt:340`. `NAV_FRIENDS` is referenced in `LixoApp.kt:346` and `androidApp/src/androidTest/kotlin/se/lixo/app/screenshots/ScreenshotTest.kt:104` (by constant, so it follows the rename).
- Internal names (`LixoMainDestination.MAP_HOME`, `AVATAR_MAP`, `CampaignMapScreen`) are not visible to users. Leave them alone.

## Steps
1. **Strings** (`LixoStrings.kt`):
   - Rename `NAV_MAP` → `NAV_LEARN = "Lär dig"`.
   - Rename `NAV_FRIENDS` → `NAV_AVATAR = "Avatar"`.
   - Change `TUTORIAL_CONTINUE_TO_MAP_BTN` value to `"Börja lära dig"` (keep the constant name, or rename it to `TUTORIAL_CONTINUE_BTN` and update its usages via grep).
   - Update the usages in `LixoApp.kt` and `ScreenshotTest.kt`.
2. **New icon** (`LixoIcons.kt`): add `val LearnBook = vector("learn_book", …)`, an **open** storybook (two pages fanning out from a center spine) with a small 4- or 5-point star/sparkle above the spine, echoing the launcher's open book plus star. It must read clearly at 24dp in one color. Starting point (tune by eye in the screenshot):
   ```
   M2,7.5c2.2,-1.3 5.6,-1.5 9,0.6v12.4c-3.4,-2.1 -6.8,-1.9 -9,-0.6z
   M22,7.5c-2.2,-1.3 -5.6,-1.5 -9,0.6v12.4c3.4,-2.1 6.8,-1.9 9,-0.6z
   M12,0.8l1,2.2 2.3,0.3 -1.7,1.5 0.5,2.3 -2.1,-1.2 -2.1,1.2 0.5,-2.3 -1.7,-1.5 2.3,-0.3z
   ```
   Keep the subpaths from overlapping (EvenOdd). Remove `LixoIcons.Home` only if a grep shows no other users.
3. **Nav bar** (`LixoApp.kt:338-349`): first item `icon = LixoIcons.LearnBook`, `label = LixoStrings.NAV_LEARN`. Second item `label = LixoStrings.NAV_AVATAR`, and keep the `LixoIcons.Friends` icon (it still covers "see friends' avatars"; see open question 2).
4. Check that the 4-item nav bar still fits "Lär dig" on one line on a narrow phone (no wrap or ellipsis). If it wraps, fix it in `LixoBottomNavBar` (`LixoComponents.kt:464`) with `maxLines = 1` plus `softWrap = false`.

## Tests / verification
- **Instrumented** (new): `androidApp/src/androidTest/kotlin/se/lixo/app/verify/BottomNavLabelsTest.kt`, following the `ScreenshotTest.kt` pattern (`createComposeRule()`, `AndroidPlatformContext.init(ctx)`, `LixoApp(repository = DummyData.populatedRepository())`).
  - `bottomNav_showsLearnAndAvatarLabels`: go past profile select (click `MAP_PLAY_LEVEL_BTN` as ScreenshotTest does). Assert `onNodeWithText("Lär dig")` and `onNodeWithText("Avatar")` exist, and `"Karta"` and `"Kompisar"` do not. Capture `onRoot().captureToImage()` → `temp/verify013/bottom_nav.png` so the new icon can be checked by eye.
  - `avatarTab_opensAvatarMap_learnTab_returns`: click "Avatar", assert `LixoStrings.AVATAR_MAP_TITLE` exists. Click "Lär dig", assert a campaign-map element exists (e.g. a `WORLD_*_TITLE`).
  - Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.BottomNavLabelsTest,se.lixo.app.screenshots.ScreenshotTest`
- `ScreenshotTest.mainFlow` must still pass after the rename.
- No commonTest change needed (strings and icon only). Run the existing unit tests to confirm nothing breaks.

## Open questions
1. Label wording: the default is **"Lär dig"**. Alternatives: "Äventyr", "Sagobok", "Utmaning". Change the value in one place if preferred.
2. Avatar tab icon: the default keeps the two-person `Friends` icon. An alternative is a single head with a wizard hat (from the launcher icon), to stress "change your look".
