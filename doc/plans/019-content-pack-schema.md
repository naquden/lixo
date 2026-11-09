# 019 — Content pack: schema, skeleton contract, validator, runtime index

**Type**: feature
**Depends on**: none. This is the first of 019–025, which together make avatars, items, events and drop rules cloud content. The full design lives in this plan, and 020–025 refer back to it.

## Goal
Define one versioned **content pack** (JSON) that describes avatars, wardrobe items, events and drop rules, so the app can build every avatar and item from data. After that, a holiday special is new content in Firestore and needs no app release. This task only adds the pure model layer: DTOs, the skeleton enums, the validator and the runtime index. Nothing in the app uses it yet (that starts in 022).

The app is unreleased, so there is no backwards compatibility to keep.

## Context
- All shapes are Kotlin today:
  - `ui/components/avatar3d/Avatar3DController.kt` dispatches every slot renderer on `when(item.styleIndex.mod(8))` (`drawHat` :503, `drawShoulders` :600, `drawTail` :685, `drawPants` :748, `drawShoes` :813, `drawArmorShirt` :865, `drawGlovesBlock` :931, `drawHandRightWeapon` :970, `drawHandLeftItem` :1036).
  - The body and z-order are in `drawLowPolyAvatarScene` :1085–1342.
  - CAT/DOG ears are hardcoded at :1262/:1277.
- The domain is enums in `domain/model/Models.kt`: `AvatarType` :9 (6 entries), `EquipmentSlot` :29, `Rarity` :49 (5 tiers, `giftDropWeight`), `Subject` :74, `ItemPattern` :186, `ItemOrnament` :194, `AvatarAppearance` :136 and `WardrobeItem` :201.
- The drop constants live in `domain/model/BalanceConfig.kt`: `CHEST_POINTS_*` :119–125, `PITY_*` :134–136, `ONBOARDING_REWARD_LEVELS`, `TUTORIAL_FIRST_REWARD_ITEM_ID` :11 and `WEEKLY_STREAK_DAYS_FOR_SET_REWARD` :93.
- Body anchors at scale=1, from `drawLowPolyAvatarScene`: head 56×56, torso 80×80, arm 38×80, leg 40×80, tail at torsoRight−4/torsoBottom−10.
- Compose MP has `androidx.compose.ui.graphics.vector.PathParser` in common code. Use it to check that path data parses. The validator is pure, but it can still use the `ui-graphics` Path, which is fine in commonMain.

## Design (shared by 019–025)

### Stays in code: the skeleton contract
`EquipmentSlot`, `Rarity` (5 tiers kept; only their weights become data), `Subject`, `ItemPattern`, `ItemOrnament` and the achievement ids.

New in `domain/content/Skeleton.kt`:
- `enum Bone { ROOT, TORSO, HEAD, ARM_L, ARM_R, HAND_L, HAND_R, LEG_L, LEG_R, TAIL }`
- `enum ZBucket { BACK, TAIL, LEGS, LEGWEAR, FOOTWEAR, TORSO, TORSO_OVER, ARM_BACK, ARM, HAND, HEAD, FACE, HEAD_OVER, HAT, FRONT }`. The order is the draw order.
- `STANDARD_BONE_BOX: Map<Bone, Size>`: HEAD 56×56, TORSO 80×80, ARM_* 38×80, HAND_* 38×38, LEG_* 40×80, TAIL 40×60, ROOT 160×240.
- `DEFAULT_PARALLAX: Map<Bone, Float>`: HEAD 1.0, TORSO 0.5, arms 0.5, legs 0.3, TAIL −0.4. Items in the BACK bucket use −0.6.

### Coordinates
Coordinates are bone-local "avatar units" (au). The origin is the top-left of the bone box and y points down. An avatar can override `bones[B].size`/`offset`. When it does, the renderer scales an item's layers by `avatarBone.size / STANDARD_BONE_BOX[B]`, so one item fits every avatar. Every path point must lie inside the bone box expanded by 1.5× its size on each side, so hats and capes can stick out.

