# 011 — Sällsynthet som känns – rättvisa dropp-odds, garanti och tydligare häftighet per nivå

**Type**: feature

## Summary

**Rättelse först:** mitt förra svar (~80 % common) var fel. Det gissade sällsynthet från färgnamnen i id:t i stället för fältet `rarity`. Den riktiga katalogen (`GeneratedWardrobeCatalog.kt`, 576 genererade + 6 handgjorda) är redan delad **40/25/17/12/6 %** (`tools/item_pipeline/generate_wardrobe_items.py:59` `RARITY_SPLIT`):

| Sällsynthet | I katalogen | Andel | Chans per kista idag |
|---|---|---|---|
| COMMON | 225 | 39 % | **65 %** |
| UNCOMMON | 144 | 25 % | 25 % |
| RARE | 99 | 17 % | 8,3 % (≈ 1 per 12 kistor) |
| EPIC | 72 | 12,5 % | 1,6 % (≈ 1 per 63) |
| LEGENDARY | 36 | 6 % | **0,14 % (≈ 1 per 692)** |

**Själva problemet är draget, inte katalogen.** `ProgressionAndGiftEngine.rollWeighted` (`ProgressionAndGiftEngine.kt:427`) viktar *varje föremål* med `Rarity.giftDropWeight` (50/30/15/4/1, `Models.kt:56-60`). Antal × vikt gör common ännu vanligare och legendary i praktiken omöjligt. En hel genomspelning ger bara några tiotal kistor. Dessutom låser en dubblett ingenting, och det blir allt vanligare ju mer man äger.

**Förslag:**
1. Behåll katalogens 40/25/17/12/6. Det ändrar inga id:n. id:n används av nivåbelöningar (002), prestationer (004) och sparade upplåsningar (008).
2. Dra **sällsynthet först, sedan föremål**, med målodds per kista **40 / 28 / 18 / 10 / 4 %**.
3. **Garanti ("pity")**: minst RARE var 4:e kista, minst EPIC var 10:e och LEGENDARY senast var 20:e.
4. Kistor väljer föremål du **inte redan äger**. Presenter behåller "dubblett → ingenting" enligt `project.md`.
5. Gör varje nivå **synligt häftigare**: renare common, glöd på epic, glöd + gnistor + särskilda färger + större modell på legendary, och en kista som skimrar i sällsynthetsfärgen innan den öppnas.
6. Visa samlade antal per sällsynthet i garderoben (t.ex. "Legendariska 2/36"), så att en sällsynt sak syns som en bedrift.

Väntat utfall per 25 kistor: ~10 C, 7 U, 4–5 R, 2–3 E, 1 L. Legendary blir alltså en händelse ungefär var 25:e kista, och aldrig mer än 20 kistor bort.

## 1. Dra sällsynthet först, sedan föremål

**Change**
- `Models.kt:49-61`: byt betydelse på `Rarity.giftDropWeight` till *procent per drag för sällsyntheten*: COMMON 40, UNCOMMON 28, RARE 18, EPIC 10, LEGENDARY 4. Uppdatera KDoc.
- `ProgressionAndGiftEngine.rollWeighted` (`:427`) blir två steg:
  1. Bland de sällsyntheter som har minst ett föremål i `pool`, välj sällsynthet viktat med `giftDropWeight` (tomma sällsyntheter ger sin vikt proportionellt till de andra).
  2. Välj ett föremål jämnt fördelat (uniformt) inom den sällsyntheten.
- Lägg till parametern `preferUnowned: Boolean`:
  - När den är sann väljs bara oägda föremål i steg 2.
  - Om sällsyntheten är helt ägd provas närmast lägre, sedan högre, sällsynthet med oägda föremål.
  - Äger man allt faller draget tillbaka på dagens dubblettregel.
- `rollChest` (`:398`) och `rollOnboardingItem` (`:416`) använder `preferUnowned = true`. `rollPresentGift` (`:384`) använder `false`, så att `project.md`-regeln "Dubblett -> ingenting händer" gäller för presenter.
- Onboarding fortsätter föredra COMMON/UNCOMMON (`isOnboardingPreferred`, `:105`) och får därmed fördelningen 40:28 inom den poolen.

**Verification**
- commonTest i `LixoEnginesTest.kt`, `rarityRoll_matchesTargetOdds`: hela katalogen, `Random(42)`, 20 000 drag. Varje sällsynthets andel ska ligga inom ±1,5 procentenheter från 40/28/18/10/4.
- `rollChest_prefersUnowned`: med alla COMMON ägda blir resultatet aldrig en dubblett, och fallbacken går till närmaste sällsynthet.
- `rollPresentGift_keepsDuplicateRule`: när poolen bara innehåller ägda föremål blir `isDuplicate = true` och `newlyUnlockedItem = null`.
- Befintliga tester för reward pacing och prestationer (001/004) ska fortsätta passera.

## 2. Garanti (pity) för kistor

