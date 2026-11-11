# Kompisbyn: friends (030)

Implements `doc/plans/030-friends-that-work.md`. This is the reference doc for the shipped
feature: data model, rules, invite lifecycle, QR payload, what's local vs. cloud, and App Check
notes for device/CI setup.

## Identity

Every install signs in with **Firebase Anonymous Auth** (`CloudSyncGateway.ensureSignedIn`,
`LixoRepository.ensureCloudIdentity`). No email/account is required for friends to work. The
optional email-link account (for backup/restore) is a separate, currently-hidden feature — its UI
in `ParentGateAndDashboardScreen.kt` is commented out behind `if (false)` (code kept, not deleted)
so a parent can't accidentally replace the anonymous user and orphan every friend card. The
anonymous uid is cached in `AppSettingsEntity.cloudUid` and retried lazily if the startup sign-in
was offline.

## Data model (Firestore)

```
friend_cards/{cardId}                      // random 20-char id, one per child profile
  { ownerUid, nick: [adjIdx, animalIdx, num], appearance }
friend_cards/{cardId}/readers/{readerUid}  // presence doc = readerUid may read the card
  { readerCardId, viaInvite }
friend_invites/{code}                      // 8-char Crockford base32, multi-use, 10-min window
  { cardId, ownerUid, expiresAt, uses }     // uses = redeems so far, capped at 30
```

- A card holds **no free text, no stars, no timestamps** — only three small integers (`nick`)
  indexing into the content pack's `nicknameWords` word list, plus the avatar/appearance.
- `friend_cards` access: `get` is the owner, or anyone with a `readers/{myUid}` doc on that card.
  `list` is denied everywhere — card ids are random, and nobody can enumerate cards.
- See `firebase/firestore.rules` for the exact field whitelist/regex (`avatarId`, `equipped` key
  pattern, `equipped.size() <= 9`) and the invite/reader write rules (10-minute window, atomic
  "bump `uses` + create a reader doc" transaction, max 30 uses, no re-redeeming).

## Invite lifecycle (no accept step)

Scanning a QR/typing a code **in person** is the only proof two kids want to be friends — there is
no request/approval screen. Redeeming makes the friendship mutual immediately.

1. `LixoRepository.createFriendCode()` opens (or returns the still-open) 10-minute, multi-use
   invite for the active profile's card. Reopening the "Min kompiskod" sheet within the window
   shows the *same* code.
2. Any number of people (capped at 30) can redeem the same code within the window.
   `LixoRepository.addFriendByCode(raw, fromQr)` parses the code/QR payload
   (`domain/social/FriendCodeParser.parseFriendCode`), calls `FriendCloud.redeemInvite`, and
   inserts the friend into Room on **both** sides immediately — no approval call in between.
3. `LixoRepository.stopFriendCode()` ("Stoppa koden") deletes the invite early.
4. After 10 minutes the code is dead; the UI shows a greyed-out QR with "Visa ny kod", which opens
   a fresh window.
5. `LixoRepository.observeNewFriendsDuring(code)` streams each new reader of my own card live
   while the "Min kompiskod" sheet is open, for the "Nya kompisar" list (with an immediate ✕
   remove). `LixoRepository.refreshFriends()` (called on every Avatar-tab open) picks up readers
   who redeemed while the sheet was shut, and drops friends whose card went `null` (unfriended).

### Reveal ("kompis-kistan")

A friendship is never "pending", but the **reveal animation** can wait: a new reader is inserted
with `FriendEntity.revealedAtEpochMillis = null` ("unseen") unless it's the redeemer's own side
(which is revealed immediately, right before the "Vad heter din kompis?" name step).
`LixoRepository.unrevealedFriends(profileId)` drives `ui/components/celebration/FriendRevealStage`
at the village level — this single stream naturally covers both "someone scanned my code while
the sheet was open" and "I'm picking up friends added while I was away", since both insert with
`revealedAtEpochMillis == null`. The "+" plot shows a "N nya kompisar!" badge until
`markRevealed` has been called for each.

## QR payload

