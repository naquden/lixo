# Content pack reference

> To publish new content to installed apps, see [publishing.md](publishing.md).

A **content pack** is one versioned JSON document that describes every avatar, wardrobe item,
event and drop rule in the app. A holiday special is new content in Firestore and needs no app
release. This document is the schema reference for `se.lixo.app.domain.content` (`doc/plans/019`),
which is a pure model layer: nothing in the app reads a content pack yet (that starts in 020/022).

## Package layout

- `Skeleton.kt`: `Bone`, `ZBucket`, the bone-box table, parallax defaults, and a few shared
  constants (`SUPPORTED_SCHEMA_VERSION`, colour constants, `expandedBoneBox`, `isInside`).
- `ContentPack.kt`: the `@Serializable` DTOs for the schema below, `ContentJson`, and
  `parseContentHex`.
- `ContentPaths.kt`: strict SVG path validation and conservative bounding-box math, used by both
  the validator and (020) the renderer.
- `ContentPackValidator.kt`: turns an untrusted JSON string into an `Accepted`/`Rejected` pack.
- `ActiveContent.kt`: the runtime index over an already-validated pack.

## Coordinate system

Every path (`ContentLayer.d`) is written in bone-local **avatar units (au)**. The origin is the
top-left of the bone's box and `y` points down — the same convention `Avatar3DController` already
uses for its hand-drawn shapes. An avatar can override a bone's box with `ContentAvatar.bones[bone]`: when it does, the renderer
scales **item** layers (never avatar part layers, which are already authored in the effective
box) on that bone by `BoneTransform.itemScale = avatarBoneSize / STANDARD_BONE_BOX[bone]`, so one
item fits every avatar without per-avatar art — sleeves and gloves on an oversized arm/hand stretch
together because `HAND` is derived from `ARM`. Held items (HAND_LEFT/HAND_RIGHT slot layers on
HAND_L/HAND_R/ARM_L/ARM_R) are not stretched per axis. They are scaled uniformly by the geometric
mean `sqrt(itemScale.x * itemScale.y)` around the hand centre (the hand-box centre, or for arm
bones the centre of the hand inside the arm box), so a round shield stays round on a slim avatar.
The face (eyes/smile, designed once in a 56×56 au box) is stretched the same way onto the avatar's
effective `HEAD` box by `faceMatrix`.

A layer's path must stay inside its bone box **expanded by `BONE_BOX_OVERHANG` (1.5×) on every
side** (`Skeleton.expandedBoneBox`), so hats, capes and weapons can stick out past their anchor
bone. This is checked on the **unscaled, standard** box for item layers, and on the avatar's
**effective** box (override if present, else standard) for avatar-part layers — an oversized
override bone therefore gives its parts more room, never less.

### Bone box table (au)

| Bone | Width | Height | Mirror | Default parallax |
|------|------:|-------:|--------|------------------:|
| `ROOT` | 160 | 240 | — | 0.0 |
| `TORSO` | 80 | 80 | — | 0.5 |
| `HEAD` | 56 | 56 | — | 1.0 |
| `ARM_L` / `ARM_R` | 38 | 80 | each other | 0.5 |
| `HAND_L` / `HAND_R` | 38 | 38 | each other | 0.5 |
| `LEG_L` / `LEG_R` | 40 | 80 | each other | 0.3 |
| `TAIL` | 40 | 60 | — | −0.4 |

A layer in the `BACK` z-bucket always uses `BACK_BUCKET_PARALLAX` (−0.6) instead of its bone's
default, unless the layer sets its own `parallax`.

## Z-bucket draw order

`ZBucket`'s enum **ordinal is the draw order** (lowest drawn first, i.e. furthest back):

```
BACK < TAIL < LEGS < LEGWEAR < FOOTWEAR < TORSO < TORSO_OVER < ARM_BACK < ARM < HAND < HEAD < FACE < HEAD_OVER < HAT < FRONT
```