**Change**
- `ChildProfile` (`Models.kt:142`): lägg till `chestsSinceRarePlus: Int = 0`, `chestsSinceEpicPlus: Int = 0` och `chestsSinceLegendary: Int = 0`.
- `BalanceConfig`: lägg till `PITY_RARE_PLUS_CHESTS = 4`, `PITY_EPIC_PLUS_CHESTS = 10` och `PITY_LEGENDARY_CHESTS = 20`, med KDoc som förklarar varför.
- `rollChest` tar emot räknarna. Innan steg 1 i draget sätts en golvnivå för sällsyntheten (`minRarity`):
  - LEGENDARY om `chestsSinceLegendary + 1 >= 20`
  - annars EPIC om `chestsSinceEpicPlus + 1 >= 10`
  - annars RARE om `chestsSinceRarePlus + 1 >= 4`
  - Sällsyntheter under golvet får vikt 0.
- Efter draget nollställs räknarna för den sällsynthet man fick och alla lägre. De andra räknas upp. Det sker i flödet för Flitkistan (`ProgressionAndGiftEngine.kt:267-275`), som skriver tillbaka till `profile.copy(...)`.
- Bara Flitkistan räknas. Onboarding, nivåbelöningar, stjärnkurvan och prestationer påverkar inte räknarna.
- Spara räknarna:
  - Lägg till kolumner i `ProfileEntity` (`Entities.kt:7`) med `@ColumnInfo(defaultValue = "0")`.
  - Höj `LixoDatabase` från version 5 till 6 och lägg en `Migration(5, 6)` med tre `ALTER TABLE profiles ADD COLUMN … INTEGER NOT NULL DEFAULT 0` i `LIXO_MIGRATIONS` (`LixoMigrations.kt`). Destruktiv fallback används inte.
  - Uppdatera mappningen i `ProfileMappers.kt`. Det exporterade schemat `composeApp/schemas/.../6.json` genereras vid bygget.

**Verification**
- commonTest `chestPity_guaranteesRareWithin4`: med en COMMON-only seed eller fejkad `Random` som alltid ger lägsta värdet blir kista 4 minst RARE.
- `chestPity_guaranteesLegendaryWithin20`: 20 kistor i följd innehåller minst en LEGENDARY.
- `chestPity_countersResetCorrectly`: efter en EPIC är `chestsSinceEpicPlus` och `chestsSinceRarePlus` 0, och `chestsSinceLegendary` har räknats upp.
- `ProfileStoreTest`: mappningens round-trip täcker de nya fälten.
- En migrationstest om sådana redan finns i repot (`grep -rn Migration composeApp/src/commonTest`). Annars räcker att bygget och `ProfilePersistenceTest` går igenom på en befintlig v5-databas.

## 3. Synligt häftigare per sällsynthet (generatorn, id:n oförändrade)

**Change** i `tools/item_pipeline/generate_wardrobe_items.py`:
- **Viktigt:** id, namn och sällsynthet beror på huvud-rng:ns ordning (blandningen av sällsynthetsordningen på `:298` och valet av färg efter `_rarity_pattern_ornament` på `:325-327`).
  - Behåll det befintliga anropet `_rarity_pattern_ornament(rarity, rng)` som det är, så att rng-strömmen inte ändras.
  - Bestäm sedan det *nya* mönstret och ornamentet med en separat `visual_rng = random.Random(f"{seed}-visual-{item_id}")` i en ny `_rarity_visuals(rarity, visual_rng)`.
- Ny visuell trappa:
  - **COMMON**: inget mönster och inget ornament (ren grundform, ingen TRIM).
  - **UNCOMMON**: TRIM, plus ränder eller prickar i hälften av fallen.
  - **RARE**: valfritt mönster + GEM.
  - **EPIC**: valfritt mönster + GLOW, med `secondaryColorHex` som guld-/silveraccent (`0xFFF4D03F` eller `0xFFD5DBDB`) så att epic ser "metalliskt" ut. Färgnamnet och id:t behålls.
  - **LEGENDARY**: oförändrade legendariska färger (regnbåge, kosmisk, gyllene, galax), mönster, SPARKLE och skala 1,15.
- Rendering (`ui/components/avatar3d/Avatar3DController.kt`, `drawOrnamentOverlay` `:406` och `drawGlowHalo` `:393`): LEGENDARY ritar *både* glorian (som GLOW) och gnistorna (som SPARKLE), oavsett vilket ornament föremålet har.
  - `WardrobeItemPreview` (`:196`) startar då animationsklockan även för `rarity == LEGENDARY`.
- Kör generatorn så att `GeneratedWardrobeCatalog.kt` skrivs om, och uppdatera `tools/tests/test_pipelines.py`.

**Verification**
- `tools/tests/test_pipelines.py`:
  - `test_ids_names_rarities_unchanged`: generera och jämför mängden `(id, nameSv, rarity, unlockStarRequirement, unlockAchievementId)` med en snapshot från nuvarande `GeneratedWardrobeCatalog.kt`. Spara snapshoten som `tools/tests/fixtures/catalog_ids_v1.json` innan generatorn ändras. Mängderna måste vara identiska.
  - `test_visual_ladder`: alla COMMON har NONE/NONE, alla EPIC har GLOW och alla LEGENDARY har SPARKLE.
