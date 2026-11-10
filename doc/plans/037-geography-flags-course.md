# 037 — Geography world: "Which flag does <country> have?"

**Type**: feature

## Goal
New world "Världsresan" (Subject.GEOGRAPHY), 30 levels × 8 questions. Prompt "Vilken flagga har Sverige?",
4 flag images to choose from. Countries introduced in fame order (Nordic + world-famous first), all
194 countries (193 UN + Vatikanstaten) covered by level 30; distractors get harder with difficulty.
Territories (Grönland, Färöarna, Åland) and partially recognised states (Kosovo, Taiwan, Palestina) are
excluded. Stars per level: 1 for completing, +1 for no wrong answer, +1 for finishing within the
prestige time. Completing N geography levels unlocks new flag/country gear (a new achievement ladder
with newly authored items).

## Context (current code)
- `Subject` enum: `composeApp/src/commonMain/kotlin/se/lixo/app/domain/model/Models.kt:72` (SWEDISH/MATH/ENGLISH, each with `primaryRewardSlots`).
- `MathProblemType` `Models.kt:119`, `HomeworkTask` `Models.kt:271`, `TaskType` `Models.kt:284`, `DailyActivityRecord` `Models.kt:355`.
- Worlds: `ui/screens/map/SubjectWorld.kt:23` — comment already anticipates a 4th geography world. Map filters levels by subject (`CampaignMapScreen.kt:305,551`), node color/icon `when` at `CampaignMapScreen.kt:825-835`.
- Levels/tasks are code-generated: `Repositories.kt:384-400` seeds courses and calls `buildSeedCampaignLevelsAndTasks` (`Repositories.kt:1259`); math uses `domain/content/MathCurriculum.kt` (orders 2..98 even + 101..160). Highest order today = 160.
- English reuses `MathActivityScreen` (`ui/screens/math/MathActivityScreen.kt:98-135`): converts items to `MathProblem` with runtime-shuffled `choices` (seed `choiceSeed`), via `domain/content/EnglishChoices.kt`. Choices rendered as text `ChoiceCard` (`ui/theme/LixoComponents.kt:361`) at `MathActivityScreen.kt:351-388`.
- Routing: `LixoApp.kt:306` READING → reading screen, else `MathActivityScreen` (`LixoApp.kt:369`).
- Daily activity `when(level.subject)` exhaustive: `ProgressionAndGiftEngine.kt:441`. Unlock is per subject (`ProgressionAndGiftEngine.kt:251,267`).
- `LixoStrings.subjectName` `ui/theme/LixoStrings.kt:271`; world strings `LixoStrings.kt:54-59`.
- No flag/country data, no `composeResources/drawable` dir yet. CMP 1.10.3 → `Res.allDrawableResources` available for lookup by name.
- Stars: `MathEngine.evaluateMathSession` (`domain/engine/MathEngine.kt:65-91`) already gives 1 + (elapsed ≤ `timeRequirementSeconds`) + (0 wrong attempts) — exactly the requested rule; reused as-is. Hints don't cost stars.
- Achievement ladders: `domain/model/AchievementLadders.kt` (`ACHIEVEMENT_LADDERS`, `counting`, `achievementId` → `ach_<action>_<target>`), `AchievementAction` enum `Models.kt:369`, names/descriptions `domain/model/AchievementStrings.kt:18,36,52`, counting `domain/engine/AchievementEngine.kt:88-97`. **Bug to avoid**: `READ_STORY -> facts.subject != Subject.MATH` (`AchievementEngine.kt:90`) would count geography levels as stories.
- Gear content: content pack sources in `tools/content_pipeline/pack/` (README there); hand-authored items with explicit `layers` + `palette {primary, secondary, accent}` + `pool` are in `catalog/handmade.json` and `catalog/jul_2026.json` (good template: bones `HEAD`/`TORSO`/`HAND_L`…, z buckets, `fill.role`). Achievement→item mapping in `drop_rules.json` `achievementRewards` (append-only, stability diff in `validate_pack.py`). Schema: `doc/content/content-pack.md` (layer fill only by role → max 3 item colours + outline/skin/tint).
- Theme colors available: Orange/OrangeLip/OrangeTint unused by worlds (`LixoTheme.kt:59-61`). No globe icon in `LixoIcons.kt`.

## Steps