Within one bucket, layers are ordered by their own `order` field (ascending), then declaration
order.

## Pack schema (`schemaVersion = 1`)

### `ContentPack`

| Field | Type | Default |
|---|---|---|
| `schemaVersion` | Int | — |
| `version` | Int | — |
| `publishedAt` | String | `""` |
| `strings` | Map\<lang, Map\<key, text>>> | `{}` |
| `avatars` | List\<ContentAvatar> | `[]` |
| `items` | List\<ContentItem> | `[]` |
| `events` | List\<ContentEvent> | `[]` |
| `dropRules` | DropRules | — |

### `ContentAvatar`

| Field | Type | Default |
|---|---|---|
| `id` | String | — |
| `nameKey` | String | — |
| `order` | Int | `0` |
| `humanoid` | Boolean | `false` |
| `skinHex` | String (`#RRGGBB`/`#AARRGGBB`) | — |
| `defaultTintHex` | String | — |
| `unlock` | `AvatarUnlock` | — |
| `bones` | Map\<Bone, BoneOverride> | `{}` |
| `pose` | `AvatarPose` | all `1.0` |
| `parts` | List\<AvatarPart> | `[]` |
| `face` | `AvatarFace` | `{eyeStyles=4, smileStyles=4}` |
| `palette` | `ContentPalette?` (`{primary, secondary, accent}` hex) | `null` |