### Pack schema (`schemaVersion = 1`)
```json
{ "schemaVersion":1, "version":42, "publishedAt":"2026-11-20T10:00:00Z",
  "strings":{"sv":{"item_hat_jul_tomte.name":"Tomteluva"}},
  "avatars":[{"id":"avatar_cat","nameKey":"avatar_cat.name","order":0,"humanoid":false,
    "skinHex":"#F2B880","defaultTintHex":"#F5A623",
    "unlock":{"type":"default"},                       // or {"type":"stars","stars":12}
    "bones":{"HEAD":{"size":[56,56],"offset":[0,0]}},  // optional overrides
    "pose":{"headBob":1.0,"breath":1.0,"armSwing":1.0,"tailWag":1.0},
    "parts":[{"part":"ears","layers":[Layer]},{"part":"legs","layers":[Layer]},
             {"part":"tail","layers":[Layer]},{"part":"body","layers":[Layer]}],
    "face":{"eyeStyles":4,"smileStyles":4}}],
  "items":[{"id":"item_hat_jul_tomte","slot":"HAT","rarity":"EPIC","subjectTheme":"ENGLISH",
    "nameKey":"…","descKey":"…",
    "palette":{"primary":"#C0392B","secondary":"#FFFFFF","accent":"#F1C40F"},
    "pattern":"NONE","ornament":"SPARKLE","scale":1.0,"hidesParts":["ears"],
    "layers":[Layer],
    "pool":"standard",            // standard | event | achievement | level | setpiece | none
    "eventId":null,"retired":false,
    "preview":null}],             // optional {"ghostBody":true,"yaw":0.35}
  "events":[{"id":"event_jul_2026","nameKey":"…","start":"2026-12-01","endExclusive":"2026-12-27","tz":"local",
    "itemIds":["…"],"chestTheme":{"bodyHex":"#B03A2E","trimHex":"#F1C40F","ornament":"SPARKLE"},
    "banner":{"titleKey":"…","bgHex":"#…","fgHex":"#…"},
    "dropBoost":{"eventItemChance":0.5,"presentGiftChance":0.5},
    "afterEnd":"retire"}],        // retire | joinStandard
  "dropRules":{"rarityWeights":{"COMMON":40,"UNCOMMON":28,"RARE":18,"EPIC":10,"LEGENDARY":4},
    "chestCostHalfPoints":20,"progressRunHalfPoints":2,"replayRunHalfPoints":1,
    "pity":{"RARE":4,"EPIC":10,"LEGENDARY":20},
    "onboarding":{"levels":[…]},
    "starUnlocks":[{"stars":31,"itemId":"…"}],
    "levelRewards":[{"level":1,"itemId":"…"}],
    "achievementRewards":{"ach_weekly_streak_7":"item_hat_weekly_hero"},
    "tutorialRewardItemId":"item_hat_starter_explorer",
    "weeklyStreakSetPieceItemIds":["item_hat_weekly_hero"]} }
```

**Layer:**
- `bone`, `z` (ZBucket), `order` (int, sorts within a bucket)
- `d`: SVG path data in au
- `fill`: `{role, shade?}`. `role` is one of `primary|secondary|accent|skin|tint|eye|outline`; `shade` is in −1..1 and lightens or darkens.
- `stroke?`: `{role, width}`
- `shading`: `flat|gradient|block`. Default `gradient`. `block` with `depth` reuses the yaw side face from `drawBlock`.
- `parallax?`: overrides the bone default
- `mirror`: `none|x`. With `x`, the layer is also drawn on the opposite L/R bone, mirrored.
- `patternClip`: bool. The item's pattern is clipped to this layer.
- `breath`: bool. The layer follows the torso breath scale.

**Palette roles:** `primary`, `secondary` and `accent` come from the item palette. `skin` comes from the avatar. `tint` is the profile's `primaryTintHex`, falling back to the avatar's `defaultTintHex`. `eye` and `outline` are app constants.

**Body-modifying cases become layering:**
- The avatar always draws its skin legs. Shorts are just shorter LEGWEAR.
- A robe or dress goes in TORSO_OVER.
- Horns are ordinary HAT layers.
- Ears and tail are avatar `parts`.
- An item hides avatar parts with `hidesParts`, for example a helmet over the ears.

**Level rewards are an explicit list per level**, so adding items never shifts the mapping. Levels without an entry give no curated reward.

### Validation (`ContentPackValidator.validate(json: String, appAchievementIds: Set<String>, previous: ActiveContent?) : ValidationResult`)
`ValidationResult` is `Rejected(reason)` or `Accepted(pack, skipped: List<Skip>)`. Use lenient JSON with `ignoreUnknownKeys`. Decode items, avatars and events one at a time from `JsonElement`, so a single bad entry is skipped instead of failing the whole pack.

