# Plan: Friends that work — add by code/QR, private names, live avatar + gear

## Context

The friends feature ("Kompisbyn", `AvatarMapScreen.kt`) doesn't really work today:
- **Nothing reaches the cloud.** `publishPublicFriendAvatar` is never called (`FirebaseSyncService.kt:414`).
- **Adding friends is fake.** Lookup finds only two hard-coded demo friends (`directoryCache`, `FirebaseSyncService.kt:193-222`), or invents a friend from a fake QR string (`:425-439`).
- **Friends are lost on restart.** They live only in memory (`Repositories.kt:138, 527-563`). The `friends` Room table (`Entities.kt:157`) is unused.
- **Cloud identity needs email.** The only identity comes from email sign-in, and failures return fake `uid_<hash>` values.
- **Too public.** `public_avatars` is readable and searchable by anyone (`firestore.rules:47-56`).

**Goal (user, 2026-11-10):** adding friends works. A child can give each friend a private name on their own device. The child sees each friend's avatar with the gear the friend is wearing right now.
- **Don't show stars.**
- **No email backup now.** It's skipped for GDPR and comes later as a separate feature.

**No accept step (user, 2026-11-10):** scanning the QR code in person is the proof that both kids want to be friends. Redeeming a code makes the friendship **mutual immediately**, with no request or approval screen. Because there is no human gate, the code itself must be hard to obtain remotely (see "Security").

**Out of scope:** email backup/restore, self-chosen public names, iOS QR scanner, any star display, remote sharing of codes (share links/messages).

## Design in one paragraph

Every install signs in silently with **Firebase Anonymous Auth**. Each child profile gets a **friend card** in Firestore with `{ ownerUid, nick, appearance }`:
- `nick` is a generated nickname made of word-list indexes, so no free text is stored.
- There are no stars and no timestamps on the card.

Only the owner and friends can read a card. Friends are added in person: one child shows a **QR code** of `lixo://friend/<code>` (with the 8-char code as text underneath for reading aloud if the camera fails), and the other scans it. Each code opens a **10-minute window** from the moment it is generated. **Any number of friends** (capped at 30 per code) can scan the same QR during that window, and each one becomes a friend. Example: a child shows the code at a party, and five kids scan it. Redeeming makes the friendship mutual immediately, with no accept step. After 10 minutes the code is dead, and the child taps "Visa ny kod" to open a new window. The friend list and the **private names** the child gives friends are stored **only in Room on the device**. While the Avatar tab is open, the app **listens live** to each friend's card, so gear changes show up right away.

## 1. Anonymous identity

- `firebase.json`: add `"anonymous": true` under `auth.providers`.
- `CloudSyncGateway` (`FirebaseSyncService.kt:126`): add `suspend fun ensureSignedIn(): Result<String>`.
  - It returns `Firebase.auth.currentUser?.uid`, otherwise calls `Firebase.auth.signInAnonymously()`.
  - On failure it returns `Result.failure`. It never invents a uid.
- `LixoApp.kt:165-172` `LaunchedEffect(Unit)`: call `repository.ensureCloudIdentity()` next to `content.sync()`.
  - Store the uid in `AppSettingsEntity` (rename `optionalCloudUserId` → `cloudUid`; Room schema v1 with a destructive fallback, so change it in place).
  - Retry lazily on the next friend action if the startup call failed offline.
- **Keep the email sign-in from replacing the anonymous user**, which would break every friendship: hide the email/account block in `ParentGateAndDashboardScreen.kt:666-760` (keep the code, remove it from the UI) until the backup feature comes back. Friends no longer depend on `isLocalOnlyMode`.

## 2. Firestore model + rules

`firebase/firestore.rules`: replace the `public_avatars` block with:

```
friend_cards/{cardId}                      // random 20-char id, one per child profile
  { ownerUid, nick: [adjIdx, animalIdx, num], appearance }
friend_cards/{cardId}/readers/{readerUid}  // presence = readerUid may read the card
  { readerCardId, viaInvite }
friend_invites/{code}                      // 8-char Crockford base32, multi-use, 10-min window
  { cardId, ownerUid, expiresAt, uses }      // uses = number of redeems so far, max 30
```

- `friend_cards/{cardId}`:
  - `get`: the owner, or `exists(.../readers/$(request.auth.uid))`.
  - `list`: denied.
  - `create`/`update`: owner only, `ownerUid` unchanged, `keys().hasOnly(['ownerUid','nick','appearance'])`.
    - `nick` is 3 ints (0..255, 0..255, 0..99).
    - `appearance.keys().hasOnly(['avatarId','tintHex','equipped', …existing AvatarAppearance fields])`, with `avatarId` / `equipped` values matching `^avatar_[a-z0-9_]{1,32}$` / `^item_[a-z0-9_]{1,64}$`. Check the field list of `domain/content/AvatarAppearance.kt` first.
  - `delete`: owner.
- `readers/{readerUid}`:
  - `get, list`: card owner or `readerUid == auth.uid`.
  - `create` either:
    - (a) by the redeemer for themselves: `readerUid == auth.uid`; the invite `get(/friend_invites/$(viaInvite))` has `cardId == cardId` and `expiresAt > request.time`; and `getAfter(invite).uses == get(invite).uses + 1`, so the same batch must bump the counter. Readers have no `update` rule, so an existing reader can't be re-created to burn uses;
    - (b) by the card owner on their own card (reciprocal grant).
  - `delete`: card owner or the reader.
- `friend_invites/{code}`:
  - `get`: any signed-in user. `list`: denied.
  - `create`: by the card owner, with `keys().hasOnly(['cardId','ownerUid','expiresAt','uses'])`, `uses == 0` and `expiresAt <= request.time + duration.value(10, 'm')`.
  - `update` (redeem): `affectedKeys().hasOnly(['uses'])`, `uses == resource.data.uses + 1`, `uses <= 30`, `resource.data.expiresAt > request.time`, and the caller becomes a reader in the same batch: `!exists(card/readers/$(auth.uid)) && existsAfter(card/readers/$(auth.uid))`. Nobody can bump the counter without actually redeeming, or redeem twice.
  - `delete`: owner.
- Card `appearance.equipped`: `size() <= 9` (one per slot). The whole card is small by construction.
- Keep the catch-all deny. Nothing was ever published to `public_avatars`, so no data needs migrating.

## 2a. Security (no accept step, so the code is the only gate)

| Attack | Mitigation |
|---|---|
| **Guessing codes** to befriend random kids | 8 chars of base32 = about 1.1 × 10¹² codes. Each code lives 10 min, so only the few invites open at any moment can be hit. Guessing even one needs on the order of 10⁸ reads. **App Check** (below) blocks scripts outside the real app, and Firestore billing alerts show unusual read volume. |
| **Forwarded QR or code** (screenshot posted in a group chat) | The biggest risk with multi-use codes. Limited by: the 10-min window, a cap of 30 uses per code, no Share button, and the code isn't stored anywhere after the window. On the inviter's sheet, every person who joins appears live in a **"Nya kompisar" list with a remove (✕) button**, so the child (or a parent standing next to them) sees strangers immediately and can remove them, and both sides lose access. The parent friend list shows every friend. "Stoppa koden" ends the window early (deletes the invite). |
| **Modified app writes reader docs directly** | The rules accept a reader doc only with an open invite for *that* card, and only if the same batch bumps `uses` by exactly 1 (`getAfter`). Invites can only be created by the card owner. A grant on someone else's card is impossible without their live code. |
| **Burning uses / double redeem** | `uses` can only be bumped together with creating a *new* reader doc for the caller (`!exists` / `existsAfter`). Readers can't be updated, so one person = one use. Many people redeeming at once are serialized by Firestore; past 30 → `FULL`. |
| **Reading cards by enumerating ids** | `list` is denied everywhere, and card ids are random (20 chars). `get` still needs a reader doc. |
| **Malicious card content** (huge or text-smuggling appearance) | A key whitelist, regex on avatar/item ids and `equipped.size() <= 9` in the rules. The client ignores unknown item ids (`buildAvatarDrawList` `mapNotNull`). |
| **Mass anonymous accounts / scripted abuse** | **App Check is in scope**: Play Integrity in release, the debug provider in debug/androidTest, and enforcement on Firestore + Auth. Add `dev.gitlive:firebase-app-check` (same version ref as the other gitlive libs), install the provider before the first Firebase call, and register debug tokens for R5CXC0681EM and the emulator. Switch on enforcement last, after the device tests pass, because content-pack reads also go through it. |
| **Deep link abuse** (a link that befriends silently) | A deep link only pre-fills the code. Nothing is redeemed until the child taps "Lägg till". |
| **Ex-friend keeps seeing gear** | Unfriending deletes both reader docs. The other side's listener gets permission-denied → `null` → the friend is dropped from Room. |

