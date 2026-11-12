# 048 — Set app version to 0.1.0

- **Type**: feature
- **Goal**: Set the user-visible app version to `0.1.0` on Android and iOS.

## Context

- Android: `androidApp/build.gradle.kts:21-22`, where `versionCode = 1` and `versionName = "1.0.0-mvp"`.
- iOS: `iosApp/iosApp/Info.plist:23-26`, where `CFBundleShortVersionString = 1.0.0` and `CFBundleVersion = 1`. `iosApp/project.yml` does not set `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`, so the plist values are used.
- The build number is read at runtime and stored in profile backups as `appVersionCode`:
  - Android: `composeApp/src/androidMain/kotlin/se/lixo/app/platform/PlatformAndroid.kt:194` reads it from PackageInfo.
  - iOS: `composeApp/src/iosMain/kotlin/se/lixo/app/platform/PlatformIos.kt:66` reads `CFBundleVersion`.
  - Backups: `ProfileBackup.kt:99`, `Repositories.kt:620`.
- No UI shows the version name. No other code or doc references `1.0.0-mvp`.

## Steps

1. `androidApp/build.gradle.kts`: change `versionName = "1.0.0-mvp"` to `versionName = "0.1.0"`. Leave `versionCode = 1` unchanged. versionCode must never go down, or installed apps can't be updated in place, and backup `appVersionCode` values stay consistent.
2. `iosApp/iosApp/Info.plist`: change `CFBundleShortVersionString` from `1.0.0` to `0.1.0`. Leave `CFBundleVersion` at `1`.
3. Run a grep for `1.0.0-mvp` and for the `1.0.0` app version in `doc/` (except `doc/plans/`), `README*` and `iosApp/`, and update any app-version mention to `0.1.0`. Do not touch dependency versions such as the foojay plugin `1.0.0` in `settings.gradle.kts`.
4. Add the instrumented test `androidApp/src/androidTest/kotlin/se/lixo/app/verify/AppVersionTest.kt`. Use `InstrumentationRegistry.getInstrumentation().targetContext`, get `packageManager.getPackageInfo(packageName, 0)`, and assert `versionName == "0.1.0"` and `PackageInfoCompat.getLongVersionCode(info) == 1L`.

## Tests / verification

- Build: `./gradlew :composeApp:testAndroidHostTest :androidApp:assembleDebug` and `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- Instrumented (emulator-5554): `se.lixo.app.verify.AppVersionTest` (new), 1/1. Also save the output of `adb -s emulator-5554 shell dumpsys package se.lixo.app | grep -E "versionName|versionCode"` to `temp/verify/048/dumpsys.txt`. It should show `versionName=0.1.0` and `versionCode=1`.
- iOS: `cd iosApp && xcodegen generate && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'id=A0905929-3F49-472C-AAC6-4E5CC10AE05D' build`. Then save the output of `/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" <built .app>/Info.plist` to `temp/verify/048/ios_version.txt`. It should print `0.1.0`.

## Open questions

- Keep the build number at `1` (versionCode / CFBundleVersion)? This plan assumes yes. Bump it only if a build with code 1 has already been shipped to a store or tester track and this needs to be a new upload.