### 1. Flag assets (tool + resources)
- New `tools/flag_pipeline/build_flags.py`: download pinned **lipis/flag-icons** (MIT) release, take `flags/4x3/<iso>.svg` for the 194 codes in `tools/flag_pipeline/countries.txt` (one lowercase iso2 per line, same order as step 2), rasterize `rsvg-convert -w 240 -h 180`, encode `cwebp -q 85`, write `composeApp/src/commonMain/composeResources/drawable/flag_<iso>.webp`. Fail if any code missing. Commit outputs (target total < 1 MB).
- Add `doc/third_party/flag-icons-LICENSE.md` (MIT text + version + URL).

### 2. Country data — `domain/content/GeographyCountries.kt`
```kotlin
enum class FlagRegion(val swedishName: String) { NORDEN("Norden"), EUROPA("Europa"), ASIEN("Asien"), AFRIKA("Afrika"), NORDAMERIKA("Nordamerika"), SYDAMERIKA("Sydamerika"), OCEANIEN("Oceanien") }
data class Country(val iso2: String, val nameSv: String, val region: FlagRegion, val colorsSv: List<String>, val isNordic: Boolean = false)
object GeographyCountries { val RANKED: List<Country>; val LOOKALIKE_GROUPS: List<Set<String>>; fun byIso(iso2: String): Country }
```
`RANKED` = fame order below (Swedish child perspective; Nordic first, then world-famous, then Europe/large, then the rest). `colorsSv` = main flag colours ("blå", "gul", …) used for hint 2.

1–20: SE Sverige, NO Norge, DK Danmark, FI Finland, IS Island, US USA, GB Storbritannien, DE Tyskland, FR Frankrike, IT Italien, ES Spanien, JP Japan, CN Kina, CA Kanada, BR Brasilien, AU Australien, RU Ryssland, IN Indien, MX Mexiko, NL Nederländerna
21–50: GR Grekland, PT Portugal, CH Schweiz, AT Österrike, PL Polen, TR Turkiet, EG Egypten, BE Belgien, IE Irland, UA Ukraina, EE Estland, AR Argentina, ZA Sydafrika, KR Sydkorea, TH Thailand, NZ Nya Zeeland, HR Kroatien, LV Lettland, LT Litauen, CZ Tjeckien, HU Ungern, IL Israel, JM Jamaica, CU Kuba, MA Marocko, SA Saudiarabien, ID Indonesien, VN Vietnam, CL Chile, CO Colombia
51–100: KE Kenya, NG Nigeria, PE Peru, PH Filippinerna, AE Förenade Arabemiraten, KP Nordkorea, IR Iran, IQ Irak, PK Pakistan, SG Singapore, MY Malaysia, RO Rumänien, BG Bulgarien, RS Serbien, SK Slovakien, SI Slovenien, LU Luxemburg, MT Malta, CY Cypern, MC Monaco, VA Vatikanstaten, LI Liechtenstein, AD Andorra, SM San Marino, BA Bosnien och Hercegovina, AL Albanien, MK Nordmakedonien, ME Montenegro, MD Moldavien, BY Belarus, GE Georgien, VE Venezuela, UY Uruguay, EC Ecuador, BO Bolivia, PY Paraguay, ET Etiopien, TN Tunisien, DZ Algeriet, QA Qatar, MN Mongoliet, AF Afghanistan, NP Nepal, TZ Tanzania, GH Ghana, SN Senegal, MG Madagaskar, SY Syrien, JO Jordanien, LB Libanon
101–150: KZ Kazakstan, BD Bangladesh, LK Sri Lanka, MM Myanmar, KH Kambodja, LA Laos, UZ Uzbekistan, AM Armenien, AZ Azerbajdzjan, KW Kuwait, OM Oman, YE Jemen, BH Bahrain, LY Libyen, SD Sudan, SO Somalia, CD Kongo-Kinshasa, UG Uganda, RW Rwanda, CM Kamerun, CI Elfenbenskusten, AO Angola, MZ Moçambique, ZW Zimbabwe, ZM Zambia, NA Namibia, BW Botswana, CR Costa Rica, PA Panama, DO Dominikanska republiken, HT Haiti, GT Guatemala, HN Honduras, SV El Salvador, NI Nicaragua, BS Bahamas, BB Barbados, TT Trinidad och Tobago, FJ Fiji, PG Papua Nya Guinea, MV Maldiverna, BT Bhutan, KG Kirgizistan, TJ Tadzjikistan, TM Turkmenistan, BN Brunei, TL Östtimor, ML Mali, NE Niger, TD Tchad
151–194: BF Burkina Faso, BJ Benin, TG Togo, GN Guinea, GW Guinea-Bissau, SL Sierra Leone, LR Liberia, GM Gambia, MR Mauretanien, CV Kap Verde, GQ Ekvatorialguinea, GA Gabon, CG Kongo-Brazzaville, CF Centralafrikanska republiken, SS Sydsudan, ER Eritrea, DJ Djibouti, BI Burundi, MW Malawi, LS Lesotho, SZ Eswatini, KM Komorerna, MU Mauritius, SC Seychellerna, ST São Tomé och Príncipe, GY Guyana, SR Surinam, BZ Belize, GD Grenada, LC Saint Lucia, VC Saint Vincent och Grenadinerna, KN Saint Kitts och Nevis, AG Antigua och Barbuda, DM Dominica, WS Samoa, TO Tonga, VU Vanuatu, SB Salomonöarna, KI Kiribati, TV Tuvalu, NR Nauru, MH Marshallöarna, FM Mikronesien, PW Palau