Residual risk accepted: a code shared remotely (a photo of the screen or read over a call) works for anyone who gets it within the 10 minutes. A child who deliberately does that befriends those people. That counts as consent, and the parent list shows it.

## 3. Generated nickname (fallback name, no free text in cloud)

- Content pack: `nicknameWords { adjectives[], animals[] }` (Swedish, child-safe, at most 256 each).
  - Authored in `tools/content_pipeline/pack/` and validated by the content-pack validator (unique, non-empty, ≤ 256).
  - Append-only: the `upload_pack.py` stability diff fails if an existing index changes word.
  - Document it in `doc/content/content-pack.md`.
- New `domain/social/Nickname.kt`: `Nickname(adj, animal, num)`, `render(words)` → `"ModigRäv42"`, `randomNickname(words, rng)`. An unknown index renders as `"Kompis<num>"`.
- Assigned automatically when a profile's card is first created. `ChildProfile.publicUsername` (`Models.kt:118`, `Entities.kt:11`) becomes `nickname: Nickname?`.
- The podium in `OwnAvatarPodium` (`AvatarMapScreen.kt:614-664`) shows "Ditt kompisnamn: ModigRäv42" with a "Slumpa" (shuffle) button.

## 4. Friend flow in gateway + repository

`CloudSyncGateway` / `FirebaseSyncService`: replace `publishPublicFriendAvatar`, `lookupFriendByUsernameOrQr`, `directoryCache` and `FirestorePublicAvatarDoc` with:
- `upsertMyCard(cardId: String?, nick, appearance): Result<String>` (creates with a random id if `null`).
- `createInvite(cardId): Result<FriendInvite>`: 8 chars from alphabet `0-9A-HJKMNP-TV-Z` using a **cryptographically secure RNG** (`kotlin.random.Random` is not enough; use `SecureRandom` via expect/actual, or `kotlinx-crypto`), `expiresAt = now + 10 min`, `uses = 0`, retry on collision. `deleteInvite(code)` ("Stoppa koden").
- `redeemInvite(code, myCardId): Result<FriendCard>`:
  1. `get` the invite → `NOT_FOUND` / `EXPIRED` / `SELF`;
  2. **one transaction** (re-reads the invite so concurrent scanners serialize): `invite.uses += 1` (`uses == 30` → `FULL`), create `friend_cards/{invite.cardId}/readers/{me}` `{readerCardId = myCardId, viaInvite = code}`, and create `friend_cards/{myCardId}/readers/{invite.ownerUid}` `{readerCardId = invite.cardId}`;
  3. `get` the friend's card.
