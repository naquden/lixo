# 042 — iOS support (full parity)

**Type**: feature

**Goal**: Make Lixo build and run on iOS with feature parity to Android: a reproducible Xcode project (XcodeGen), Firebase via SPM, real Swedish speech recognition and TTS, QR scanning, `lixo://friend` deep links, App Check, and the trusted server time/clock. Verify with Compose UI tests on the iOS simulator (`iosSimulatorArm64Test`) plus a simulator build/launch.

## Context

- iOS targets declared in `composeApp/build.gradle.kts:36-47` (static framework `ComposeApp`, Room KSP for 3 iOS targets) but **disabled locally** (`local.properties: kmp.ios.enabled=false`) → iOS code never compiled recently, likely drift.
- `iosApp/iosApp/` has `iOSApp.swift`, `Info.plist`, entitlements, icons, `GoogleService-Info.plist` but **no `.xcodeproj`**. Xcode 27.0 + iPhone 17/18 simulators present; `xcodegen` not installed.
- `composeApp/src/iosMain/.../PlatformIos.kt` stubs: STT fake (no recognizer), TTS `isSpeaking` wrong (set true→false synchronously), `fetchHttpServerTimeMillis` = null, `platformBootId` = null, QR = Unavailable, debug preview file = null.
- Missing vs Android `MainActivity.kt`: `lixo://friend/<code>` deep link (`FriendDeepLinkDispatcher`), App Check provider install, `lixo` URL scheme in Info.plist, `NSCameraUsageDescription`.
- Privacy: Documents dir is iCloud-backed-up by default, but 041 review (commit 8ee65d6) established child data is never uploaded to cloud backup → must exclude.
- Shared helpers to reuse: `SttRecognitionHelpers.kt` (`tokenizeSttTranscript`, `parseSpeechBundle`, `buildBiasStrings`), `BalanceConfig.STT_*`, `EmailLinkAuthDispatcher`, `FriendDeepLinkDispatcher` (`PlatformContracts.kt`).

## Steps

**A. Make iOS build**
1. Set `kmp.ios.enabled=true` in local `local.properties` (not committed). Run `./gradlew :composeApp:compileKotlinIosSimulatorArm64` and fix every compile error in commonMain/iosMain (JVM-only APIs, missing actuals; Room KSP generates `LixoDatabaseConstructor` actual). Keep Android unaffected.
2. Also `linkDebugFrameworkIosSimulatorArm64` must pass.

**B. Xcode project via XcodeGen**
3. Add `iosApp/project.yml`: app target `iosApp`, bundle id `se.lixo.app`, iOS 16+ deployment, `Info.plist` + `iosApp.entitlements`, `DEVELOPMENT_TEAM` from `$(LIXO_TEAM_ID)` (empty OK for simulator), SPM package `firebase-ios-sdk` (version compatible with GitLive 2.1.0 — check GitLive README) products FirebaseCore, FirebaseAuth, FirebaseFirestore, FirebaseCrashlytics, FirebaseAppCheck; pre-build script `cd "$SRCROOT/.." && ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`; `FRAMEWORK_SEARCH_PATHS=$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`, `OTHER_LDFLAGS=-framework ComposeApp`, `ENABLE_USER_SCRIPT_SANDBOXING=NO`; post-build Crashlytics dSYM upload script (release only).
4. `.gitignore`: `iosApp/iosApp.xcodeproj/` (generated). Add `doc/ios.md`: `brew install xcodegen`, `cd iosApp && xcodegen generate`, `kmp.ios.enabled=true`, build/run commands, App Check debug token registration.

**C. App wiring (Swift + MainViewController.kt)**
5. `MainViewController.kt`: add `handleIncomingUrl(url): Boolean` = `FriendDeepLinkDispatcher.onIncomingLink(url) || EmailLinkAuthDispatcher.onIncomingLink(url)`; keep `handleIncomingEmailSignInLink` delegating to it.
6. `iOSApp.swift`: route `onOpenURL`/`onContinueUserActivity`/AppDelegate handlers through `handleIncomingUrl`; configure Firebase once (remove duplicate configure in `init` vs AppDelegate — keep AppDelegate); before `FirebaseApp.configure()` install App Check: `#if DEBUG AppCheckDebugProviderFactory` else `AppAttestProviderFactory` (iOS 14+, DeviceCheck fallback). Enforcement stays off (same as Android 030 §2a).
7. `Info.plist`: add URL type with scheme `lixo`; `NSCameraUsageDescription` (Swedish, "skanna kompiskod, ingen bild sparas"); keep mic/speech descriptions.