`AvatarUnlock` is `{type: "default" | "stars", stars: Int = 0}` (a plain class, not a polymorphic
discriminator — `"type"` would otherwise clash with kotlinx's own `classDiscriminator`).
`BoneOverride` is `{size: [w,h]?, offset: [x,y]?}`. `AvatarPart` is `{part: String, layers: [Layer]}`.

### `ContentItem`

| Field | Type | Default |
|---|---|---|
| `id` | String | — |
| `slot` | `EquipmentSlot` | — |
| `rarity` | `Rarity` | — |
| `subjectTheme` | `Subject` | — |
| `nameKey` / `descKey` | String | — / `""` |
| `palette` | `{primary, secondary, accent}` hex | — |
| `pattern` | `ItemPattern` | `NONE` |
| `ornament` | `ItemOrnament` | `NONE` |
| `scale` | Float | `1.0` |
| `hidesParts` | List\<String> | `[]` |
| `layers` | List\<ContentLayer> | — |
| `pool` | `ItemPool` | `standard` |
| `eventId` | String? | `null` |
| `retired` | Boolean | `false` |
| `preview` | `{ghostBody, yaw}`? | `null` |

`ItemPool` is one of `standard | event | achievement | level | setpiece | none`, serialized
lower-case.

### `ContentLayer`

| Field | Type | Default |
|---|---|---|
| `bone` | `Bone` | — |
| `z` | `ZBucket` | — |
| `order` | Int | `0` |
| `d` | String (SVG path, au) | — |
| `fill` | `{role, shade}` | — |
| `stroke` | `{role, width}`? | `null` |
| `shading` | `flat \| gradient \| block` | `gradient` |
| `depth` | Float? | `null` (required for `block`) |
| `parallax` | Float? | `null` (falls back to the bone/bucket default) |
| `mirror` | `none \| x` | `none` |
| `patternClip` | Boolean | `false` |
| `breath` | Boolean | `false` |

### `ContentEvent` / `DropRules`

See the DTOs in `ContentPack.kt` for the exhaustive field list (`ChestTheme`, `EventBanner`,
`DropBoost`, `AfterEnd`, `OnboardingRules`, `StarUnlock`, `LevelReward`). Noteworthy defaults:
`ContentEvent.tz = "local"`, `afterEnd = retire`; `DropRules.onboarding/starUnlocks/levelRewards`
all default to empty — a pack only needs to list the ones it wants to curate.

### Drop rules (023)

Every loot tuning value lives in `dropRules` now, so it can be changed from the cloud without an
app release. `ProgressionAndGiftEngine` (see its class KDoc) never falls back to a constant: each
field is a required parameter on the function that uses it.

| Field | Engine behaviour |
|---|---|
| `rarityWeights` | Rarity-first roll weights (`pickRarity`), summed only over the rarities actually present in the candidate pool — a rarity missing from the pool implicitly spreads its share over the others. |
| `chestCostHalfPoints` / `progressRunHalfPoints` / `replayRunHalfPoints` | The Flitkista "effort chest" meter: half-points earned per run and the threshold that awards a chest. |
| `pity` | Pity floors: the Nth Flitkista chest since the last chest at or above a tier (keyed by `Rarity`) is guaranteed to be at least that tier. A tier missing from the map has no floor. |
| `onboarding.levels` | Cleared-level counts (across all subjects) that award an onboarding item on the *next* level cleared, i.e. `clearedLevelsCountBeforeRun + 1 in onboarding.levels`. |
| `starUnlocks` | Star-threshold item unlocks, lowest eligible first. |
| `levelRewards` | The curated item for a scheduled onboarding first clear (`ActiveContent.levelRewardItemId`). A missing id, or one already owned/not chest-eligible, falls back to the onboarding roll instead of crashing. |
| `achievementRewards` | Resolved at award time: `achievementRewards[achievementId] ?: achievement.rewardItemId`. The run's frozen pack decides the item, not the `Achievement.rewardItemId` baked in at repository init. |
| `tutorialRewardItemId` | The hat unlocked and equipped when the tutorial completes (and the debug Saga profile's starting hat). |
| `weeklyStreakSetPieceItemIds` | The item pool for the 7-day weekly-streak reward. The "every 7th streak day" rule itself stays app code (`BalanceConfig.WEEKLY_STREAK_DAYS_FOR_SET_REWARD`), as does the max-1-item-per-`RewardSource`-per-run cap (`BalanceConfig.MAX_ITEMS_PER_SOURCE_PER_RUN`). |

Each run uses one frozen snapshot of the pack, taken when the run starts (`repository.content.active.value` at the moment play begins): a content hot swap mid-run never changes that run's outcome or celebration.

## Palette roles

| Role | Source |
|---|---|
| `primary` / `secondary` / `accent` | the item's own `palette` |
| `skin` | the avatar's `skinHex` |
| `tint` | the profile's `primaryTintHex`, falling back to the avatar's `defaultTintHex` |
| `eye` | app constant (`CONTENT_EYE_ARGB`) |
| `outline` | app constant (`CONTENT_OUTLINE_ARGB`) |

**Avatar part layers may only use `skin`, `tint`, `eye` or `outline`**, plus `primary`/`secondary`/
`accent` when the avatar has its own `palette` — a part without an avatar `palette` has no source
for those roles. Item layers may use any role; their `primary`/`secondary`/`accent` always come
from the item's own `palette` (`resolveRoleColor` only falls back to the avatar's `palette` when
there is no item, i.e. for avatar part layers).

Item layers are checked against `STANDARD_BONE_BOX` (an item must fit the unscaled skeleton; the
renderer, not the pack, is responsible for `scale`-ing it per avatar). Avatar part layers are
checked against the avatar's **effective** bone box (its own `bones[bone].size` override if
present, else the standard box).

## Strict path rules

Compose's own `PathParser` is lenient: `"M 0 0 L"` (a command with too few arguments) silently
parses to whatever came before it instead of failing. `ContentPaths.strictError` runs first and
is much stricter:

1. `d` must be non-blank and at most `ContentPaths.MAX_PATH_CHARS` (4096) characters.
2. Only `MmLlHhVvCcSsQqTtAaZz`, digits, `. + - e E`, whitespace and `,` are allowed at all.
3. Tokenizing must leave nothing behind except whitespace/commas between tokens — a command
   letter immediately followed by another command letter with no numbers in between is legal
   (e.g. `Z` then `L`), but a stray character that matches neither a command letter nor a number
   is an error.