`lixo://friend/<8-char-code>`, built by `domain/social/FriendCodeParser.buildFriendPayload` and
rendered with `io.github.alexzhirkevich:qrose`'s `rememberQrCodePainter`. `parseFriendCode`
accepts the same string from three sources — typed text, the deep link, and a scanned QR — strips
the prefix case-insensitively, removes `-`/spaces, upper-cases, and maps the easily-confused
`O`→`0`/`I`,`L`→`1` before validating against the 8-char Crockford-base32 alphabet
(`0-9A-HJKMNP-TV-Z`). There is **no Share button** anywhere — codes are only shown in person.

### QR scanning

`platform/PlatformContracts.kt` defines `QrScanner` (`scan(): QrScanResult` —
`Success`/`Cancelled`/`Unavailable`) as a plain interface, provided into `AvatarMapScreen` as a
constructor parameter (not a platform singleton resolved inside the screen) so instrumented tests
can inject a fake. Android's actual (`createPlatformQrScanner`) uses Google's
`play-services-code-scanner`, which needs **no `CAMERA` permission** (verified: the assembled
debug APK has no `CAMERA` entry). iOS uses an AVFoundation scanner (`IosQrScanner`, a modal
camera view with an "Avbryt" button, gated on `NSCameraUsageDescription`); it returns
`Unavailable` without a camera (e.g. the simulator) or without camera permission, and the UI
falls back to the typed code field + "Skriv koden i stället".

### Deep link

`androidApp/src/main/AndroidManifest.xml` has a `VIEW`/`DEFAULT`/`BROWSABLE` intent filter for
`scheme="lixo" host="friend"`. `MainActivity` forwards the incoming URI to
`platform.FriendDeepLinkDispatcher`, which `LixoApp.kt` observes to switch to the Avatar tab and
prefill the code into "Lägg till kompis" — **nothing is ever redeemed automatically**. This link
exists only so the system camera app can open Lixo when it scans a code; Lixo itself never
generates a shareable link. iOS registers the same `lixo` URL scheme in `Info.plist`
(`CFBundleURLTypes`) and routes every incoming URL (Universal Link, custom scheme, or
`onOpenURL`/`onContinueUserActivity`) through `MainViewController.kt`'s `handleIncomingUrl`,
which tries `FriendDeepLinkDispatcher` first, then `EmailLinkAuthDispatcher`.

## Local vs. cloud

| Data | Where | Notes |
|---|---|---|
| `friend_cards/*` (nick, appearance) | Cloud (Firestore) | Friends-only read, owner-only write |
| `friend_invites/*` | Cloud | 10-min TTL, deleted on stop/expiry cleanup |
| Friend list, cached nick/appearance | Room (`FriendEntity`) | Last-known gear, works offline |
| **Private label** (`FriendEntity.label`) | **Room only, device-local** | Never sent to any `FriendCloud`/gateway method, never logged. Never in Google cloud backup either (`data_extraction_rules.xml` excludes everything from `<cloud-backup>`; `backup_rules.xml` excludes everything on Android 8–11). The only way it leaves the phone is Android's own device-to-device transfer (Android 12+, old phone → new phone directly, never via Lixo/Firebase servers or Google Drive), and even then it is dropped on first launch, see "Phone transfer" below. The parent list shows it next to the real nickname, e.g. "Kalle (@ModigRäv42)". |
| `revealedAtEpochMillis` | Room only | Drives the reveal animation, not synced |

### Phone transfer (041)

Android 12+ device-to-device transfer copies the local Room DB (`lixo.db`) to the new phone.
Firebase Auth state is device-bound and is not transferred, so the new phone gets a new
anonymous uid. Firestore rules tie every card, reader row and invite to the old uid, so on the
first cloud call `LixoRepository.ensureCloudIdentity()` sees that the persisted `cloud_uid`
differs from the signed-in uid and resets the friend state: each profile's `friendCardId`,
the friends list (including private labels) and removal tombstones are cleared, and a fresh
card is created under the new uid. Friends have to be re-added in person. Progress, avatars
and unlocked items carry over. The old card stays orphaned in Firestore (no auth to delete it).

## App Check (030 §2a)

App Check providers are installed (`installAppCheckProviderFactory()` in `androidApp/src/{debug,release}/kotlin/se/lixo/app/AppCheckInstaller.kt`, called from `MainActivity` before any other
Firebase call) but **enforcement is not turned on yet** — that's a deliberate last step, done only
after the device tests pass, since content-pack reads also go through it.