- `listMyReaders(myCardId): Result<List<ReaderRef>>`: how the inviter discovers new friends.
- `observeCard(cardId): Flow<FriendCard?>`: the gitlive `document(...).snapshots`. It emits `null` when the card is deleted or access is denied.
- `removeFriend(myCardId, friendUid, friendCardId)`: delete both reader docs.
- `deleteMyCard(cardId)`: delete the card, its readers and its invites.
- Never map exceptions to success. Exceptions become `AddFriendError.NETWORK`. Add `EXPIRED`, `SELF`, `FULL` and `INVALID_QR` to `AddFriendError` (`Models.kt:391`).

New `social/FriendCodeParser.kt`:
- `buildFriendPayload(code)` = `lixo://friend/<code>`.
- `parseFriendCode(raw)`: strip the prefix (case-insensitive), remove `-` and spaces, upper-case, map O→0 and I/L→1, require exactly 8 chars from the alphabet; otherwise `null`.
- It is shared by text input, the deep link and the QR scanner. The QR encodes only this payload.

`LixoRepository` (`Repositories.kt:527-563` replaced):
- `ensureMyCard()`: lazy; creates the card plus a nickname for the active profile and stores `friendCardId` on the profile.
- `createFriendCode()` → `(code, payload, expiresAt)`, which opens a 10-min window. If the active profile already has an open, unexpired invite, return it instead of creating a new one (store `code` + `expiresAt` in memory or Room), so closing and reopening the sheet shows the same QR. `stopFriendCode()` deletes it.
- `observeNewFriendsDuring(code)`: a listener on my card's `readers` while the sheet is open. Every new reader is inserted into Room and emitted, so the sheet's "Nya kompisar" list updates live as each friend scans.
- **No accept step:** a successful `redeemInvite` immediately inserts the friend in Room on the redeemer's side. The inviter picks the new friend up with `refreshFriends()` / its readers listener and inserts it too.
- `addFriendByCode(raw, fromQr)` → `parseFriendCode`, then `redeemInvite`, then upsert into Room. `ALREADY_FRIEND` if already in the list.
- `refreshFriends()`: on Avatar tab open and app start. It adds new readers (people who redeemed my code) as friends and drops friends whose card is `null`.
- `removeFriend(friendUid)`: Room first, then the gateway (best effort).
- `setFriendLabel(friendUid, label?)`: Room only.
- **Own gear goes live:** after `equipItemOnActiveProfile` (`:385`), `selectAvatarOnActiveProfile` (`:407`) and `customizeBaseAppearanceOnActiveProfile` (`:421`), debounce 2 s and call `upsertMyCard` if the profile has a card.

## 5. Local storage (Room) — friends and private names

- `FriendEntity` (`Entities.kt:157-164`): primary key `(profileId, friendUid)`, plus `friendCardId`, `nickJson`, `appearanceJson`, `label: String?` (1–20 chars, **device only**), `addedAtEpochMillis`. Drop `totalStars`.
- `ProfileEntity`: add `friendCardId: String?`.
- `Daos.kt:120-128`:
  - `observeFriends(profileId)`, `insertFriend`, `deleteFriend(profileId, friendUid)`;
  - `updateFriendCard(profileId, friendUid, nickJson, appearanceJson)`, which never touches `label`;
  - `setLabel(profileId, friendUid, label)`.
- `ProfileStore.kt` / `ProfileMappers.kt`: add friend load/save plus `FriendEntity` ↔ `FriendProfile` mappers. `_friendsByProfile` (`Repositories.kt:138`) is loaded in `loadPersistedProfiles` (`:192-254`) and written through on every change.
- `FriendProfile` (`Models.kt:380`): `{ userId, cardId, nick, appearance, label, addedAt }`. **Remove `totalStars`.** `shownName(words) = label ?: nick.render(words)`.
- The label never leaves the device:
  - no gateway method takes it;
  - it is never logged;
  - `allowBackup="false"` is already set (`androidApp/src/main/AndroidManifest.xml:24`), so Google's device backup doesn't copy it.

## 6. Live avatar + current gear