4. The **first token must be `M` or `m`**.
5. Each command's argument count must be **positive and an exact multiple** of its arity:
   `M/L/T` → 2, `H/V` → 1, `C` → 6, `S/Q` → 4, `A` → 7, `Z` → 0 (repeated groups, e.g. `L` with 8
   numbers = 4 implicit line-tos, are fine as long as the count is a multiple of 2).
6. An arc's large-arc and sweep flags (its 4th and 5th numbers) must be **exactly `0` or `1`**,
   and must be their own token — `A 1 1 0 01 2 2` (flags clubbed into one `01` token) is invalid,
   even though it "looks" like two flags.
7. Every number must parse as a finite `Float`.

Only after `strictError` returns `null` does anything call `ContentPaths.parseNodes` /
`PathParser`.

**Bounding-box rule**: `ContentPaths.bounds` is deliberately conservative. It includes every
segment's start and end point, every cubic/quadratic control point (including the reflected
control point for `S`/`T`), and — for an elliptical arc — the **full rotated ellipse's extent**,
not just the drawn arc. Control points and arc ellipses therefore also need to stay inside the
expanded bone box, even if the visible stroke/fill never reaches that far. For example, the
`item_hand_left_test_shield` fixture item draws a 15×15 arc on a 38×38 `HAND_L` box: its ellipse
extends 15 au past the arc's chord, which is within the box's 1.5×-expanded 95×95 bound, so it
passes.

## Validation table

`ContentPackValidator.validate(json, appAchievementIds, previous, requireNewerVersion = true)`
returns `Rejected(reason)` (the caller keeps whatever pack it already has) or
`Accepted(pack, skipped)`.

| Check | Outcome |
|---|---|
| Raw JSON > 6 MB, or not valid JSON, or root isn't an object | **Reject** |
| `schemaVersion != SUPPORTED_SCHEMA_VERSION` (1), or `version` not an Int | **Reject** |
| `items`/`avatars`/`events` over their cap (3000 / 64 / 64) | **Reject** |
| `previous != null && requireNewerVersion && version <= previous.version` | **Reject** |
| `strings` doesn't decode as `Map<String, Map<String,String>>` | **Reject** |
| `dropRules` undecodable, or any rarity weight missing/≤0, or pity not present/strictly
  increasing `RARE < EPIC < LEGENDARY`, or a chest/run cost ≤ 0, or `onboarding.levels` not
  strictly increasing / < 1, or a `starUnlocks.stars` < 1, or duplicate `levelRewards.level` | **Reject** |
| One item: undecodable (bad enum, missing required field), a bad layer (strict path error, empty
  path, out-of-box bounds, bad shade/stroke/parallax/depth, disallowed role), bad `eventId`, `id`
  format, blank `nameKey`, bad hex, `scale` out of `0.5..2`, too many `hidesParts`/layers | **Skip** `ITEM`, item dropped |
| Duplicate item id | **Skip** `ITEM` (first one wins) |
| One avatar: same shape of errors as an item, plus bone-override range checks, pose range, face
  style counts, part name/count/uniqueness | **Skip** `AVATAR`, unless it's the avatar `avatarError`
  classified as `unlock.type == "default"` from the raw JSON **before** decoding, in which case: **Reject** |
| One event: bad id/tz, `start`/`endExclusive` not parseable or `start >= endExclusive`, bad
  chest/banner hex, `dropBoost` out of `0..1` | **Skip** `EVENT`, event dropped |
| An event whose `itemIds` are all skipped items | **Skip** `EVENT` ("no valid items") |
| `appAchievementIds` entry without an `achievementRewards` mapping to an **accepted** item | **Reject** |
| `levelRewards`/`starUnlocks`/`tutorialRewardItemId`/`weeklyStreakSetPieceItemIds` referencing a
  non-accepted item (including one that was just skipped) | **Reject** |
| No accepted avatar has `unlock.type == DEFAULT` | **Reject** |
| An item id present in `previous` (owned items) is missing from the newly-accepted set | **Reject** |