- Release/real-device builds: `PlayIntegrityAppCheckProviderFactory`
  (`com.google.firebase:firebase-appcheck-playintegrity`).
- Debug builds (`debugImplementation`): `DebugAppCheckProviderFactory`
  (`com.google.firebase:firebase-appcheck-debug`), which logs a debug token to Logcat on first run
  (`D/DebugAppCheckProvider: Enter this debug secret into the allow list...`). That token must be
  registered in the Firebase console (**App Check → Apps → se.lixo.app → Manage debug tokens**)
  for:
  - The physical test device **R5CXC0681EM** (Samsung S25) — install a debug build once, grab the
    token from Logcat, register it.
  - The Android emulator (`emulator-5554`) used as a fallback per the plan's verification section
    — same steps, separate token (emulators get a new random debug token per data wipe, so
    re-register after a factory reset).
- `androidApp`'s `androidTest` source set also pulls in `firebase-appcheck-debug`
  (`androidTestImplementation`), since instrumented tests run against the same Firebase project
  and need a registered token too, independent of the app's own debug token.
- Once enforcement is switched on (Firestore + Auth, per the plan's order of work), an
  unregistered debug device will see every Firestore call fail — if that happens unexpectedly,
  check the console's debug token list first before suspecting the rules.

## Testing

Most friend coverage uses `FakeFriendCloud`, which hand-mirrors the rules' access checks
(commonTest's `FriendFlowTest`/`FriendLabelTest`/`FriendRobustnessTest`, androidTest's
`FriendCodeFlowTest`/`FriendLabelTest`/`FriendLiveGearTest`/`FriendDeepLinkTest`/`FriendRevealTest`).

`androidApp/src/androidTest/kotlin/se/lixo/app/verify/FirebaseFriendCloudEmulatorTest.kt` is the
one test that runs the **real** `FirebaseSyncService` against the **real** `firebase/firestore.rules`,
via local Firebase emulators — it's the only thing that would have caught a client/rules mismatch
like the `FirestoreAppearanceDoc` key-casing fix. It simulates three devices (A, B, C) as three
secondary `FirebaseApp`s sharing the default app's project, each with its own anonymous user and
its own `FirebaseSyncService`, and walks the whole lifecycle: card upsert with real equipped gear,
invite creation/expiry, multi-use redeem (including a third redeemer and `uses` bumping), live
`observeCard` updates (gear change, unfriend → `Gone`, delete → `Gone`), `ALREADY_FRIEND` on a
second redeem, and `NOT_FOUND` after the invite is deleted.

Start the emulators (`firebase.json`'s `emulators` block: Auth on 9099, Firestore on 8080, same
project id as `androidApp/google-services.json`) and run the test against the emulator:

```sh
firebase emulators:exec --project lixo-fd45d --only auth,firestore \
  "ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.FirebaseFriendCloudEmulatorTest"
```

If the emulator isn't running, the test skips itself (`Assume`, via a socket probe to
`10.0.2.2:8080`) instead of failing, so a normal `connectedDebugAndroidTest` run is unaffected.

Two small, deliberately minimal production/build changes support this:
- `FirebaseSyncService`'s `isLocalOnlyMode` constructor now also takes optional
  `explicitAuth`/`explicitFirestore` (`FirebaseAuth`/`FirebaseFirestore`, both `null` by default).
  When null, each resolves **lazily** to the global `Firebase.auth`/`Firebase.firestore` on first
  use — not at construction — so plain `FirebaseSyncService()` (used throughout `commonTest`/
  `androidHostTest`, in JVMs with no native `FirebaseApp`) is unaffected. Only this test passes
  non-null instances, one per simulated device/`FirebaseApp`.
- Debug builds get a `network_security_config.xml` (`androidApp/src/debug/res/xml/`) allowing
  cleartext HTTP to `10.0.2.2`/`localhost` — the emulator talks plain HTTP, and Android blocks
  cleartext by default since API 28. Debug-only; never packaged in release.

Re-run the rules tests after any rules change: `firebase emulators:exec --project demo-lixo
--only firestore "npm --prefix firebase/tests test"`.
