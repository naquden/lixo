# 044 — Remove "Ditt kompisnamn: @…" + "Slumpa" row from own avatar podium

**Type**: feature

## Goal
The avatar map shows "Ditt kompisnamn: @SnabbGroda67" with a "Slumpa" button under the child's own avatar. Friends add each other by QR code or 8-digit code, so the nickname is never typed or shared by the child, and the row adds nothing. Remove it.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/social/AvatarMapScreen.kt:1143-1218`: `OwnAvatarPodium`. After the "Jag · <name>" pill it has `Spacer(6.dp)` + a `Row` holding the nickname `Text` (`AVATAR_MAP_NICKNAME_PREFIX`) and a GHOST `LixoButton` (`AVATAR_MAP_SHUFFLE_NICKNAME_BTN`) that calls `onShuffleNickname()`.
- `AvatarMapScreen.kt:113`: the screen parameter `onShuffleNickname: suspend () -> Result<Nickname>`. It is passed to the podium at `:220-225`.
- Call sites: `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:546` and `androidApp/src/androidTest/kotlin/se/lixo/app/verify/FriendTestSupport.kt:143`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoStrings.kt:180-181`: `AVATAR_MAP_NICKNAME_PREFIX`, `AVATAR_MAP_SHUFFLE_NICKNAME_BTN`.
- `Repositories.kt:782` `shuffleNickname()`: after this change no UI calls it.
- **Keep the nickname itself.** Friends still see the generated `@nick` on friend plots, cards and the reveal/label step (`AvatarMapScreen.kt:534, 862, 1281, 1290`), and it is the default when no label is set. `FriendLabelTest`/`FriendRevealTest` rely on it. Only the child's own view of it goes away.

## Steps
1. In `OwnAvatarPodium` (`AvatarMapScreen.kt`), delete the trailing `Spacer(Modifier.height(6.dp))` and the whole `Row { Text(nickname); Spacer; LixoButton(Slumpa) }`. Remove the `onShuffleNickname` parameter and the now-unused `rememberCoroutineScope()`. If the outer `Column` then wraps only the clickable inner `Column`, flatten it. Update the KDoc ("…with a name pill + "Slumpa" below it" → "…with a name pill below it").
2. Remove the `onShuffleNickname` parameter from the `AvatarMapScreen` composable (`:113`) and from the `OwnAvatarPodium(...)` call (`:220-225`).
3. Remove `onShuffleNickname = …` from `LixoApp.kt:546` and `FriendTestSupport.kt:143`.
4. Delete `AVATAR_MAP_NICKNAME_PREFIX` and `AVATAR_MAP_SHUFFLE_NICKNAME_BTN` from `LixoStrings.kt`.
5. Delete `shuffleNickname()` from `Repositories.kt` (and its KDoc mentions at `:204`, `:586`) if nothing else references it. Grep commonTest/androidTest/iosTest first; if a test uses it, keep the function and leave the test alone. Drop any imports this leaves unused (e.g. `Nickname`, `launch` in AvatarMapScreen.kt) only if they really are unused.
6. Grep `doc/` for "Slumpa"/"kompisnamn" UI descriptions (e.g. `doc/friends.md`) and update them so they no longer describe the podium row. Do not touch old plan files.

## Tests / verification
- Build: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug` plus host/commonTest (`./gradlew :composeApp:testDebugUnitTest` or the project's usual unit-test task) stay green.
- New instrumented test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/OwnPodiumNoNicknameTest.kt` (fqcn `se.lixo.app.verify.OwnPodiumNoNicknameTest`). Use the `FriendTestSupport` setup to render the real `AvatarMapScreen` with a real repository and a profile that has a generated nickname, then:
  - `onNodeWithText("Ditt kompisnamn", substring = true).assertDoesNotExist()`
  - `onNodeWithText("Slumpa").assertDoesNotExist()`
  - `onNodeWithText("@" + nickname.render(...)).assertDoesNotExist()` (own nickname not shown anywhere on own podium)
  - the own "Jag · <name>" pill still exists, and tapping it still opens the wardrobe (callback fired)
  - capture `onRoot().captureToImage()` → `temp/verify/044/own_podium.png`
- Regression: `se.lixo.app.verify.FriendLabelTest`, `se.lixo.app.verify.FriendRevealTest`, `se.lixo.app.verify.FriendCodeFlowTest` (friends still show `@nick`).
- Run on the Pixel 10 emulator (unlock PIN 111111 if locked): `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.OwnPodiumNoNicknameTest,se.lixo.app.verify.FriendLabelTest,se.lixo.app.verify.FriendRevealTest,se.lixo.app.verify.FriendCodeFlowTest`

## Open questions
