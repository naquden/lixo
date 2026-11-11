# 038 — Flag choices bound to typed flag resources; invalid flag questions excluded in the app

- **Type**: bug
- **Goal**: A geography answer option must never be able to render without its flag. Each country
  (and so each question option) is bound at compile time to its own flag drawable resource. A flag
  question that doesn't map to known flags, for example one created wrongly in Cloud Firestore, is
  **excluded by the app itself**. The safeguard lives in the app, not in the cloud data. The
  name-based runtime lookup, the `println` warning and the blank grey fallback box are removed.

## Context

- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/theme/LixoComponents.kt:426-471`:
  `FlagChoiceCard(iso2: String, …)` resolves the image at runtime with
  `Res.allDrawableResources["flag_${iso2.lowercase()}"]` (line 454). It returns null for a missing
  or misspelled name, and the card then `println`s on every recomposition (line 468) and draws an
  empty `LixoColors.Surface` box that a child can't tell apart from the other options.
- **Cloud path, no validation today**:
  `composeApp/src/commonMain/kotlin/se/lixo/app/data/repository/Repositories.kt:1174-1218`.
  `syncCurriculumFromCloud()` fetches remote levels and tasks
  (`cloudGateway.fetchTasksForCourse`, `FirebaseSyncService`) and **overwrites** the seeded task by
  id (`next[task.id] = task.copy(rewardItemId = …)`, line ~1210) without checking anything. A
  Firestore `task_<order>` whose `flagQuestions` contain an unknown, misspelled or duplicate iso2
  code, a missing distractor, or a distractor equal to the correct code replaces the good,
  code-generated geography task.
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/content/GeographyChoices.kt:32-45`:
  `buildProblems` turns each `FlagQuestion` into a `MathProblem` whose `choices` are iso2 strings.
  Only the correct country goes through `GeographyCountries.byIso` (`getValue`, so an unknown code
  **throws** and crashes the screen); distractor codes are never checked.
- `composeApp/src/commonMain/kotlin/se/lixo/app/ui/screens/math/MathActivityScreen.kt:134-137`
  builds the problems, and `:389-405` passes each choice string to `FlagChoiceCard(iso2 = iso, …)`.
  Nothing handles an empty problem list.
- `composeApp/src/commonMain/kotlin/se/lixo/app/domain/content/GeographyCountries.kt:22-28,41,271`:
  `Country` has no reference to its flag; `RANKED` lists all 194 countries; `byIso` = `getValue`.
- `MathProblem` (`domain/model/Models.kt:264`) and `FlagQuestion` (`Models.kt:146`) are
  `@Serializable` (synced and persisted), so a `DrawableResource` must not go into them. It belongs
  in `Country`, which is static code data.
- Compose Multiplatform generates a typed accessor `Res.drawable.flag_<iso>` for every
  `composeResources/drawable/flag_<iso>.webp` (194 files, plan 037). Referencing it makes a missing
  bundled file a **compile error**.
- Existing sync tests with a fake gateway: `composeApp/src/androidHostTest/kotlin/se/lixo/app/LixoEnginesTest.kt:1865`
  and `:2789` (`override suspend fun fetchTasksForCourse(...)`) are the patterns to copy.

## Steps

1. **Typed flag per country** (`GeographyCountries.kt`): add `val flag: DrawableResource` (non-null,
   `org.jetbrains.compose.resources.DrawableResource`) to `Country`. In every `RANKED` entry, pass
   that country's typed accessor, e.g.
   `Country("se", "Sverige", FlagRegion.NORDEN, listOf("blå", "gul"), isNordic = true, flag = Res.drawable.flag_se)`.
   Do all 194 with a script, generating each accessor from the entry's own iso2 literal, then check
   that every `flag_<x>` matches its iso2. Import `lixo.composeapp.generated.resources.*`. Don't
   change the order or any other field (persisted-order rule from plan 037). Add
   `fun find(iso2: String): Country? = BY_ISO[iso2.lowercase()]` and keep `byIso`.
2. **One validity rule** (`GeographyChoices.kt`, pure and in commonMain): add
   `fun isValid(q: FlagQuestion): Boolean`. It requires:
   - `countryIso2` resolves via `find`;
   - `distractorIso2` has exactly 3 entries, each resolving via `find`;
   - the 4 codes are pairwise distinct after lowercasing;
   - `id` is non-blank.

   Also add `fun sanitize(questions: List<FlagQuestion>): List<FlagQuestion> = questions.filter(::isValid)`.
   Normalise codes to lowercase in the kept questions.