- `AvatarMapScreen`: while visible, one `LaunchedEffect(friends.map { it.cardId })` collects `observeCard` for each friend. It goes through `LixoRepository.observeFriendCards(profileId): Flow<…>`, which merges the per-card flows and writes changes to Room with `updateFriendCard`.
  - The UI reads only from Room, so it works offline with the last known gear.
  - Listeners stop when the screen leaves composition.
- Rendering already reuses `AvatarViewport` (`ui/components/avatar/AvatarViewport.kt`), the same composable as the own podium. It is used in `FriendPlot` (`AvatarMapScreen.kt:696-701`) and in the detail sheet (`:277-282`).
- An unknown item id (the friend has a newer content pack) is already skipped (`LayerGeometry.kt` `buildAvatarDrawList` `mapNotNull`). In that case also trigger `content.sync()` once per session, so new items appear after the pack download.
- The detail-sheet gear list (`:303-345`) keeps using `content.itemsById`. Unknown ids are hidden.

## 7. UI

`AvatarMapScreen.kt`:
- **Remove stars:** the `StatPill` at `:293` and `:721`.
- **FriendPlot:** the label large, with `@ModigRäv42` small underneath (just the nickname if there's no label). The rarity dots (`:723-740`) stay.
- **Add-friend sheet** (`:377-480`), two tabs:
  - **"Min kompiskod":** a QR (~220 dp, white quiet zone, `testTag("friend_qr")`), the code as `XXXX-XXXX` underneath (for reading aloud), and a countdown "Koden gäller i 9:42".
    - **One code per 10-min window.** The QR stays the same for the whole window, so several friends can scan it one after another. Closing and reopening the sheet within the window shows the same code. When it expires, the QR is greyed out with a "Visa ny kod" button.
    - **"Nya kompisar" list** under the QR: each friend who scans appears live (avatar + nickname + ✕ to remove right away), with a short celebration "Du och <nick> är nu kompisar!".
    - "Stoppa koden" ends the window early. There is **no Share button**: friends are added in person only.
  - **"Lägg till kompis":** a primary "Skanna QR-kod" button, a code field (auto-uppercase), then "Lägg till".
  - After success, a **"Vad heter din kompis?"** step: the friend's avatar (`AvatarViewport`), a text field (placeholder = nickname), the hint "Bara du ser det här namnet.", and "Spara" / "Hoppa över".
  - Offline / no uid: "Kompisar kräver internet".
- **Detail sheet:** the header shows the label and nickname; a "Byt namn" button opens the same field (empty field = remove the label); "Ta bort kompis" stays.
- **Parent friend list** (`ParentGateAndDashboardScreen.kt:593-630`): "Kalle (@ModigRäv42)", and remove calls the new `removeFriend`.
- **Strings** in `LixoStrings.kt` + `strings.xml`: errors (`NOT_FOUND`, `EXPIRED` "Koden har gått ut – be om en ny", `SELF`, `FULL`, `ALREADY_FRIEND`, `INVALID_QR`, `NETWORK`) plus all labels above. Remove the username/search copy.

**New-friend reveal ("kompis-kistan")**

There is no "pending friends" section: with no accept step, a friendship is never waiting for anyone. What *can* wait is the **reveal**. The inviter may learn about a new friend while the app is closed or the sheet is shut. Those friends are queued as **"unseen"** and revealed on the next visit.

- **Room:** `FriendEntity.revealedAtEpochMillis: Long?` (`null` = not yet celebrated on this device) + DAO `markRevealed(profileId, friendUid)`. `LixoRepository.unrevealedFriends(profileId): Flow<List<FriendProfile>>`.
- **When it plays:**
  - **Scanner side:** right after a successful redeem, before the "Vad heter din kompis?" step.
  - **Inviter side, sheet open:** each new reader from `observeNewFriendsDuring` plays it. If several scan at once, they are queued one after another, with "+2 till" shown while waiting.
  - **Inviter side, later:** on Avatar tab open, any `unrevealedFriends` play in order. The village then shows a badge "3 nya kompisar!" on the "+" plot until all are seen.
- **New composable `ui/components/celebration/FriendRevealStage.kt`**, modelled on `ChestRevealStage` (`RewardChest.kt:438`) and reusing its parts:
  1. **Envelope/portal (0–0.8 s):** a glowing "kompis-portal" ring (or a gift box with the `RewardChest` wiggle) wobbles in the centre on `CelebrationScrim`, with sound `SfxCue.CHEST_WIGGLE` and `LixoHaptics.tick`. It auto-opens; there's no tap needed, unlike the chest.
  2. **Pop (0.8 s):** `LightBurst(rarity = Rarity.EPIC)` (`LightBurst.kt:41`) behind it. The friend's **`AvatarViewport`** (with their current gear, `interactiveRotation = false`) springs from scale 0 → 1.15 → 1.0 (`spring(dampingRatio = 0.45f)`) with a small jump.
  3. **Confetti:** `rememberConfettiState()` + `ConfettiOverlay` (`ConfettiOverlay.kt:139-167`), `burst` from the avatar centre using `confettiPaletteFor(Rarity.EPIC)` and `confettiCountFor(Rarity.EPIC)`. Sound `SfxCue.CONFETTI`, `LixoHaptics.heavy`.
  4. **Title:** "Ny kompis!" bounces in, with the nickname below (`@ModigRäv42`). The avatar does a short wave (rotate the arm bone, or rock the whole avatar ±8° twice).
  5. **Then:** on the scanner side, go to the label step. On the inviter side, a "Ge ett namn" button (label step) or a tap to continue. It auto-finishes after 2.5 s, like the chest's 2.2 s, and calls `markRevealed`.
  - Add `SfxCue.FRIEND_ADDED` (`feedback/LixoHaptics.kt:16`). It can fall back to `ITEM_REVEAL` until a dedicated sound exists.
- **Landing animation in the village:** after the reveal, the new friend's `FriendPlot` drops into its spot (scale/translate from the top + a small dust puff) and glows for 3 s. A `newlyAddedFriendUid` state in `AvatarMapScreen` drives it.
- **Reduced motion:** if the system "remove animations" setting is on (`animator duration scale == 0`), skip the confetti and light burst and just fade in.

**Tests**
- commonTest: `unrevealedFriends` emits new readers found by `refreshFriends`; `markRevealed` removes them; a scanner-side add is marked revealed after the stage finishes.
- Instrumented `verify/FriendRevealTest` (real repo + Room + `FakeFriendCloud`, `mainClock.autoAdvance = false` to step frames):
  - B redeems A's code → the stage shows "Ny kompis!" and A's nickname; advance 2.5 s → the label step is shown;
  - A has 2 friends added while away → open the village → the reveal plays twice (assert the text for each nickname in order), then the badge is gone;
  - capture frames at 0.3 s / 1.0 s / 1.8 s to `temp/verify/lixo_screenshots/friends/reveal_*.png` as visual evidence;
  - reduced-motion flag → no `ConfettiOverlay` node (add `testTag("confetti")`).

**QR + deep link:**
- **QR display:** add `io.github.alexzhirkevich:qrose` (commonMain) and a new `FriendQrCode(payload)` composable.
- **QR scanning:**
  - `commonMain` `interface QrScanner { suspend fun scan(): QrScanResult }` (`Success` / `Cancelled` / `Unavailable`), provided through the platform context so tests can inject a fake.
  - `androidMain` uses the Google code scanner (`play-services-code-scanner`, QR only), which needs **no CAMERA permission**.
  - `Unavailable` → focus the code field and show "skriv koden i stället".
- **Deep link:**
  - `AndroidManifest.xml`: a new intent filter `VIEW` / `DEFAULT` / `BROWSABLE`, `scheme="lixo" host="friend"`. There's none today; only https email links at `:42-71`.
  - Route it to the Avatar tab with the code prefilled. Never redeem automatically. It exists only so that scanning with the **system camera app** opens Lixo; the app never generates shareable links.

## 8. Cleanup

- Remove `directoryCache`, `FirestorePublicAvatarDoc`, the old QR parsing and `publishPublicFriendAvatar`/`lookupFriendByUsernameOrQr` from `FirebaseSyncService.kt`.
- Update `FakeCloudGateway` (`androidApp/src/androidTest/.../screenshots/DummyData.kt:21-47`) to the new interface.
- Add a shared in-memory **`FakeFriendCloud`**, used by both commonTest and androidTest (or one copy each). It implements cards, readers and invites with the **same access checks as the rules**, and lets several fake "devices" (uids) share one store.
- `doc/friends.md`: data model, rules, invite lifecycle (10-min multi-use window, max 30, stop early), QR payload, what is local vs. cloud (the label is local only).
- `doc/project.md` §2 "Kompisgalleri" (line 110) and §3 (lines 136-141):
  - friends need no account (anonymous identity);
  - friends-only visibility;
  - added with a code or QR;
  - private names stay on the device;
  - no stars are shown.

## Verification

**Rules (emulator):** `firebase/tests/rules.test.mjs` with `@firebase/rules-unit-testing`, run by `firebase emulators:exec --only firestore "npm --prefix firebase/tests test"`. It checks that:
- a stranger can't `get` a card, and `list` is denied;
- the owner can read and write their card, and a foreign `ownerUid` is denied;
- `totalStars`/`updatedAt`/string-nickname/free-text appearance fields are denied;
- redeeming a valid invite (reader create + `uses + 1` in one batch) works; an expired invite is denied;
- **three different users redeem the same code** → all three succeed, `uses == 3`;
- the same user redeems twice → the second is denied (reader already exists); `uses` stays the same;
- bumping `uses` without creating a reader, by +2, or past 30 → denied; a reader create without bumping `uses` → denied;
- an invite with `expiresAt` more than 10 min ahead, or `uses != 0` at create → denied; creating an invite for someone else's card → denied;
- a reader doc without an invite, or with another card's invite → denied;
- a card with `equipped` of 10+ entries is denied;
- after the reader doc is deleted, `get` is denied.

**Unit (commonTest / androidHostTest):**
- `FriendCodeParserTest`: round-trip; formatting variants; junk, `https://` and the old `user:avatar:stars` payload return `null`.
- `NicknameTest`: render, range, unknown index → `Kompis<num>`.
- `FriendFlowTest` (two repositories on one `FakeFriendCloud`):
  - A's code redeemed by B (as a bare code and as a QR payload) → both see each other after `refreshFriends`;
  - no accept step: right after B's redeem, both lists contain each other, with no approval call in between;
  - **one code, many friends:** repositories B, C and D redeem A's single code → A has 3 friends, and B, C and D each have A;
  - the window: at +9 min, E's redeem works; at +11 min (fake clock) → `EXPIRED`; after `stopFriendCode` → `NOT_FOUND`; 31st redeem → `FULL`;
  - `SELF` (A scans own code); B scans again → `ALREADY_FRIEND`; non-Lixo payload → `INVALID_QR`;
  - `createFriendCode()` twice within the window returns the same code;
  - A equips a hat → after the 2 s debounce, B's `observeFriendCards` emits the hat;
  - A removes B → B's card flow emits `null` and B drops A.
- `FriendLabelTest`:
  - the label is shown; `refreshFriends` / card updates keep the label;
  - **privacy:** `FakeFriendCloud` records all writes, and the label string appears in none of them.
- `ProfileStoreTest`: friends and labels round-trip per profile, and two profiles stay separate.
- `ContentPackValidatorTest` + pytest: word-list validation and the append-only stability diff.

**Instrumented** (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/`, `createComposeRule`, `AndroidPlatformContext.init`, `LixoTheme`, real `LixoRepository` + Room, `FakeFriendCloud`):
- `FriendCodeFlowTest`:
  - A shows a code + `friend_qr`. The QR bitmap is decoded with ML Kit (`androidTestImplementation com.google.mlkit:barcode-scanning`) and equals the payload;
  - B scans (with a `FakeQrScanner`) → the label step → "Kalle" → the plot shows `Kalle` + `@<nick>`;
  - typing the code works; expired → error text; `Unavailable` scanner → fallback text;
  - **no star text/icon exists** in the village or the detail sheet;
  - no Share button exists on "Min kompiskod";
  - **multi-scan:** while A's sheet is open, B and C both redeem the shown code → A's "Nya kompisar" list shows both nicknames, and the QR code text does not change between scans; ✕ on C removes C from A's village;
  - close and reopen the sheet within the window → same code text; fake clock +10 min → "Visa ny kod" is shown, and tapping it shows a different code.
- `FriendLabelTest`: "Hoppa över" → only the nickname; "Byt namn" → "Olle"; cold start (new repo, same DB) → "Olle" still shown; the parent list shows `Olle (@nick)`.
- `FriendLiveGearTest`: B's village is open; A's repository equips a hat → B's friend plot re-renders with it. Compare `captureToImage()` before and after; at least the detail-sheet gear list shows the hat name with `onNodeWithText`.
- `FriendDeepLinkTest`: `ACTION_VIEW lixo://friend/<code>` → the Avatar tab with the code prefilled, and nothing is redeemed until tapped.
- Screenshots to `temp/verify/lixo_screenshots/friends/`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.FriendRevealTest,se.lixo.app.verify.FriendCodeFlowTest,se.lixo.app.verify.FriendLabelTest,se.lixo.app.verify.FriendLiveGearTest,se.lixo.app.verify.FriendDeepLinkTest`. Use emulator-5554 if the S25 isn't attached.

**Build + manual:**
- `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest :composeApp:testDebugUnitTest` is green.
- `grep -rn "public_avatars\|directoryCache\|lookupFriendByUsernameOrQr\|totalStars" composeApp/src/commonMain/kotlin/se/lixo/app/{data/remote,ui/screens/social}` returns nothing friend-related.
- `aapt dump permissions` on the debug APK shows no `CAMERA`.
- After `firebase deploy --only firestore:rules,auth` and a pack upload with the word list, on two real phones (S25 + Pixel 7):
  - A shows the QR, B scans, then a third device scans the same QR → all villages show each other;
  - A changes gear → B sees it while B's village is open;
  - scanning with B's system camera opens Lixo with the code.

## Order of work

1. Rules + emulator rules tests (section 2).
2. Nickname word list in the content pack + `Nickname` (section 3).
3. Anonymous identity + App Check providers (no enforcement yet) + hide the email block (sections 1 + 2a).
4. Room friends + labels + mappers (section 5).
5. Gateway, `FriendCodeParser`, repository flow, own-card upsert, `FakeFriendCloud`, unit tests (sections 4 + 8).
6. Live card listeners (section 6).
7. UI, QR, scanner, deep link, star removal, then the instrumented tests (section 7).
8. Cleanup + docs, full build + device run, deploy rules/auth, upload the content pack, set Firestore billing alerts, then turn on App Check enforcement (section 2a).
9. Register as task `030` in `FEATURES.md` with this plan copied to `doc/plans/030-friends-that-work.md` (project rule).

## Open questions

- Should friends need a **parent opt-in** ("Tillåt kompisar" in the parent area, default on or off) before the first card is created? The plan currently enables friends without one, because the card holds no personal data.
- Do friendships belong to the **install uid**? A sibling profile on the same phone could technically read a friend's card via the rules (the UI only shows it in the profile that added it). The plan accepts this.
