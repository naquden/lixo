# 036 — Speech recognition misses words the child read correctly (false "Ord att öva")

- **Type**: bug
- **Goal**: The child reads words correctly, but the AI listener doesn't recognize them. They turn
  yellow and land in the parent's "Ord att öva på" list (e.g. `lixo` 14×, `bo`, `i`, `en`, `ett`,
  `den`, `det`). Make recognition tolerant so that correctly read words are credited, using four
  changes (options A–D from the analysis):
  - **B**: short words the recognizer swallows don't get flagged.
  - **C**: per-word aliases for names and invented words (e.g. `lixo`).
  - **A**: use Android's N-best alternatives and real confidence scores.
  - **D**: give the story's words (plus the upcoming phrase from the reading cursor) to the
    recognizer as its expected vocabulary on every start (`EXTRA_BIASING_STRINGS`, API 33+), and
    prefer the on-device recognizer.

## Context

Recognition pipeline:

- `composeApp/src/androidMain/kotlin/se/lixo/app/platform/PlatformAndroid.kt:151`
  `AndroidSpeechRecognitionEngine`
  - `startListening(languageIso, expectedWords)` (`:224`) **ignores `expectedWords`**.
  - `beginRecognition` (`:243-259`) always uses `SpeechRecognizer.createSpeechRecognizer(ctx)`
    with `LANGUAGE_MODEL_FREE_FORM`, `EXTRA_PARTIAL_RESULTS`. No biasing, no on-device recognizer,
    no `EXTRA_MAX_RESULTS`.
  - `handleSpeechBundle` (`:280-302`) takes only `matches.firstOrNull()` (top transcript), ignores
    `SpeechRecognizer.CONFIDENCE_SCORES`, and hardcodes `confidence = 0.88f`. Partial results are
    cumulative; it forwards `allTokens.drop(emittedInUtterance)`.
  - `onError` (`:188-204`): errors 12/13 (language not supported/unavailable) set
    `ERROR_ENGINE_UNAVAILABLE`.
- `composeApp/src/commonMain/kotlin/se/lixo/app/platform/PlatformContracts.kt:38`
  `RecognizedSpeechToken(rawWord, normalizedWord, confidence, timestampMillis)`.
- `composeApp/src/iosMain/kotlin/se/lixo/app/platform/PlatformIos.kt:51` is a stub (simulated
  only). It only needs to compile with the new token field.

Alignment (`composeApp/src/commonMain/kotlin/se/lixo/app/domain/engine/ForcedAlignmentEngine.kt`):

- `startSession(storyText, mode, isTimedChallengeRequested)` (`:83`) → `tokenizeStory` (`:107`)
  builds `StoryWordToken(index, displayText, normalizedText, …)`.
- `processRecognizedTokens` (`:127-241`) runs three passes per spoken token: cursor match, earlier
  yellow word, then lookahead (`STT_LOOKAHEAD_WINDOW_WORDS = 3`). On a lookahead match at
  `matchedIndex > cursor`, **every skipped word becomes `needsPractice = true` and is added to
  `struggledWordsNormalized`** (`:197-213`). This is the root cause for `i`, `en`, `ett`, `den`,
  `det`: Android STT often drops short function words, so the child "skipped" them.
- `isMatch` (`:249-253`) uses `computeSwedishPhoneticSimilarity` (`:407-418`, folded Levenshtein)
  against `STT_WORD_MATCH_CONFIDENCE_THRESHOLD = 0.65` (`BalanceConfig.kt:68`). Non-Swedish names
  like `lixo` come back as e.g. `lisso`/`licksåu`/`lixå`, and short words can't absorb even one
  wrong letter (`i` vs `e` = 0.0).
- `evaluateSessionScore` (`:325-389`) → `struggledWordsToLog` = `struggledWordsNormalized` +
  unread yellow. Logged in `composeApp/src/commonMain/kotlin/se/lixo/app/ui/LixoApp.kt:317-318`
  → `Repositories.logStruggledWord` (`Repositories.kt:585`).