**Caps:**
- raw size ≤ 6 MB, ≤ 3000 items, ≤ 64 avatars, ≤ 64 events
- ≤ 24 layers per item or part; `d` ≤ 4 KB
- ids match `^[a-z][a-z0-9_]{2,63}$`; hex is `#RRGGBB` or `#AARRGGBB`
- `scale` in 0.5..2
- every `d` parses with `PathParser`, is not empty, and its bounds lie inside the expanded bone box

**Reject the whole pack (the caller keeps the current one):**
- invalid JSON, or `schemaVersion != SUPPORTED_SCHEMA_VERSION (1)`
- `dropRules` invalid: missing or ≤ 0 weights for any rarity, pity counts not strictly increasing RARE < EPIC < LEGENDARY, costs ≤ 0
- any `appAchievementIds` entry without a valid reward item
- any invalid `levelRewards`, `starUnlocks`, `tutorialRewardItemId` or set-piece item. This includes ids that point at skipped items.
- no valid avatar with `unlock.type == default`
- an item id present in `previous` is missing: **owned items must stay renderable forever**
- the version is not greater than `previous.version`. The caller is allowed to skip this check for the bundled pack.

**Skip one entry and log it** (`println("LixoContent: skip <kind> <id>: <reason>")`):
- an item with a bad layer, unknown slot, rarity, pattern or ornament, or a bad `eventId`
- an avatar that is invalid and not a default avatar
- an event with `start >= endExclusive`, or with 0 valid items once skipped items are removed

Skipped items are also removed from event `itemIds`.

### Runtime index (`ActiveContent`)
An immutable class with:
- `version`
- `itemsById`, `avatarsById`, `avatarsOrdered`
- `standardPoolByRarity: Map<Rarity, List<Item>>` (`pool == standard && !retired`)
- `eventsById`, `dropRules`
- `string(key, lang = "sv")`, which falls back to the key itself
- `parsedPath(layer)`: a lazy `Path` cache keyed by layer identity, never parsed per frame
- `itemBounds(itemId)`: the union of the layer bounding boxes in au, computed once

## Steps
1. Create `composeApp/src/commonMain/kotlin/se/lixo/app/domain/content/`:
   - `Skeleton.kt` (Bone, ZBucket, STANDARD_BONE_BOX, DEFAULT_PARALLAX, `SUPPORTED_SCHEMA_VERSION = 1`)
   - `ContentPack.kt` (`@Serializable` DTOs exactly as in the schema above, with enums deserialized by name; `ItemPool`, `Shading`, `Mirror`, `PaletteRole` and `AfterEnd` are enums)
   - `ContentPackValidator.kt`
   - `ActiveContent.kt` (plus `ActiveContent.from(pack)`)
2. The DTO names must not clash with the current `WardrobeItem`/`AvatarAppearance`. Use `ContentItem`, `ContentAvatar`, `ContentEvent`, `DropRules` and `ContentLayer`. 022 replaces the old models.
3. Add `doc/content/content-pack.md`: the schema reference, the coordinate system, the bone boxes, the z buckets, the validation table and the stability rules (see 021/024). Copy the Design section above and expand it with one worked item example.
4. Add `composeApp/src/commonTest/resources/` (or an inline Kotlin string fixture if commonTest resources aren't set up): a `test_pack.json` with 1 default avatar, 1 star avatar, 1 item per slot, 1 event and full drop rules. 020 reuses it.

## Tests / verification
commonTest (new files under `composeApp/src/commonTest/kotlin/se/lixo/app/content/`):
- `ContentPackParseTest`: the fixture round-trips; unknown keys are ignored; enums decode by name.
- `ContentPackValidatorTest`, one test per rule:
  - Accepted when valid.
  - Skipped: an unknown slot (other items kept), a bad path `d="M 0 0 L"`, out-of-bounds points, 25 layers, a bad hex, an event left with 0 items after skips.
  - Rejected: schemaVersion 2, rarity weight 0, pity not increasing, a missing achievement reward, a levelReward pointing at a skipped item, no default avatar, an id from `previous` missing, version ≤ previous.
- `ActiveContentIndexTest`: the standard pool excludes retired items and non-standard pools; `itemBounds` is correct for a known path; `string()` falls back to the key.

Run with `./gradlew :composeApp:testAndroidHostTest` (or `:composeApp:allTests`).

No instrumented test: nothing renders yet.

## Open questions
- None blocking. Only `sv` strings exist; the `strings` map is keyed by language for later.