**D. Platform actuals (`PlatformIos.kt`, split into files per concern if > ~400 lines)**
8. **STT** `IosSpeechRecognitionEngine` on `SFSpeechRecognizer(locale sv-SE)` + `AVAudioEngine` input tap → `SFSpeechAudioBufferRecognitionRequest` (`shouldReportPartialResults=true`, `requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition`, `contextualStrings = buildBiasStrings(expectedWords, upcomingWords)`, `addsPunctuation=false`, taskHint dictation). Results: N-best = `result.transcriptions` (take `BalanceConfig.STT_MAX_RESULTS`) → strings; scores = avg segment confidence of top transcription, null when all 0 (partials) → feed `parseSpeechBundle(matches, scores, emittedInUtterance, now)`; extract a pure `iosTranscriptionsToTokens(transcripts: List<List<Pair<String,Float>>>, alreadyEmitted, now)` in iosMain for unit tests. Mirror Android state machine (`PlatformAndroid.kt:199-520`): permission via `SFSpeechRecognizer.requestAuthorization` + `AVAudioSession.requestRecordPermission` (denied → `ERROR_MIC_PERMISSION_DENIED`); recognizer null/unavailable → `ERROR_ENGINE_UNAVAILABLE`; on final result/no-speech error restart after 100/200 ms, other errors 1000 ms, resetting `emittedInUtterance`; proactive restart before Apple's ~1 min task limit (~50 s); `updateExpectedPosition` stores upcoming words for next restart; `cancelListening` stops engine, removes tap, cancels task, deactivates session, state IDLE. Audio session `.playAndRecord` + `defaultToSpeaker`/`allowBluetooth`. Callbacks hop to `Dispatchers.Main`. Audio buffers only appended to request, never stored. Keep `emitSimulatedWords`. `PlatformFeatureFlags.isOnDeviceSttSupported` = recognizer?.supportsOnDeviceRecognition.
9. **TTS** `IosTextToSpeechEngine`: `AVSpeechSynthesizerDelegateProtocol` (NSObject, strong ref) driving `isSpeaking` via didStart/didFinish/didCancel; prefer highest-quality installed voice for the language (`speechVoices()` filtered by language, sorted by quality); audio session `.playback` when STT is not active.
10. **QR** `IosQrScanner`: modal `UIViewController` presented from the top-most VC with `AVCaptureSession` + `AVCaptureMetadataOutput` (`AVMetadataObjectTypeQRCode`) + Avbryt button; `suspendCancellableCoroutine` → `Success(raw)` / `Cancelled`; no camera (simulator) or denied permission → `Unavailable` (UI already falls back to typed code). Update `doc/friends.md:83` and `PlatformContracts.kt` QR KDoc.
11. **Server time**: add commonMain pure `parseHttpDateMillis(header: String): Long?` (RFC 1123, `kotlinx-datetime`); iOS `fetchHttpServerTimeMillis` = `NSURLSession` HEAD, 5 s timeout, no cache, read `Date` header from `NSHTTPURLResponse.allHeaderFields`, parse, null on any failure. Android unchanged.
12. **Clock**: `platformMonotonicMillis` = `clock_gettime_nsec_np(CLOCK_MONOTONIC)/1e6` (counts across sleep on Darwin); `platformBootId` = `sysctlbyname("kern.boottime")` seconds as string. Update KDocs in `PlatformContracts.kt:136-150`.
13. **Debug preview**: `readDebugContentPreviewFile` reads `Documents/content_pack_preview.json` when `isDebugBinary`.
14. **Backup privacy**: move DB to `Application Support/lixo.db` (create dir) and set `NSURLIsExcludedFromBackupKey=true` on the dir, matching 041's "no cloud backup of child data". (Fresh iOS installs only — no existing iOS users.)

**E. iOS test infra**
15. Add `iosTest` deps: `compose.uiTest` (`@OptIn(ExperimentalTestApi::class)`, `runComposeUiTest`), `kotlin.test`. Test binary links GitLive → needs Firebase symbols: add a Gradle task that downloads the pinned Firebase iOS XCFramework zip into `build/firebase-ios/` and add `linkerOpts("-F…", "-framework", "FirebaseCore", …)` to `iosSimulatorArm64` test binaries only. If linking proves infeasible, fall back to an XCUITest target in `project.yml` (record in plan result).

## Tests / verification

- commonTest: `HttpDateParseTest` (valid RFC 1123, garbage → null).
- iosTest (`./gradlew :composeApp:iosSimulatorArm64Test`): `IosSttTokensTest` (N-best → alternatives, zero confidences → default, alreadyEmitted drop), `IosDeepLinkRoutingTest` (`handleIncomingUrl` routes lixo://friend vs email link vs junk), `IosSmokeUiTest` (`runComposeUiTest { LixoTheme { first-profile setup / map screen } }` with real engines, assert key texts with `onNodeWithText`), `IosQrUnavailableTest` (simulator → Unavailable, friend screen shows typed-code field), `IosClockTest` (bootId stable across calls, monotonic non-decreasing).
- Verify target: booted simulator iPhone 18 Pro (iOS 27.0, UDID A0905929-3F49-472C-AAC6-4E5CC10AE05D) only.
- Build: `cd iosApp && xcodegen generate && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,id=A0905929-3F49-472C-AAC6-4E5CC10AE05D' build`; boot sim, `xcrun simctl install/launch se.lixo.app`, `xcrun simctl io booted screenshot temp/verify/042/launch.png`; `xcrun simctl openurl booted lixo://friend/ABC123` → screenshot shows Avatar tab "Lägg till kompis" prefilled.
- Android regression: `./gradlew :androidApp:assembleDebug`, host tests, `FriendDeepLinkTest`, `SttRecognizerConfigTest` on R5CXC0681EM.
- **Out of scope for this task:** no physical iPhone install or testing (simulator only; `DEVELOPMENT_TEAM` may stay empty). **TTS is implemented but not verified** (simulator audio is unreliable) — no TTS tests or TTS checks in the verify round; only make sure it compiles.
- When running via `model-split-implement`, this task needs an iOS-simulator verify note instead of `android-instrumented` (Android regression still runs on R5CXC0681EM).

## Open questions

- Excluding DB from iCloud backup also means no iOS→iOS transfer of progress (unlike Android 041). OK, or allow encrypted device-to-device only? (Default: exclude.)
- `model-split-implement` verifyMode is `android-instrumented`; this task needs an iOS-simulator verify note when run.