- Callers of `startSession` / `processRecognizedTokens`:
  - `ui/screens/reading/ReadingActivityScreen.kt:112, 144, 150, 360, 499, 531, 560, 600`
  - `ui/screens/onboarding/ProfileSelectAndTutorialScreen.kt:294, 310, 379, 385, 410, 538`
  - Tutorial text `BalanceConfig.TUTORIAL_STORY_SV` (`BalanceConfig.kt:10`) contains "Lixo".
- Existing alignment unit tests: `composeApp/src/androidHostTest/kotlin/se/lixo/app/LixoEnginesTest.kt:70-370`.
- Existing instrumented reading test with an injectable fake STT:
  `androidApp/src/androidTest/kotlin/se/lixo/app/verify/HeardWordPopupTest.kt` (`SilentSttEngine`).
- minSdk 26, targetSdk 35 (`gradle/libs.versions.toml:4-5`). The on-device recognizer API is 31+;
  `EXTRA_BIASING_STRINGS`, `checkRecognitionSupport` and `triggerModelDownload` are 33+.

## Steps

Do the engine changes (B, C, A) first. They're pure common code and unit-testable. D is
Android-only.

### B — Don't flag short words the recognizer swallowed

1. `BalanceConfig.kt`: add
   - `const val STT_SHORT_WORD_MAX_LETTERS = 3`
   - `const val STT_SHORT_WORD_LENIENT_THRESHOLD = 0.5f`
2. In `processRecognizedTokens`, in the lookahead-skip branch (`:197-213`): for each skipped
   index `i` in `cursor until matchedIndex`, check whether the word is "silently swallowed". That
   means `workingWords[i].normalizedText.length <= STT_SHORT_WORD_MAX_LETTERS`, no pending
   unmatched speech is attributed to that slot (`heard == null`), and the word wasn't already
   `needsPractice`. If so, mark it `isCompleted = true` and do **not** set `needsPractice` or add
   it to `struggled`. Otherwise keep today's behaviour (yellow + struggled + heardText).
   - Rationale: if the recognizer heard *nothing* for that slot and the next word matched, the
     child almost certainly said it. If it heard something wrong, keep flagging.
3. In `isMatch`, when `targetNorm.length <= STT_SHORT_WORD_MAX_LETTERS`, also accept
   `similarity >= STT_SHORT_WORD_LENIENT_THRESHOLD` **only for the cursor pass** (pass 1), never
   for lookahead or yellow passes, to avoid mis-crediting repeated short words. Do this by adding a
   `lenientShort: Boolean` parameter, true only at `:145-146`.
4. Leave hesitation flagging (`onTick`, `:260-300`) unchanged. A real pause on a short word should
   still flag it.

### C — Aliases for names and invented words

5. `BalanceConfig.kt`: add
   `val STT_WORD_ALIASES: Map<String, List<String>> = mapOf("lixo" to listOf("lixå", "lisso", "liksso", "lixxo", "licko", "lix", "lissa", "lixa", "liso", "leckso"))`.
   Keys and values are already `normalizeForAlignment`-ed. Keep this list in one place.
6. `StoryWordToken`: add `val aliases: List<String> = emptyList()`.
7. `ForcedAlignmentEngine.startSession`: add parameter
   `wordAliases: Map<String, List<String>> = BalanceConfig.STT_WORD_ALIASES`. Pass it to
   `tokenizeStory`, which sets `aliases = wordAliases[normalizedText].orEmpty()`.
8. Proper nouns: in `tokenizeStory`, mark a token as a name when `displayText` starts with an
   uppercase letter and is **not** sentence-initial (index 0, or the previous raw token ends with
   `.`, `!` or `?`). Add `val isProperNoun: Boolean = false` to `StoryWordToken`. In matching,
   proper nouns use threshold `STT_PROPER_NOUN_THRESHOLD = 0.5f` (new `BalanceConfig` const) and
   also match if the first folded letter is equal and the length difference is ≤ 2.