3. **Safeguard at the cloud boundary** (`Repositories.syncCurriculumFromCloud`, before the
   `_tasksById.update` merge): for every remote task with `type == TaskType.GEOGRAPHY_FLAGS` (or a
   non-empty `flagQuestions`):
   - replace `flagQuestions` with `GeographyChoices.sanitize(...)`, logging once per sync how many
     questions were dropped and from which task id;
   - if **no** valid question remains, don't overwrite: keep the current local, code-generated task
     for that id. If no local task exists either, skip that remote task, so the level keeps its
     seeded task.

   Non-geography tasks are untouched.
4. **Last-line safeguard where problems are built** (`GeographyChoices.buildProblems`): run
   `sanitize` first, so persisted or cached tasks from older builds are also covered. Use `find`
   instead of `byIso`. Every FLAG `MathProblem.choices` entry is then guaranteed to be a known
   country with a flag.
5. **Empty result can't break the screen** (`MathActivityScreen`): if a geography task yields 0
   problems after sanitising, don't render the question UI or crash. Close back to the map with
   `onBack`/the existing exit callback and record nothing (no stars, no completion). Steps 3 and 4
   should make this unreachable in practice; this is a guard.
6. **Card renders only a resolved flag** (`LixoComponents.kt` `FlagChoiceCard`): change the
   parameter to `country: Country` (or `flag: DrawableResource` + `iso2` for the tag). Always
   render `Image(painterResource(country.flag), …)`. Delete the `allDrawableResources` lookup and
   import, the `println`, and the grey fallback `Box`. Keep the test tag
   `FLAG_CHOICE_TAG_PREFIX + iso2`, the generic a11y description and the colour states unchanged.
   `MathActivityScreen.kt:397` passes `GeographyCountries.byIso(iso)`, which is safe after step 4.
7. `grep -rn "allDrawableResources" composeApp/src` must return nothing.

## Tests / verification

- commonTest `composeApp/src/commonTest/kotlin/se/lixo/app/geography/GeographyChoicesTest.kt`:
  - `isValid` is false for each of: unknown correct code (`"xx"`); unknown distractor; only 2
    distractors; 4 distractors; duplicate distractor; distractor equal to the correct code
    (case-insensitive, `"SE"` vs `"se"`); blank id. It is true for a well-formed question.
  - `sanitize` keeps the valid questions of a mixed list in order and drops only the bad ones.
  - `buildProblems` on a mixed list returns problems only for the valid questions, without
    throwing.
  - every question of every `GeographyCurriculum` level is valid (the seed itself passes the rule).
- commonTest `GeographyCountriesTest`: all 194 `flag` resources are distinct, which catches
  iso2/accessor copy-paste mismatches.
- androidHostTest `LixoEnginesTest` (pattern at `:1865` / `:2789`), new test
  `syncCurriculumFromCloud_excludesInvalidFlagQuestions`. The fake gateway returns a newer
  `course_geo_1`, a `level_161`, and a `task_161` of type `GEOGRAPHY_FLAGS` with 8 questions,
  3 of them invalid (unknown code, duplicate distractor, only 2 distractors). After sync,
  `tasksById["task_161"].flagQuestions` holds exactly the 5 valid ones.
- Same file, test `syncCurriculumFromCloud_allInvalidFlagTask_keepsSeededTask`: every remote
  question is invalid, so after sync `task_161` still equals the seeded task (8 questions, all
  valid).
- Existing `GeographyFlagAssetsTest` (androidHostTest) stays.
- Compile `./gradlew :androidApp:assembleDebug`: a missing bundled `flag_<iso>.webp` is now a
  compile error. Host: `./gradlew :composeApp:testAndroidHostTest`.
- Instrumented (`androidApp/src/androidTest/kotlin/se/lixo/app/verify/`):
  - extend `GeographyFlagChallengeTest` to answer all 8 questions of level 161 correctly, advance
    each time, and capture `temp/verify/038/flag_q<n>.png`, so every rendered option is visibly a
    flag;
  - new `GeographyInvalidQuestionTest`: render `MathActivityScreen` with a GEOGRAPHY_FLAGS task of 3
    questions, the middle one invalid (unknown distractor `"xx"`). Assert the progress covers 2
    questions, the invalid prompt never appears, and every `flag_choice_*` tag on screen is a known
    country. Then a task where every question is invalid: the screen calls its exit callback and
    shows no question;
  - re-run `GeographyWorldMapTest`.
- Run: `ANDROID_SERIAL=R5CXC0681EM ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=se.lixo.app.verify.GeographyFlagChallengeTest,se.lixo.app.verify.GeographyInvalidQuestionTest,se.lixo.app.verify.GeographyWorldMapTest`.
  Use `emulator-5554` while the S25 isn't attached, and never run without `ANDROID_SERIAL`.

## Open questions

- All-invalid remote geography task with **no** local task for that id. Default: skip it, so the
  level keeps or doesn't get a playable task. Alternative: hide that level on the map. The default
  is simpler because geography levels are always seeded in code.