Every skip is logged: `println("LixoContent: skip <kind> <id>: <reason>")`.

## Stability rules

- **Item ids are never removed.** Once a child can own an item, that id must keep decoding and
  rendering forever — see the "item from previous missing" reject rule above.
- **`version` is strictly increasing** between any two packs the app accepts in sequence (the
  bundled/seed pack may opt out via `requireNewerVersion = false`).
- **`levelRewards`, `achievementRewards` and `starUnlocks` only ever get additions.** Removing or
  renumbering an entry would retroactively change what a child already earned; add new entries
  instead (e.g. a new `level`, not a changed `itemId` for an existing `level`).

## Cloud delivery (024)

A published pack lives in Firestore, keyed by `schemaVersion` so a future schema-2 app and
today's schema-1 app can coexist:

- `content/meta` = `{ packs: { "<schemaVersion>": { version, chunkCount, sha256, bytes, publishedAt } } }`.
- `content_chunks/{schemaVersion}_{version}_{i}` = `{ data: "<chunk text>" }` -- the canonical pack
  JSON split into ≤900 KB slices (cut only between characters, never inside a surrogate pair); the
  concatenation of all `chunkCount` chunks is byte-identical to the uploaded pack.

On startup and on resume (throttled to once per 15 minutes), the app fetches `content/meta`,
downloads `chunkCount` chunks for its `SUPPORTED_SCHEMA_VERSION`, and only swaps them in if ALL
of the following hold: the joined chunks' SHA-256 (over their UTF-8 bytes) matches `sha256`; the
meta's `version` is strictly greater than the currently-active pack's; the decoded pack's own
`version` field matches the meta's; and `ContentPackValidator.validate(..., previous = active)`
accepts it (so, per the stability rules above, no owned item id can disappear). A verified pack
is cached to Room (`content_pack_meta`/`content_pack_chunks`) before it is swapped in, so a later
offline restart still serves it; a cache write failure never blocks the swap itself. At startup,
before any network call, a cached pack only wins over the bundled one if it is newer AND still
validates against the bundled pack (`previous = bundled`) -- an app update whose bundled pack is
newer than the cache, or that ships an item the cache doesn't have, always wins over a stale
cache.

`tools/content_pipeline/pack/upload_pack.py` is the only writer: it re-validates the pack and
runs the stability diff against the last `published/pack_v<N>.json` before touching the network,
uploads every chunk doc first and the `content/meta` doc last (so a client never observes a meta
bump without its chunks), then copies the pack to `published/pack_v<N>.json` and prunes chunk
docs for versions older than the last 3 published.

## Worked example: a HAT item

```json
{
  "id": "item_hat_jul_tomte",
  "slot": "HAT",
  "rarity": "EPIC",
  "subjectTheme": "ENGLISH",
  "nameKey": "item_hat_jul_tomte.name",
  "descKey": "item_hat_jul_tomte.desc",
  "palette": { "primary": "#C0392B", "secondary": "#FFFFFF", "accent": "#F1C40F" },
  "ornament": "SPARKLE",
  "hidesParts": ["ears"],
  "layers": [
    {
      "bone": "HEAD",
      "z": "HAT",
      "order": 0,
      "d": "M 8 0 L 28 -40 L 48 0 Z",
      "fill": { "role": "primary" }
    },
    {
      "bone": "HEAD",
      "z": "HAT",
      "order": 1,
      "d": "M 4 -4 L 52 -4 L 52 4 L 4 4 Z",
      "fill": { "role": "secondary" }
    }
  ],
  "pool": "standard"
}
```

The first layer is the cone body: a triangle from `(8,0)` to the tip at `(28,-40)` to `(48,0)`,
whose bounds `Rect(8,-40,48,0)` lie comfortably inside `HEAD`'s expanded box
(`-84,-84` to `140,140`). The second layer is the white trim band drawn just below it (`order: 1`
> `order: 0`, so it draws after/above the cone within the same `HAT` bucket). `hidesParts: ["ears"]`
hides the avatar's `ears` part while this hat is equipped, the same way a helmet would.