- `python3 tools/item_pipeline/generate_wardrobe_items.py --dry-run` visar oförändrade antal per sällsynthet.
- Kör `se.lixo.app.verify.ItemCatalogScreenshotTest` (009) igen och jämför `temp/verify009/` med det nya resultatet. Common ska se renare ut, epic glöda och legendary både glöda och gnistra.

## 4. Kistan förvarnar om sällsyntheten

**Change**
- `RewardChest.kt`: i läget före öppning (stängd kista, "tryck för att öppna") ritar kistan en pulserande ram och glöd i `LixoColors.rarityColor(item.rarity)`. Pulsen blir starkare uppåt i nivåerna:
  - COMMON: ingen glöd
  - UNCOMMON: svag
  - RARE: mjuk puls
  - EPIC: tydlig puls + små gnistor
  - LEGENDARY: guldglöd, kistan skakar lätt och öppningsanimationen tar ~30 % längre tid.
- Befintliga effekter efter öppning (`LightBurst`, `confettiCountFor` 40→140, `RarityItemTile`) behålls oförändrade.
- Lägg animationsspecarna i en ren funktion `chestTeaserSpecFor(rarity)`, på samma sätt som `lightBurstSpecFor`, så att den går att enhetstesta.

**Verification**
- commonTest `chestTeaserSpec_escalatesWithRarity`: intensiteten ökar strikt från COMMON till LEGENDARY.
- Instrumenterat test `se.lixo.app.verify.RarityRevealTest`: rendera `RewardChest` i stängt läge och efter öppning för en EPIC och en LEGENDARY ur den riktiga katalogen.
  - Stäng av klockans automatiska framdrift (`compose.mainClock.autoAdvance = false`) och flytta den framåt med `advanceTimeBy`, som i 009-testet.
  - Ta skärmbilder `rarity_<r>_closed.png` och `rarity_<r>_opened.png`, och kontrollera att sällsynthetsetiketten finns (`onNodeWithText`).

## 5. Samlade per sällsynthet i garderoben

**Change**
- `WardrobeScreen.kt`: en rad överst med fem små brickor i sällsynthetsfärg, t.ex. "Vanliga 12/225 · Ovanliga 5/144 · Sällsynta 3/96 · Episka 1/69 · Legendariska 0/25".
  - Siffrorna räknas från `unlockedItemIds` mot hela katalogen.
  - Namnen hämtas via `LixoStrings.rarityName`.
- Strängarna läggs i `LixoStrings.kt` om de saknas.

**Verification**
- Instrumenterat test `se.lixo.app.verify.WardrobeRarityCountTest`: lås upp 2 COMMON och 1 LEGENDARY i ett riktigt `LixoRepository` och rendera `WardrobeScreen`. Kontrollera att texterna "2/" och "1/" finns i rätt bricka och ta en skärmbild.

## 6. Dokumentation och uppgiftskö

**Change**
- Projektregeln säger att en funktionsförfrågan ska bli en plan plus en uppgift. Skriv därför den här planen som `doc/plans/011-rarity-odds-and-feel.md` och lägg till en rad under `## Pending` i `FEATURES.md`:
  `- [ ] 011 — Rarity: rarity-first drop odds 40/28/18/10/4, chest pity, visual ladder, chest teaser, collection counts (feature) → [plan](doc/plans/011-rarity-odds-and-feel.md)`
- Uppdatera KDoc i `ProgressionAndGiftEngine.kt:56-80` och dokumentationssträngen i generatorn (`generate_wardrobe_items.py:9-18`).

**Verification**
- Kör `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.RarityRevealTest,se.lixo.app.verify.WardrobeRarityCountTest,se.lixo.app.verify.ItemCatalogScreenshotTest,se.lixo.app.verify.ProfilePersistenceTest`
- Kör även `./gradlew :composeApp:testAndroidHostTest` och `python3 -m pytest tools/tests`.

## Order of work

1. Ta snapshoten av katalogens id:n (avsnitt 3, fixturen) **innan** generatorn ändras.
2. Avsnitt 1: drag med sällsynthet först och `preferUnowned`, med enhetstester.
3. Avsnitt 2: pity-räknare, DB-migration 5→6 och mappning, med tester.
4. Avsnitt 3: visuell trappa i generatorn (separat rng), rendering av legendary och omgenererad katalog. Testet att id:n är oförändrade måste passera.
5. Avsnitt 4: kistans förvarning.
6. Avsnitt 5: räknare i garderoben.
7. Avsnitt 6: plan och uppgift i `FEATURES.md`, därefter alla tester och skärmbilder på `emulator-5554`.

**Öppna frågor**
- Är målodds 40/28/18/10/4 och garantin 4/10/20 rätt nivå? Alternativet är ett snålare 45/28/16/8/3 med legendary-garanti vid 30.
- Ska presenter också få `preferUnowned`? `project.md` säger i dag "Dubblett → ingenting", så planen låter presenter vara som de är.