9. Replace direct `isMatch(spokenNorm, word.normalizedText, …)` calls with a helper
   `matchesWord(spokenNorm, token: StoryWordToken, confidence, threshold, lenientShort)`. It
   returns true if `isMatch` hits for `token.normalizedText` **or** any alias (`similarity` vs
   alias ≥ threshold), and applies the proper-noun rule from step 8.

### A — Use N-best alternatives and real confidence

10. `RecognizedSpeechToken` (`PlatformContracts.kt:38`): add
    `val alternatives: List<String> = emptyList()` (normalized words at the same position from
    lower-ranked transcripts). The default keeps `emitSimulatedWords`, iOS and the test fakes
    compiling.
11. `PlatformAndroid.kt beginRecognition`: add `putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)`.
12. `handleSpeechBundle`:
    - Read `bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)`. Top-transcript confidence =
      `scores[0]` if present and `>= 0f`, else `0.88f` (partial results usually have no scores).
    - Tokenize every transcript in `matches` the same way. For the token at index `i` of the top
      transcript, `alternatives` = distinct normalized words at index `i` of each other
      transcript that has the same token count (skip transcripts with a different count),
      excluding the top word itself.
    - Keep the `drop(emittedInUtterance)` delta logic unchanged (fixing it is option E, out of
      scope).
13. `ForcedAlignmentEngine.processRecognizedTokens`: in each pass, try `spokenNorm` first, then
    each alternative (normalized with `normalizeForAlignment`) in order. The first that matches
    wins. Use the alternative for matching only. `heardText` / pending buffer keep the top
    `rawWord`.

### D — Story words as the recognizer's expected vocabulary + on-device recognizer

The story text *is* the list of words the child will say. Today it's already passed as
`expectedWords` into `startListening` (`ReadingActivityScreen.kt:150-153`,
`ProfileSelectAndTutorialScreen.kt:385`) and then thrown away. Hand it to the recognizer as
biasing hints on **every** recognizer start, whichever recognizer (on-device or cloud) is used.

14. Expected vocabulary in `AndroidSpeechRecognitionEngine`:
    - Store `expectedWords` from `startListening` in `private var expectedWords: List<String>`.
    - Add to the `SpeechRecognitionEngine` interface (`PlatformContracts.kt:60`):
      `fun updateExpectedPosition(nextWords: List<String>) {}` (default no-op, so iOS and fakes
      compile). Android stores it in `private var upcomingWords: List<String>`.
    - The bias list for each `beginRecognition` (also every auto-restart in `scheduleRestart`) is
      built by `internal fun buildBiasStrings(expectedWords, upcomingWords): List<String>`:
      1. the upcoming phrase as **one string**: the next ≤ 12 words from the cursor joined with
         spaces (e.g. `"hittar en glittrande hatt i skogen"`). Phrases give the recognizer
         context, so short words like `i`/`en` survive;
      2. then each distinct upcoming word;
      3. then the rest of the story's distinct words in story order (incl. aliases from step 19);
      4. capped at `BalanceConfig.STT_BIAS_MAX_STRINGS = 100` entries.
    - Words are passed in their display form, lowercase, without punctuation (`normalizedText`).
      Aliases are added as-is.
    - Both screens call `sttEngine.updateExpectedPosition(sessionState.words.drop(currentWordIndex).filter { !it.isCompleted }.map { it.normalizedText })`
      whenever `currentWordIndex` changes (inside the existing `recognizedTokens.collect`, after
      `processRecognizedTokens`). The new list takes effect at the next recognizer restart, which
      happens after each utterance (`onResults` → `scheduleRestart(100L)`), so the hints follow
      the child through the story.
