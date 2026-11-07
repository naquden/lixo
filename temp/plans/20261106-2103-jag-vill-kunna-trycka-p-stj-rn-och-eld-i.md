# Plan: Tappable star and streak (flame) icons in the map toolbar with info dialogs

## Summary

On the map screen's top HUD (`ui/screens/map/CampaignMapScreen.kt:181-195`), the ⭐ (total stars) and 🔥 (streak) `StatPill`s are display-only today. Make each one tappable so it opens a child-friendly info dialog:

- **Star dialog**: total stars, stars per world (Svenska / Matte / Engelska, earned / possible), how stars are earned (reading and math rules), and the next star goal (next avatar unlock).
- **Streak dialog**: current streak, best streak, the rule (1 reading task + 1 math task per calendar day), today's status (what's left for today), and the next weekly reward (every 7 days).

`StatPill` (`ui/theme/LixoComponents.kt:306`) already takes `onClick: (() -> Unit)?`, so no component change is needed. The dialogs follow the existing overlay style in `ui/components/celebration/UnlockDialogs.kt` (scrim `Box` + `LixoCard` + `LixoButton`) and close with tap-outside, back (`PlatformBackHandler`), and a "Stäng" button. All text is in Swedish, in `LixoStrings`.

**Note**: workflow `wf_136b7eff-989` (FEATURES tasks 001–004) is running. Task 003 adds a holo chest meter to the **same HUD Row**, and task 004 adds star achievements. Implement this **after that workflow finishes**, on top of its changes: keep the chest meter as the last (top-right) item, and add a "next star achievement" line if 004 landed.

## 1. Pure info model (testable, no UI)

### Change
New file `ui/screens/map/HudInfoModel.kt` (pure functions, no Compose):
- `data class StarInfo(total: Int, perSubject: List<SubjectStars>, nextAvatar: AvatarType?, starsToNextAvatar: Int?)`, with `SubjectStars(subject, earned, possible)`.
  - `fun buildStarInfo(profile, levels, progressByLevelId): StarInfo`
  - `earned` = sum of `progressByLevelId[level.id].starsEarned` for levels of that subject. `possible` = `levels.count { subject } * 3`, the same formula as `CampaignMapScreen.kt:467`. Reuse it by extracting that expression into this helper and calling it from both places.
  - `nextAvatar` = the lowest `AvatarType.entries` with `unlockStarsRequired > profile.totalStars` that isn't in `profile.unlockedAvatars`.