Region: Nordic 5 → NORDEN (`isNordic = true`); others by continent (TR, CY, GE, AM, AZ, RU → EUROPA; EG → AFRIKA; Central America/Caribbean → NORDAMERIKA).

`LOOKALIKE_GROUPS` (used for hard distractors): {SE,NO,DK,FI,IS}, {ID,MC,PL,SG}, {RO,TD,AD,MD}, {IE,CI,IT,MX}, {NL,LU,FR,RU,HR,SK,SI,RS}, {AU,NZ,FJ,TV}, {VE,CO,EC}, {SN,ML,GN,CM}, {HN,NI,SV,AR,GT}, {QA,BH}, {AE,KW,JO,SD,SY,IQ,YE,EG}, {US,LR,MY}, {GH,BO,LT,ET}, {AT,LV,LB}, {DE,BE,UG}, {JP,BD,PW}, {CU,DO,PA}. Implementer: keep only groups of ≥2 codes all in RANKED; tune freely.

### 3. Curriculum — `domain/content/GeographyCurriculum.kt` (pattern: `MathCurriculum.kt`)
- `LEVEL_COUNT = 30`, `QUESTIONS_PER_LEVEL = 8`, `FIRST_ORDER = 161` (orders 161..190), courseId `course_geo_1`.
- Chunks of new countries along `RANKED`: level 1 = ranks 1–8 (8 new); remaining 186 over levels 2–30: levels 2–13 get 7 new, levels 14–30 get 6 new. Fill each level to 8 with review countries from earlier chunks (seeded `Random(order)`, prefer last 3 levels). Shuffle question order (seeded).
- `difficulty = (levelIndex - 1) / 5 + 1` → 1..6. Known pool = all countries introduced up to and including this level.
- Distractors (3, unique, ≠ correct), deterministic per `(order, questionIndex)`:
  - d1–2: random from known pool, excluding look-alikes of the correct flag (easy, visually distinct).
  - d3–4: random from known pool.
  - d5–6: 1–2 from correct flag's look-alike group (any rank) if group exists, else same `region`; rest from known pool.
- Output `CampaignLevel(id="level_$order", courseId, order, title="Bana ${idx}: Flaggor från ${region of first new country}", biomeNameSv="Världsresan", subject=GEOGRAPHY, difficulty, timeRequirementSeconds = QUESTIONS_PER_LEVEL * (5 + difficulty) /* prestige time: 48 s at d1 … 88 s at d6 */, referenceWpm = BalanceConfig.referenceWpmForDifficulty(difficulty), taskId="task_$order")` and `HomeworkTask(type=GEOGRAPHY_FLAGS, subject=GEOGRAPHY, flagQuestions=…, rewardItemId=levelRewardByOrder[order])`.

### 4. Model
- `Subject.GEOGRAPHY(id="geography", swedishNameKey="subject_geography", primaryRewardSlots=listOf(EquipmentSlot.SHOULDERS_BACK, EquipmentSlot.HAT))`.
- `TaskType.GEOGRAPHY_FLAGS`; `MathProblemType.FLAG("flag", "math_type_flag")` (+ string key wherever math_type_* keys resolve; fix any exhaustive `when`).
- `@Serializable data class FlagQuestion(val id: String, val countryIso2: String, val distractorIso2: List<String>)`; `HomeworkTask.flagQuestions: List<FlagQuestion> = emptyList()`.
- `DailyActivityRecord.geographyTasksCompleted: Int = 0`.

### 5. Stars
- No engine change: geography runs go through `MathActivityScreen` → `MathEngine.evaluateMathSession` (1 complete + 1 zero wrong + 1 within `timeRequirementSeconds`). Make sure the screen's timer/"prestige time" display (whatever math shows) is shown for geography too, so the kid sees the target time. Add a commonTest asserting the three cases for a geography level's `timeRequirementSeconds`.