## Renderer placement (020)

020's `se.lixo.app.ui.components.avatar` renderer places every bone before drawing any layer.
This section documents the rules 021's authoring pipeline must reproduce.

### Standard bone origins

`STANDARD_BONE_ORIGIN` (au, relative to the feet, y down), with no avatar overrides:

| Bone | Origin |
|---|---|
| LEG_R | (-40, -80) |
| LEG_L | (0, -80) |
| TORSO | (-40, -160) |
| HEAD | (-28, -217.5) |
| ARM_R | (-78, -160) |
| ARM_L | (40, -160) |
| HAND_R | (-78, -118) |
| HAND_L | (40, -118) |
| TAIL | (34, -94) |
| ROOT | (-80, -240) |

Each bone's origin/pivot is derived from its own effective box (`ContentAvatar.bones[bone].size`
if 2 values are given, else `STANDARD_BONE_BOX[bone]`), stacked from the feet up: the legs set
`legsTop`; the torso sits on `legsTop` and defines `torsoTop`/`torsoLeft`/`torsoRight`; the head
sits 1.5au above the torso; the arms hang off the torso's shoulders; hands sit at the bottom of
their own arm's (already-offset) box. `ContentAvatar.bones[bone].offset` (`[x, y]`) is added to
that bone's own origin and pivot only, after the formula above — a hand does NOT additionally
inherit its arm's offset beyond what the arm formula already propagates through the arm's
offset-adjusted origin/pivot.

### L/R convention

`*_R` bones (`ARM_R`/`HAND_R`) are the character's right side, which is the **viewer's left** at
yaw 0 — they hold `HAND_RIGHT`-slot items. `*_L` bones are the viewer's right and hold
`HAND_LEFT`-slot items. This is intentional: it keeps the old renderer's look (wand on the
viewer's left, shield on the viewer's right).

### Mirror semantics

A layer with `"mirror": "x"` on a mirrorable bone (`Bone.mirrorOf() != null`) is drawn twice: once
as authored, and once on the opposite bone with the SAME path, flipped horizontally in place by
the placement matrix (`scale(-1, 1)` after `translate(W, 0)` — never by re-parsing or copying the
path). `W` is the STANDARD bone width for an item layer, or the EFFECTIVE (possibly overridden)
bone width for an avatar part layer. Part layers are never scaled by the eff/std ratio the way
item layers are (the validator checks a part's layer bounds against the avatar's own effective
box already), so using the standard width for items but the effective width for parts keeps both
checks and renders consistent.

### Item scale pivots