- `data class StreakInfo(current: Int, best: Int, readingDoneToday: Boolean, mathDoneToday: Boolean, countedToday: Boolean, daysToWeeklyReward: Int)`
  - `fun buildStreakInfo(profile, todayActivity: DailyActivityRecord?, todayIso: String): StreakInfo`
  - `readingDoneToday` / `mathDoneToday` use the same thresholds as `ProgressionAndGiftEngine.evaluateCalendarDayStreak` (`BalanceConfig.STREAK_REQUIRED_READING_TASKS_PER_DAY` / `..._MATH_...`, `ProgressionAndGiftEngine.kt:341-342`). Extract that check into a small shared function (e.g. `BalanceConfig`-adjacent `fun qualifiesForStreakDay(activity)`) and use it from both the engine and this model, so the rule isn't duplicated.
  - `countedToday` = `profile.lastStreakDateIso == todayIso`.
  - `daysToWeeklyReward` = `WEEKLY_STREAK_DAYS_FOR_SET_REWARD - (current % WEEKLY_STREAK_DAYS_FOR_SET_REWARD)` (7 when current is 0 or a multiple of 7 that's already counted).

### Verification
New unit tests in `composeApp/src/commonTest/kotlin/se/lixo/app/HudInfoModelTest.kt`:
- Star info: per-subject sums and possible counts are correct for a mixed progress map. `nextAvatar` = DOG at 0–11★, KNIGHT at 12★ (DOG unlocked), and null when all are unlocked.
- Streak info: no activity → neither done; reading only → `readingDoneToday` only; both → both done. `countedToday` follows `lastStreakDateIso`. `daysToWeeklyReward` for streaks 0, 3, 6, 7.
- The existing streak tests in `LixoEnginesTest.kt` still pass after the shared-function refactor.

## 2. Strings

### Change
In `ui/theme/LixoStrings.kt`, add constants and functions (no hard-coded text in composables):
- Stars: title "Dina stjärnor", body "Du får 1–3 stjärnor på varje bana.", reading rule "Läsning: läs i AI-läge i lagom tempo för fler stjärnor. Läser du själv (manuellt läge) får du 1 stjärna.", math rule "Matte: 1 stjärna för att klara banan, +1 om du är snabb, +1 om du svarar rätt utan fel.", replay "Spela om en bana för att förbättra dina stjärnor.", and `nextAvatar(name, starsLeft)` "Om N stjärnor till låser du upp X!". Use the existing `LixoStrings.avatarName(type)`.
- Streak: title "Din streak", `streakDays(n)` (reuse `MAP_STREAK_LABEL` "dagar", with "dag" for n == 1), `bestStreak(n)` "Bästa: N dagar", rule "Klara en läsuppgift och en matteuppgift samma dag för att hålla elden vid liv!", today checklist labels "Läsning idag" / "Matte idag", `countedToday` "Dagen är räknad! Kom tillbaka i morgon.", `weeklyReward(days)` "N dagar kvar till veckans belöning", and close "Stäng".
- Check the reading star wording against `BalanceConfig` (`READING_THREE_STAR_WPM_RATIO`, `MANUAL_MODE_MAX_STARS`) and the math formula against `MathEngine.kt:72-74` (1 + time bonus + no-wrong bonus). Keep the wording simple for ages 6–12.

### Verification
It compiles, and the unit test from §1 can assert `streakDays(1)` == "1 dag" and `streakDays(3)` == "3 dagar".

## 3. Dialog composables

### Change
New file `ui/screens/map/HudInfoDialogs.kt`:
- `@Composable fun StarInfoDialog(info: StarInfo, onClose: () -> Unit)` and `@Composable fun StreakInfoDialog(info: StreakInfo, onClose: () -> Unit)`.
- Layout follows `ItemUnlockedDialog` (`ui/components/celebration/UnlockDialogs.kt:55`): a full-screen `Box` with `CelebrationScrim` (tap on the scrim → `onClose`), a centered `LixoCard` (24 dp radius), a big icon at the top (`LixoIcons.Star` tinted `LixoColors.GoldLip` / `LixoIcons.Flame` tinted `LixoColors.Orange`, ~48 dp), a title, the big number, the content, and a `LixoButton` "Stäng" at the bottom. Tapping inside the card must not close it (consume clicks on the card).
- Star content: per-world rows (world name + `earned/possible ⭐` + a thin `LixoProgressBar`), then the rule texts, then the next-avatar line (hidden if null).
- Streak content: current + best, the rule text, a two-row checklist (✓ green / empty circle) for reading/math today, either the `countedToday` line or "what's left", and the weekly reward line.
- `PlatformBackHandler(enabled = true) { onClose() }` (`se.lixo.app.platform.PlatformBackHandler`, as used in `CelebrationSequence.kt`).
- Pop-in animation optional: a light scale-in (0.9 → 1) like the existing dialogs. A haptic tick on open via `rememberLixoHaptics()` if a light cue exists.

### Verification
Compiles. Covered visually in §5.

## 4. Wire up the HUD

### Change
- `CampaignMapScreen` (`ui/screens/map/CampaignMapScreen.kt:95`): add params `todayActivity: DailyActivityRecord?` and `todayIso: String`. Add state `var hudDialog by remember { mutableStateOf<HudDialog?>(null) }` (`enum class HudDialog { STARS, STREAK }`).
- In the right HUD Row (~line 185-194), pass `onClick = { hudDialog = HudDialog.STARS }` / `STREAK` to the two `StatPill`s. Keep the order stars → streak → (003's chest meter last, if present).
- At the end of the root `Box` (so it draws above the map, level sheet, etc.), render `StarInfoDialog(buildStarInfo(...))` / `StreakInfoDialog(buildStreakInfo(...))` when `hudDialog` is set, and `onClose = { hudDialog = null }`. Build the info inside `remember(profile, levels, progressByLevelId)` / `remember(profile, todayActivity)`.
- `ui/LixoApp.kt:327`: pass `todayIso = repository.currentLocalDateIso()` and `todayActivity = dailyActivityByProfile[activeProfile.id]?.get(todayIso)` (the same lookup as `LixoApp.kt:180-182`).
- Accessibility: give the pills a `contentDescription`-style semantics label when clickable ("Stjärnor: N, tryck för mer info" / "Streak: N dagar, tryck för mer info"). Add an optional `contentDescription` param to `StatPill` if needed, defaulting to null so the other call sites (`AvatarMapScreen`, `ProfileSelectAndTutorialScreen`) are unchanged.
- Other `StatPill` call sites stay non-clickable (out of scope).

### Verification
- Full commonTest suite passes (`./gradlew :composeApp:allTests` or the host test task in use).
- `./gradlew :composeApp:assembleDebug` (or the Android build task) succeeds.

## 5. App verification (Pixel)

### Verification
Install the debug build. On the map screen (use real view-hierarchy bounds for taps, not coordinates estimated from a screenshot):
1. Tap ⭐ → the star dialog shows the total, matching the HUD number, 3 world rows, the rules, and the next avatar line. Screenshot.
2. Tap outside the card → it closes. Open it again → system back → it closes. Open again → "Stäng" → it closes.
3. Tap 🔥 → the streak dialog shows current/best, the today checklist, and the weekly reward line. Screenshot.
4. Finish one reading level, return to the map, and tap 🔥 → "Läsning idag" is checked and math isn't. Finish one math level → both checked, plus the "Dagen är räknad" text.
5. Tapping inside the card doesn't close it, and the level sheet / map underneath doesn't react while a dialog is open.

## Order of work

1. Wait for workflow `wf_136b7eff-989` (tasks 001–004) to finish, so these edits land on top of its HUD changes.
2. §1 Pure model + shared streak-day function + unit tests.
3. §2 Strings.
4. §3 Dialog composables.
5. §4 HUD wiring + `LixoApp` params.
6. Run the tests and the build, then §5 app verification with screenshots.
7. Add as a task to `FEATURES.md` if it should run in a batch (per the project CLAUDE.md flow).
