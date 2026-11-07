# 005 — Visa vad AI hörde för gula ord ("Tryck och jämför")

**Type**: feature

> Efter godkännande: denna fil kopieras till `doc/plans/005-heard-word-compare-popup.md` och en rad läggs i `FEATURES.md` under `## Pending`:
> `- [ ] 005 — Gula ord: visa vad AI hörde vs ordet i hjälp-popupen (feature) → [plan](doc/plans/005-heard-word-compare-popup.md)`
> Ingen implementation nu (projektregel i CLAUDE.md).

## Goal
Ord blir ibland gula fast barnet läste rätt, eftersom STT hörde fel. När man trycker på ett gult ord ska hjälp-popupen visa **ordet** och **vad AI hörde** bredvid varandra. Det ska gå att lyssna på båda, och skillnaden ska markeras per bokstav. Under läsningen syns bara en liten markering på gula ord.

## Context
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/engine/ForcedAlignmentEngine.kt`
  - `StoryWordToken` (rad 10) har `isCompleted` och `needsPractice`, men inget fält för vad som hördes.
  - `processRecognizedTokens` (rad ~128): en talad token som inte matchar markören, ett gult ord eller lookahead **kastas bort**. Ord som hoppas över i lookahead (rad ~201) flaggas gula utan att något sparas om vad som sades.
  - `onTick` (rad ~245): tvekan flaggar markörordet gult, men inget av det som hördes sparas.
- `composeApp/src/commonMain/kotlin/se/lixo/app/platform/PlatformContracts.kt:31`: `RecognizedSpeechToken.rawWord` / `normalizedWord`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/reading/ReadingActivityScreen.kt`
  - Ordchips: rad ~398–436 (`isYellowUnread` / `isYellowRead`).
  - Hjälp-popup: rad ~707–770 (`helpPopupWordIndex`, `popupEntry`, rubrik, stavelser, förklaring, `TtsIconButton`).
  - "Ord att öva" i resultatvyn (rad ~666) öppnar samma popup.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoComponents.kt:222`: `TtsIconButton(textToSpeak, ttsEngine, languageIso, …)`.
- `LixoIcons.Mic` finns redan (`LixoIcons.kt:49`).
- Strängar: `LixoStrings.kt` rad ~58–80 (`READING_*`).
- Tester: `composeApp/src/commonTest/kotlin/se/lixo/app/LixoEnginesTest.kt` (`forcedAlignment_*`, rad 63–227, 1356+).

## Steps

### 1. Domän: spara det AI hörde
I `ForcedAlignmentEngine.kt`:
1. `StoryWordToken`: lägg till `val heardText: String? = null`. Det är råtexten STT hörde när ordet flaggades, eller vid senaste missade försöket.
2. `ReadingAlignmentSessionState`: lägg till `val unmatchedSinceAdvance: List<String> = emptyList()`. Det är `rawWord` för talade tokens som inte matchat något sedan markören senast flyttades. Max 4 ord, behåll de senaste.
3. I `processRecognizedTokens`:
   - Lokal `var pending = state.unmatchedSinceAdvance.toMutableList()`.
   - Matchar en token **inget** (markör, gult ord eller lookahead): lägg `spoken.rawWord.trim()` i `pending` (cap 4). Är markörordet redan `needsPractice`, sätt dess `heardText = pending.joinToString(" ")`. Då uppdateras ett ord som flaggats av tvekan med senaste försöket.
   - **Lookahead-skip** (`matchedIndex > cursor`): fördela `pending` över de överhoppade orden `cursor until matchedIndex`, i ordning. Ord `i` får `pending.getOrNull(i - cursor)`. Finns fler pending än överhoppade ord läggs resten ihop med mellanslag på det sista överhoppade ordet. Saknas pending blir `heardText` null.
   - Efter **varje** match (markör, gult ord, lookahead): töm `pending`.
   - Returnera `unmatchedSinceAdvance = pending`.
   - Ett gult ord som sedan läses rätt behåller `heardText`, så popupen kan visa "Först hörde jag …".
4. I `onTick`: när markörordet flaggas `needsPractice` och `heardText == null`, sätt `heardText = state.unmatchedSinceAdvance.joinToString(" ").ifBlank { null }`.
5. Rör inte poängberäkning, `struggledWordsNormalized` eller manuellt läge. Manuellt läge får aldrig `heardText`.

### 2. Ren hjälpfunktion för bokstavsdiff
Ny fil `composeApp/src/commonMain/kotlin/se/lixo/app/domain/engine/SpellingDiff.kt`:
```kotlin
data class DiffChar(val char: Char, val matched: Boolean)
/** LCS-based: marks each char of [target] as matched if it is part of the LCS with [heard] (case-insensitive). */
fun spellingDiff(target: String, heard: String): List<DiffChar>
```
- Jämför `target`, alltså `displayText` utan skiljetecken i kanterna, mot `heard` med lowercase. Returnera ett element per tecken i `target`.
- Tom `heard` ger alla tecken `matched = false`.

### 3. Strängar (`LixoStrings.kt`, nära `READING_*`)
```kotlin
const val READING_HEARD_WORD_LABEL = "Ordet:"
const val READING_HEARD_AI_LABEL = "Jag hörde:"
const val READING_HEARD_AI_FIRST_LABEL = "Först hörde jag:"
const val READING_HEARD_NOTHING = "Jag hörde inget tydligt"
const val READING_HEARD_TRY_AGAIN_HINT = "Säg ordet igen – jag lyssnar!"
```

### 4. UI: markering på gula chips (`ReadingActivityScreen.kt` ~rad 415–436)
- För `isYellowUnread` och `isYellowRead` med `token.heardText != null`: lägg en liten `LixoIcon(LixoIcons.Mic, tint = LixoColors.GoldLip, size = 12.dp)` uppe till höger i chippet, via `Box` + `Alignment.TopEnd` och en liten offset. Flowrow-layouten får inte ändras: ingen ny bredd, ikonen överlappar padding.

### 5. UI: jämförelseblock i hjälp-popupen (~rad 707–770)
- Spara det tryckta `StoryWordToken` (`popupWord`) utöver `popupEntry`.
- Om `popupWord.needsPractice && sessionState.mode == ReadingMode.AI_LISTENING`: rendera ett block **mellan rubrikraden och stavelserna**:
  ```
  Ordet:      h u s e t   🔊
  Jag hörde:  huse        🔊
  ```
  - Rad 1: `READING_HEARD_WORD_LABEL` + `popupWord.displayText`, ritat med `spellingDiff`. Matchade tecken i `LixoColors.TextPrimary`, omatchade i `LixoColors.GoldLip` + `FontWeight.ExtraBold`, via `buildAnnotatedString`.
  - Rad 2: etiketten är `READING_HEARD_AI_FIRST_LABEL` om `popupWord.isCompleted`, annars `READING_HEARD_AI_LABEL`. Därefter `heardText` i kursiv `TextSecondary`, eller `READING_HEARD_NOTHING` om null.
  - 🔊-knappar (`TtsIconButton`) bara om `showSwedishTtsButtons`. Ordet läses som `displayText`, det hörda som `heardText`, och ingen knapp visas om null. `languageIso` = `"en-US"` för `Subject.ENGLISH`, annars `"sv-SE"`, samma logik som i `startListening` (~rad 158).
  - Om `!popupWord.isCompleted && !sessionState.isCompleted`: visa `READING_HEARD_TRY_AGAIN_HINT` med `LixoIcons.Mic` under blocket. STT lyssnar redan medan popupen är öppen, och gula ord kan läsas när som helst (yellow-match-passet).
- **Auto-feedback:** när popupens ord går från `!isCompleted` till `isCompleted` medan popupen är öppen (`LaunchedEffect(popupWord?.isCompleted)`), visa en grön `LixoIcons.Check` i ca 800 ms och stäng sedan popupen (`helpPopupWordIndex = null`).
- Popupen för gröna ord och ord som inte är gula ändras inte.

### 6. Debug-simulator
- I debugkontrollerna (~rad 490–570), lägg till en knapp "Säg fel ord" (`READING_SIMULATE_WRONG_WORD_BTN = "Säg fel ord"`). Den skickar `RecognizedSpeechToken(rawWord = current.normalizedText.dropLast(1) + "x", …)` till `processRecognizedTokens`, så att flödet kan testas utan riktig mikrofon. Kör sedan "Läs nästa ord" två gånger för att trigga lookahead-skip.

## Tests / verification
Unit-tester i `LixoEnginesTest.kt`:
1. `forcedAlignment_lookaheadSkipStoresHeardTextOnSkippedWord`: story "katten sitter på mattan". Säg "katten", "sitta" (ingen match), "på". Då ska `words[1].needsPractice`, `words[1].heardText == "sitta"` och `!isCompleted`.
2. `forcedAlignment_skipWithoutUnmatchedSpeechLeavesHeardTextNull`: hopp utan omatchat tal ger `heardText == null`.
3. `forcedAlignment_hesitationThenWrongAttemptUpdatesHeardText`: `onTick` flaggar ordet, sedan omatchat "mata" på markören. Då ska `heardText == "mata"`. Läs sedan rätt: ordet ska vara `isCompleted` med `heardText` kvar.
4. `forcedAlignment_unmatchedBufferClearedAfterMatch`: buffert töms efter match och är begränsad till 4.
5. `spellingDiff_marksMissingLetters`: `spellingDiff("huset","huse")` ger enbart sista `t` omatchat. Tom `heard` ger allt omatchat.
6. Befintliga `forcedAlignment_*` och `evaluateSessionScore_*` ska fortsätta passera oförändrade.

Kör: `./gradlew :composeApp:allTests` (eller `:composeApp:testDebugUnitTest`).

I appen (Pixel, debug-kontroller på):
- Starta en läsnivå i AI-läge. Tryck "Säg fel ord", sedan "Läs nästa ord" två gånger. Det överhoppade ordet ska vara gult med mic-markering.
- Tryck på det gula ordet. Popupen ska visa "Ordet:" med markerad skillnad, "Jag hörde: …", 🔊-knappar och tipset "Säg ordet igen".
- Läs ordet med riktig mikrofon. Grön bock ska visas, popupen stänga och chippet bli mjukt gult (read).
- Tryck igen: "Först hörde jag: …" ska visas.
- Gult av tvekan utan tal: "Jag hörde inget tydligt".
- Manuellt läge: inget jämförelseblock.
- Resultatvyn "Ord att öva" öppnar samma popup med jämförelse.

## Open questions
- (inga — val gjorda: senaste omatchade försöket vinner, buffert max 4 ord, ingen "Jag sa rätt"-knapp i denna uppgift)