An item's own `scale` (if != 1) is applied around a bone-specific pivot, as a fraction of the
STANDARD bone box: `HEAD`/`TORSO`/`ARM_*` → `(.5, 0)` (top centre), `HAND_*` → `(.5, .5)`
(centre), `LEG_*`/`ROOT` → `(.5, 1)` (bottom centre), `TAIL` → `(0, 0)` (the tail's own origin).

### Breath

Only `TORSO` breathes by default (`breathScaleY` from `AvatarPose.breath` × the idle pose's
`torsoBreathScaleY`), pivoting around the torso's bottom-centre (`(0, legsTop)`, in px). A layer on
any other bone can opt in with `"breath": true`, in which case it scales around that SAME torso
pivot/scale — not its own bone's.

### Block side face

A `"shading": "block"` layer's path is drawn twice: once shifted horizontally by
`layer.depth * sign(yaw)` and darkened, behind the front face (the "side"), then the normal
front face (a vertical gradient) and a lightened top-edge strip on top.

### Decoration targets

An item's pattern fill and `TRIM` ornament stroke are drawn right after each of its "decoration
target" layers: the layers with `"patternClip": true`, or ALL of the item's layers if none of
them set it.

### Face

The face (eyes + smile) is drawn by app code, not content data, at the `FACE` z-bucket, in
`HEAD`-bone-local au (as if it were an unmirrored item layer with scale 1). Avatars only choose
`face.eyeStyles`/`face.smileStyles`; the eye/smile shape alternates with `eyeIndex`/`smileIndex`.

### Preview framing

An item preview is framed from its PLACED bounds (after the bones have been positioned, with a
10% margin on every side) — never from `ActiveContent.itemBounds`, which unions boxes from
different bones' LOCAL spaces and would collapse a multi-bone item (e.g. mirrored gloves) onto
one spot. `ItemPreviewHints.yaw` is in RADIANS (unlike the turntable's degrees).

`ItemPreview` draws on standard bones (`ItemDrawList.avatar` is the default avatar with `bones`
cleared, keeping its skin, tint and palette), so every item shows its authored proportions
whatever avatar a player wears. With `preview.ghostBody`, the ghost uses only the default
avatar's base body parts (`BASE_BODY_PARTS`, which mirrors `avatar_body.BODY_PART_NAMES`: torso,
arms, hands, legs, pants, shoes, head) on the bones the item places, minus `hidesParts`. Signature
parts (ears, muzzle, stripes, hair...) are never shown. Each ghost layer carries
`PlacedLayer.authoredBoneSize` (the default avatar's effective box) and is stretched to the
standard box, mirrored within the authored width first.

### Avatar frame (026)

`avatarFrame(canvasSize, avatar)` shrinks an avatar (below the base `min(w,h)/340` scale, never
below 0.45× that) whenever its HEAD top plus a 67au "tallest hat" reach, scaled to that avatar's
own effective HEAD height, would exceed a 290au budget above the feet — e.g. the witch's tall
head with a style-4 top hat. Standard-size avatars (cat, dog) are unaffected; the witch shrinks
the most (~5%). Feet sit at `h * 0.56 + 108 * base` (previously 96; moved down 12au so the
tallest hat still clears the canvas in the 340dp screenshot). A `null` avatar (or one without bone
overrides) keeps the old, unshrunk scale. `ItemPreview` does not use `avatarFrame`; see Preview
framing.

## Avatar sources (pipeline)

An avatar source JSON (`tools/content_pipeline/pack/templates/avatars/avatar_*.json`) is compact:

- `bones`: `{"HEAD": [w,h], "TORSO": [w,h], "ARM": [w,h], "LEG": [w,h]}`. Missing keys mean
  standard. `avatar_body.expand_bones` mirrors `ARM`/`LEG` into `ARM_L`/`ARM_R`/`LEG_L`/`LEG_R`
  and derives `HAND_L`/`HAND_R` as `(armW, round(armH * 0.475, 2))` — the standard 38×80 arm's
  hand stays 38×38 — so sleeves, gloves and hand-held items always line up with no gap. `TAIL` is
  always the standard 40×60; it isn't part of an avatar's silhouette table.
- `palette`: `{primary, secondary, accent}`, written straight into `ContentAvatar.palette`.
- `pose`: `AvatarPose` fields that differ from `1.0`.
- `extras`: a list of ids from `templates/avatars/extras.py`'s `EXTRAS` map. Each id builds one
  named part; a part whose name already exists in `avatar_body.body_parts` (torso, arms, hands,
  legs, pants, shoes, head) **replaces it in place** instead of being appended — this is how a
  skirt or robe becomes the avatar's own `pants` part (so a `PANTS` item still hides it) and how
  stockings become its `legs` part.
- Z-bucket rule for extras: `drawLayerList` draws the face just before the first `FACE`+ layer, so
  a PATCH feature that must sit under the eyes/smile (muzzle, snout, tongue, cheek, jaw shadow,
  eye spot, stripes, chin) goes in z `HEAD` with `order >= 2` (the head block itself is order 1).
  A LINE or NOSE feature that draws over the face (whiskers, nose, lashes, brows) goes in z
  `FACE`.
