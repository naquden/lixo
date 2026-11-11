# iOS

Simulator-only support (doc/plans/042). No physical-iPhone install/testing is in scope; TTS is
implemented but not verified (simulator audio is unreliable).

## Prerequisites

- Xcode (with a selected command-line tools path: `xcode-select -p`).
- `brew install xcodegen` — generates `iosApp/iosApp.xcodeproj` from `iosApp/project.yml` (the
  `.xcodeproj` is gitignored, generated on demand).
- `kmp.ios.enabled=true` in `local.properties` (this is the default; set it to `false` only to
  skip iOS targets, e.g. on Windows/Linux).
- A JDK on `PATH`: Xcode's pre-build script shells out to `./gradlew` to build the Kotlin
  framework.

## Generate the Xcode project

```sh
cd iosApp && xcodegen generate
```

Re-run this whenever `iosApp/project.yml` changes.

## Simulator build

```sh
cd iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' \
  -derivedDataPath ../build/ios-dd build
```

Install and launch on a booted simulator:

```sh
xcrun simctl install <SIMULATOR_UDID> build/ios-dd/Build/Products/Debug-iphonesimulator/Lixo.app
xcrun simctl launch <SIMULATOR_UDID> se.lixo.app
```

A cold build (including the SPM checkout of `firebase-ios-sdk`) takes about 2.5 minutes.

## Tests

```sh
./gradlew :composeApp:iosSimulatorArm64Test
```

The first run downloads about 245 MB of Firebase 11.15.0 Carthage frameworks into
`build/firebase-ios/11.15.0`, via `tools/ios/fetch-firebase-test-frameworks.sh`. They are linked
into the `iosSimulatorArm64` test binary only — the shipped app gets Firebase from SPM instead
(`iosApp/project.yml`). The downloaded version **must match** `project.yml`'s `exactVersion` for
the `Firebase` SPM package; GitLive 2.1.0 was built against Firebase iOS 11.1.0 headers, and
11.x is ABI-compatible, so do not bump to 12.x without re-verifying the test link.

## App Check

Enforcement stays off (same as Android, doc/plans/030 §2a). In debug builds the
`AppCheckDebugProviderFactory` prints a line like:

```
[AppCheckCore] App Check debug token: <uuid>
```

Register that token in Firebase Console → App Check → Apps → iOS app → "Manage debug tokens".

For release, App Attest (`DCAppAttestService.shared.isSupported`) is used where available,
falling back to DeviceCheck. Adding the App Attest capability
(`com.apple.developer.devicecheck.appattest-environment`) requires a paid Apple Developer team
and is not added yet — `DEVELOPMENT_TEAM` may stay empty for simulator-only work.

## Privacy

The Room database lives under `Application Support`, with the directory marked
`NSURLIsExcludedFromBackupKey` — same stance as Android 041 (commit 8ee65d6): child data never
leaves the device via a cloud/iCloud backup. One consequence: there is no iOS-to-iOS progress
transfer via iCloud backup (unlike Android's Google-account backup transfer).

## Deep links

`xcrun simctl openurl <SIMULATOR_UDID> 'lixo://friend/ABC123'` shows a system "Open in
“Lixo”?" confirmation dialog (Cancel/Open) before the app receives the URL — this cannot be
confirmed by screenshot alone without tapping through the dialog. The routing itself is covered
by `IosDeepLinkRoutingTest` (`composeApp/src/iosTest`), which calls
`MainViewControllerKt.handleIncomingUrl` directly and renders the real `LixoApp`.

## Team ID

`DEVELOPMENT_TEAM` comes from the `LIXO_TEAM_ID` environment/build-setting variable
(`iosApp/project.yml`); it may stay empty for simulator builds.