15. Recognizer choice, extracted into `internal fun chooseRecognizer(ctx): Pair<SpeechRecognizer, Boolean /*onDevice*/>`:
    - API ≥ 33 and `SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)` → use
      `SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)`. Before first use, call
      `checkRecognitionSupport(intent, executor, callback)`.
      - If `currentLanguage` is in `supportedOnDeviceLanguages` → use on-device.
      - If it's in `onlineLanguages` / `pendingOnDeviceLanguages` → call
        `triggerModelDownload(intent)` once (no UI, best effort) and fall back to
        `createSpeechRecognizer` for this session.
      - Cache the result per language for the process lifetime.
    - Otherwise use `createSpeechRecognizer(ctx)` (today's path).
    - If the on-device recognizer reports error 12/13 or `ERROR_CLIENT` on its first start, destroy
      it, set a flag `onDeviceFailed = true`, and restart with `createSpeechRecognizer` instead of
      going to `ERROR_ENGINE_UNAVAILABLE`.
16. Intent, extracted into `internal fun buildRecognizerIntent(ctx, language, biasStrings, sdkInt): Intent`
    so it can be tested:
    - existing extras + `EXTRA_MAX_RESULTS = 5` (step 11);
    - `if (sdkInt >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasStrings))`.
      Set it for **both** the on-device and the cloud recognizer. Recognizers that don't support
      it ignore the extra. Below API 33 Android has no public biasing API, so there the story
      words only help through the alignment engine (B/C/A);
    - `putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)` when the on-device recognizer is used.
17. Add `BalanceConfig.STT_PREFER_ON_DEVICE = true` as a kill-switch. When false, step 15 always
    uses `createSpeechRecognizer`.
18. Log (Logcat tag `LixoStt`, debug only) which recognizer was chosen, whether biasing was
    applied, and the number of bias strings plus the first (phrase) string, for device diagnosis. No audio and no transcripts in release logs (GDPR comment in
    `FirebaseSyncService.kt:54`).

### Wiring

19. Callers keep working with defaults. `ReadingActivityScreen` and
    `ProfileSelectAndTutorialScreen` already pass `expectedWords = sessionState.words.map { it.normalizedText }`.
    Extend that to `words.flatMap { listOf(it.normalizedText) + it.aliases }.distinct()` so aliases
    also bias the recognizer. Also call `updateExpectedPosition(...)` once with the full word list
    right before `startListening` (step 14).
    - Same for the tutorial (`ProfileSelectAndTutorialScreen.kt:385`) and the retry/restart
      `startSession` calls (`ReadingActivityScreen.kt:360`, `ProfileSelectAndTutorialScreen.kt:379, 410`):
      every new session must refresh both lists.
    - iOS (future, not in this task): the same list maps to
      `SFSpeechRecognitionRequest.contextualStrings` once `IosSpeechRecognitionEngine` is real.
20. `grep -rn "RecognizedSpeechToken(" composeApp androidApp` and make sure all constructors still
    compile (defaults should cover it).

## Tests / verification

Unit tests (`composeApp/src/androidHostTest/kotlin/se/lixo/app/LixoEnginesTest.kt`, or a new
`SttToleranceTest.kt` beside it):

- `shortWordSwallowedByStt_isCompletedNotFlagged`: story "Bo bor i en gul stuga", spoken
  `bo bor gul stuga` → `i`, `en` completed, `needsPractice == false`; `struggledWordsToLog` empty.
- `shortWordMisheard_isStillFlagged`: spoken `bo bor katt en gul stuga` (unmatched "katt" before
  the match) → `i` yellow with heardText "katt"; logged.
- `longWordSkipped_isStillFlagged`: story "solen regnar fint", spoken `solen fint` → `regnar`
  yellow + logged (regression of today's behaviour).
- `shortWordLenient_onlyAtCursor`: cursor on `en`, spoken `et` → matches; a later `en` in the
  lookahead window is not credited by `et`.
- `alias_matchesInventedName`: tutorial text, spoken `katten lisso hittar …` → `Lixo` completed,
  not yellow.
- `properNoun_lenientMatch`: story "Där bor Bo.", spoken `där bor bå` → `Bo` completed.
- `sentenceInitialCapital_isNotProperNoun`: `tokenizeStory("Katten sover. Hunden springer.")` →
  `isProperNoun == false` for both.
- `nBestAlternative_matchesWhenTopMisses`: token rawWord `gull`, alternatives `[gul]` → `gul`
  completed at confidence threshold.
- Existing `forcedAlignment_*` tests stay green. Update
  `forcedAlignment_lookaheadSkipFlagsWordYellow…` only if it uses a ≤3-letter skipped word, by
  changing its fixture to a longer word so it keeps testing the long-word path.

Android unit/instrumented for the platform layer:

- New `androidApp/src/androidTest/kotlin/se/lixo/app/verify/SttRecognizerConfigTest.kt`:
  - `buildRecognizerIntent(…, sdkInt = Build.VERSION.SDK_INT)` on R5CXC0681EM (API 35) contains
    `EXTRA_BIASING_STRINGS` equal to the given words and `EXTRA_MAX_RESULTS == 5`.
  - `buildBiasStrings`: for the tutorial story with cursor at "hittar", the first entry is
    `"hittar en glittrande hatt i skogen hatten lyser med varmt guld när"` (12 words), followed by
    distinct upcoming words, then earlier words. The list has no duplicates, has ≤ 100 entries,
    and contains the `lixo` aliases.
  - Biasing on restart: with a fake `SpeechRecognizer` seam (or by capturing the intent via
    `buildRecognizerIntent`), calling `updateExpectedPosition(["i","skogen"])` followed by a
    restart produces an intent whose first bias string is `"i skogen"`.
  - `handleSpeechBundle` parsing: extract the parsing into
    `internal fun parseSpeechBundle(matches: List<String>, scores: FloatArray?, alreadyEmitted: Int): List<RecognizedSpeechToken>`
    and assert alternatives and confidence for a 3-transcript bundle with scores
    `[0.7, 0.2, 0.1]`, and for a partial bundle with no scores (→ 0.88).
  - Log `chooseRecognizer` result (on-device yes/no, languages) to the test output as evidence.
    Assert only that a recognizer is returned, since on-device sv-SE availability depends on the
    device.
- New `androidApp/src/androidTest/kotlin/se/lixo/app/verify/SttToleranceReadingTest.kt`, following
  `HeardWordPopupTest` (real `ReadingActivityScreen`, `SilentSttEngine` fake, `LixoTheme`):
  - inject tokens that skip the short words of the story → finish → assert
    `LixoStrings.READING_PRACTICE_WORDS_TITLE` does **not** exist (`assertDoesNotExist`);
  - inject `lisso` for "Lixo" in the tutorial story via `ProfileSelectAndTutorialScreen`, or a
    level whose text contains "Lixo" → word is not yellow / no practice list;
  - screenshot `onRoot().captureToImage()` → `lixo_screenshots/036_no_false_practice_words.png`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.SttRecognizerConfigTest,se.lixo.app.verify.SttToleranceReadingTest,se.lixo.app.verify.HeardWordPopupTest,se.lixo.app.verify.TutorialUnreadWordsTest`
  (the last two are regression checks on the same screens).
- Manual check (optional, real voice): read the tutorial aloud on the S25 and confirm `LixoStt`
  logs `onDevice=true biasing=true` and that "Lixo", "i", "en" stay white.

## Open questions

- The Samsung S25's default recognizer may be Samsung's rather than Google's, and
  `isOnDeviceRecognitionAvailable` / sv-SE on-device support can differ. If on-device sv-SE isn't
  available, D falls back to the cloud recognizer with biasing extras only. Confirm on the device
  via the `SttRecognizerConfigTest` log.
- Some recognizers silently ignore `EXTRA_BIASING_STRINGS`. If the S25 ignores it, the
  alignment-side fixes (B/C/A) still apply. A stronger option would be a constrained grammar
  recognizer such as Vosk with the story as its grammar, but that's out of scope here.
- The parent's existing struggled-word log already contains false entries (`lixo` 14×, etc.). This
  plan doesn't clear them. Add a one-off cleanup or a "remove word" button for parents (option F)
  as a separate task if wanted.