### 6. Reward ladder — new `AchievementAction.EXPLORE_WORLD`
- Append `EXPLORE_WORLD` at the **end** of `AchievementAction` (order = seed/queue order; appending keeps existing order). `counting = DISTINCT_LEVELS`; qualifies when `facts.subject == Subject.GEOGRAPHY`.
- Fix `AchievementEngine.kt:90`: `READ_STORY -> facts.subject == Subject.SWEDISH || facts.subject == Subject.ENGLISH` (keeps today's behaviour, excludes geography). Check `SOLVE_MATH`, `FIRST_TRY`, `PERFECT_LEVEL`, `COLLECT_STARS` behave sensibly for geography (PERFECT_LEVEL/COLLECT_STARS may count geography — fine; FIRST_TRY counts first-try problems — fine).
- Ladder (8 tiers, distinct geography levels completed):
  | target | rarity | reward item id | slot | design |
  |---|---|---|---|---|
  | 1 | COMMON | `item_hand_right_geo_sverigeflagga` | HAND_RIGHT | Swedish flag on a short pole (blue #006AA7, yellow #FECC02) |
  | 3 | COMMON | `item_hand_left_geo_norgeflagga` | HAND_LEFT | Norwegian flag on pole (red #BA0C2F, white, blue #00205B) |
  | 6 | UNCOMMON | `item_hat_geo_upptackarhatt` | HAT | Explorer/safari hat, khaki with a small globe badge |
  | 10 | UNCOMMON | `item_shoulders_back_geo_resryggsack` | SHOULDERS_BACK | Travel backpack with rolled map on top |
  | 15 | RARE | `item_shoulders_back_geo_nordenmantel` | SHOULDERS_BACK | Cape with a Nordic cross (blue/yellow, cross via `secondary` layer) |
  | 20 | RARE | `item_hand_left_geo_kompass` | HAND_LEFT | Brass compass with red needle |
  | 25 | EPIC | `item_armor_shirt_geo_flaggtroja` | ARMOR_SHIRT | Jersey with Nordic-cross chest stripe (Danish red/white) |
  | 30 | LEGENDARY | `item_hand_right_geo_jordglob` | HAND_RIGHT | Globe on a staff (ocean blue, land green, gold ring) |
  Respect existing shared-hand behaviour (plan 032: HAND items work in either hand). Avoid national-costume stereotypes; gear = flags, travel/explorer and Nordic cross motifs only.
- Content: new `tools/content_pipeline/pack/catalog/geography.json` with the 8 items (`pool: "achievement"`, `subjectTheme: "GEOGRAPHY"`, explicit `layers` authored like `catalog/jul_2026.json`, fit inside `STANDARD_BONE_BOX`; held items follow plan 027/028 hand-item scale + preview bones). Wire it into `build_pack.py` like `jul_2026.json`; add names/descriptions to `strings/sv.json` (e.g. "Sverigeflagga" / "Du klarade din första flaggbana!"). Append the 8 `ach_explore_world_<n>` → item ids to `drop_rules.json` `achievementRewards` (append only). Rebuild `python3 tools/content_pipeline/pack/build_pack.py`, run `validate_pack.py` (0 issues, stability diff ok), commit regenerated `composeResources/files/content_pack.json` and `tools/tests/fixtures/mini_pack.json`.
- Kotlin: add the ladder to `ACHIEVEMENT_LADDERS`; strings in `AchievementStrings.kt` — title "Världsresenär", tier "Klara 1 flaggbana" / "Klara $n flaggbanor", description likewise. If `Subject` is used for item `subjectTheme` parsing anywhere, GEOGRAPHY must deserialize (it will, once enum added). Check `ContentPackValidator` / `validate_pack.py` accept `subjectTheme: "GEOGRAPHY"` (Python validator may have a hard-coded subject list — extend it).
- Update anything that hard-codes ladder counts (KDoc says "9 ladders, 48 tiers" → 10 / 56; grep tests for `48`, `ACHIEVEMENT_LADDERS.size`, AchievementLadderTest).

### 7. Wiring
- `Repositories.kt:384`: add `Course("course_geo_1", "Världsresan: Länder & flaggor", Subject.GEOGRAPHY, minGrade = 1, version = 1, updatedAt = seedTimestamp)`; in `buildSeedCampaignLevelsAndTasks` append `GeographyCurriculum` levels/tasks. Check any Firestore curriculum delta/merge path doesn't drop code-generated GEOGRAPHY levels (same as math).
- `ProgressionAndGiftEngine.kt:441`: `Subject.GEOGRAPHY -> copy(geographyTasksCompleted = +1)`. Make sure first GEOGRAPHY level is unlocked (per-subject unlock at :251/:267).
- `SubjectWorld.kt`: 4th entry `Subject.GEOGRAPHY`, `WORLD_GEOGRAPHY_TITLE = "Världsresan"`, `WORLD_GEOGRAPHY_TAGLINE = "Länder & flaggor"`, Orange colors, new `LixoIcons.Globe` (Material "public" path).
- `CampaignMapScreen.kt:825-835`: GEOGRAPHY → Orange triple, Globe icon. Verify world pager/selector handles 4 worlds.
- `LixoStrings.subjectName`: `Subject.GEOGRAPHY -> "Geografi"`.
- Grep all `when (…subject)` / `when (…type)` for exhaustiveness after enum changes.

### 8. Challenge UI
- `MathActivityScreen.kt:113`: add branch `task.type == TaskType.GEOGRAPHY_FLAGS` → map each `FlagQuestion` to `MathProblem(id, type=FLAG, promptSv="Vilken flagga har ${nameSv}?", correctAnswer=iso2, choices=(distractors+iso2).shuffled(random), hintStep1Sv="${nameSv} ligger i ${region.swedishName}.", hintStep2Sv="Flaggan har färgerna ${colorsSv joined "," / "och"}.")` using same `choiceSeed` mechanism as English. Put this mapping in `GeographyChoices.buildProblems(questions, random)` (domain/content) so tests share it.
- When `problem.type == FLAG`: render choices as a 2×2 grid of new `FlagChoiceCard(iso2, isCorrect, onClick)` in `LixoComponents.kt` — same face/lip/border states as `ChoiceCard`, flag image 4:3 via `painterResource(Res.allDrawableResources["flag_$iso"])` with 1dp `Border` outline (white flags like JP), `Modifier.testTag("flag_choice_$iso")`, no country name in contentDescription (would reveal answer). No badge/prefix (consistent with plan 035: options show only content).
- Wrong/solved logic unchanged (string compare on iso2). Errors logged with type FLAG.

## Tests / verification
commonTest:
- `GeographyCountriesTest`: 194 unique iso2 codes; first 8 ranks ⊇ all 5 Nordic; every country has non-empty nameSv/colorsSv; lookalike codes all exist.
- `GeographyCurriculumTest`: 30 levels at orders 161..190, 8 questions each; every country is a correct answer at least once; new-country max rank strictly increases per level; 3 unique distractors ≠ correct; d1–4 distractors within known pool; d1–2 no look-alike of correct; d5–6 has ≥1 look-alike when group exists; deterministic across runs.
- `GeographyChoicesTest`: 4 choices, correct included exactly once, seed-stable, correct position varies.
- androidHostTest: every `RANKED` code has `composeResources/drawable/flag_<iso>.webp` file.
- `GeographyStarsTest`: geography level session → 3 stars (no wrong, under time), 2 (one wrong), 2 (over time, no wrong), 1 (wrong + over time).
- `AchievementEngine` test: completing distinct geography levels advances `EXPLORE_WORLD` (1 → tier 1 unlocked with `item_hand_right_geo_sverigeflagga`), replaying the same level doesn't double-count, and geography does **not** advance `READ_STORY`.
- pytest (`tools/tests/test_pipelines.py`): the 8 geography items exist in the built pack, `achievementRewards` maps every `ach_explore_world_*` id, `validate_pack.py` passes.
- Update existing tests counting levels/subjects/worlds (160 → 190 levels, 3 → 4 worlds) — grep `160`, `SubjectWorlds`.

Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/`, pattern `EnglishChoiceOrderTest.kt`):
- `GeographyFlagChallengeTest`: real repository level order 161 + its task in `MathActivityScreen` with fixed `choiceSeed`; assert prompt text "Vilken flagga har …?", 4 `flag_choice_*` nodes; tap a wrong one → marked wrong, not solved; tap correct → solved/advances; `onRoot().captureToImage()` → `temp/verify/037/flag_challenge.png`.
- `GeographyRewardItemsTest` (pattern `BundledCatalogRenderTest.kt` / `ItemCatalogScreenshotTest.kt`): render each of the 8 geography items on every avatar from the real bundled pack; assert each renders (node exists, non-blank) and capture `temp/verify/037/geo_item_<id>.png`.
- `GeographyWorldMapTest`: `CampaignMapScreen` shows "Världsresan" world, first level unlocked and clickable, levels 2+ locked; capture `temp/verify/037/geo_map.png`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.GeographyFlagChallengeTest,se.lixo.app.verify.GeographyWorldMapTest,se.lixo.app.verify.GeographyRewardItemsTest`

## Open questions
_None._
