package se.lixo.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.lixo.app.data.local.LexiconCache
import se.lixo.app.data.local.createPlatformLexiconCache
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.SyncMergeEngine
import se.lixo.app.domain.content.MathCurriculum
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.model.AvatarAppearance
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.Course
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.DictionaryEntry
import se.lixo.app.domain.model.EnglishGlosaItem
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.MathErrorLog
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.StruggledWordLog
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.PendingEmailLinkStorage

data class AppPrivacySettings(
    val isLocalOnlyMode: Boolean = true,
    val optionalAccountEmail: String? = null,
    val optionalCloudUserId: String? = null,
    val pendingSignInEmail: String? = PendingEmailLinkStorage.getSavedEmail(),
    val emailLinkSent: Boolean = false,
    val pendingIncomingEmailLink: String? = null,
    val appLaunchCount: Int = 1,
    val crashlyticsConsentPromptShown: Boolean = false,
    val crashlyticsConsentGranted: Boolean = false,
    val showCrashlyticsDialogNow: Boolean = false
)

/**
 * Offline-first repository coordinating local Room/state storage, bundled curriculum seed data,
 * and optional cloud sync via [CloudSyncGateway].
 */
class LixoRepository(
    private val cloudGateway: CloudSyncGateway = FirebaseSyncService(),
    private val lexiconCache: LexiconCache? = createPlatformLexiconCache()
) {
    private var lexiconVersion: Int? = null

    private val _profiles = MutableStateFlow<List<ChildProfile>>(emptyList())
    val profiles: StateFlow<List<ChildProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses.asStateFlow()

    private val _levels = MutableStateFlow<List<CampaignLevel>>(emptyList())
    val levels: StateFlow<List<CampaignLevel>> = _levels.asStateFlow()

    private val _tasksById = MutableStateFlow<Map<String, HomeworkTask>>(emptyMap())
    val tasksById: StateFlow<Map<String, HomeworkTask>> = _tasksById.asStateFlow()

    private val _wardrobeCatalog = MutableStateFlow<List<WardrobeItem>>(emptyList())
    val wardrobeCatalog: StateFlow<List<WardrobeItem>> = _wardrobeCatalog.asStateFlow()

    /**
     * Universal Wardrobe (`project.md` §2):
     * "Alla upplåsta föremål ligger i en gemensam pott och kan användas av vilken avatar som helst."
     * Locked items remain hidden (mysterium).
     */
    private val _unlockedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val unlockedItemIds: StateFlow<Set<String>> = _unlockedItemIds.asStateFlow()

    private val _levelProgressByProfile = MutableStateFlow<Map<String, Map<String, LevelProgress>>>(emptyMap())
    val levelProgressByProfile: StateFlow<Map<String, Map<String, LevelProgress>>> = _levelProgressByProfile.asStateFlow()

    private val _dailyActivityByProfile = MutableStateFlow<Map<String, Map<String, DailyActivityRecord>>>(emptyMap())
    val dailyActivityByProfile: StateFlow<Map<String, Map<String, DailyActivityRecord>>> = _dailyActivityByProfile.asStateFlow()

    private val _struggledWordsByProfile = MutableStateFlow<Map<String, List<StruggledWordLog>>>(emptyMap())
    val struggledWordsByProfile: StateFlow<Map<String, List<StruggledWordLog>>> = _struggledWordsByProfile.asStateFlow()

    private val _mathErrorsByProfile = MutableStateFlow<Map<String, List<MathErrorLog>>>(emptyMap())
    val mathErrorsByProfile: StateFlow<Map<String, List<MathErrorLog>>> = _mathErrorsByProfile.asStateFlow()

    private val _dictionary = MutableStateFlow<Map<String, DictionaryEntry>>(emptyMap())
    val dictionary: StateFlow<Map<String, DictionaryEntry>> = _dictionary.asStateFlow()

    private val _achievements = MutableStateFlow<List<Achievement>>(emptyList())
    val achievements: StateFlow<List<Achievement>> = _achievements.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendProfile>>(emptyList())
    val friends: StateFlow<List<FriendProfile>> = _friends.asStateFlow()

    private val _privacySettings = MutableStateFlow(AppPrivacySettings())
    val privacySettings: StateFlow<AppPrivacySettings> = _privacySettings.asStateFlow()

    init {
        seedInitialData()
    }

    private fun seedInitialData() {
        val now = Clock.System.now().toEpochMilliseconds()

        // Default child profile starting in Zero-State ("Ingenting är upplåst första gången;
        // spelaren börjar med en ren bas-avatar utan extra tillbehör" - project.md §2)
        val defaultChild = ChildProfile(
            id = "profile_child_1",
            displayName = "Saga",
            publicUsername = "saga_stjarna",
            ageYears = 8,
            tutorialCompleted = false,
            tutorialSkippableByParent = false,
            appearance = AvatarAppearance(
                avatarType = AvatarType.CAT,
                primaryTintHex = 0xFFF5A623,
                equippedItems = emptyMap() // Zero-state: nothing equipped at start
            ),
            unlockedAvatars = setOf(AvatarType.CAT),
            currentLevelOrder = 1,
            totalStars = 0,
            currentStreakDays = 0,
            bestStreakDays = 0,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        _profiles.value = listOf(defaultChild)
        _activeProfileId.value = defaultChild.id

        // Seed Courses (baseline version 1 so Firestore version 2+ triggers delta download)
        val seedTimestamp = 1730000000000L
        _courses.value = listOf(
            Course("course_sv_1", "Svenska Sagor: Viskande Skogen", Subject.SWEDISH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_sv_2", "Svenska Sagor: Kristallbergen", Subject.SWEDISH, minGrade = 2, version = 1, updatedAt = seedTimestamp),
            Course("course_sv_3", "Svenska Sagor: Stjärnborgens Krönikor", Subject.SWEDISH, minGrade = 3, version = 1, updatedAt = seedTimestamp),
            Course("course_math_1", "Matteäventyret: Plus, Minus & Tiokompisar", Subject.MATH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_math_2", "Klocktornet & Guldbörsen: Tiotal, Tid och Pengar", Subject.MATH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_math_3", "Sifferdalens Kluringar: Hundratal, Gåtor & Klockmästaren", Subject.MATH, minGrade = 2, version = 1, updatedAt = seedTimestamp),
            Course("course_en_1", "Engelska Glosresan", Subject.ENGLISH, minGrade = 2, version = 1, updatedAt = seedTimestamp)
        )

        // Seed 108 Wardrobe Items across all 9 slots and 5 rarities
        val generatedItems = buildSeedWardrobeCatalog()
        _wardrobeCatalog.value = generatedItems

        // Seed 160 Campaign Levels (40 Swedish, 100 Math, 20 English) + Tasks
        val (generatedLevels, generatedTasks) = buildSeedCampaignLevelsAndTasks(generatedItems)
        _levels.value = generatedLevels
        _tasksById.value = generatedTasks

        // Seed Epic & Legendary Achievements
        _achievements.value = buildSeedAchievements()

        // Seed 1 sample friend in gallery
        _friends.value = listOf(
            FriendProfile(
                userId = "friend_demo_1",
                username = "stjarn_riddare_99",
                appearance = AvatarAppearance(
                    avatarType = AvatarType.KNIGHT,
                    primaryTintHex = 0xFF3498DB,
                    equippedItems = mapOf(
                        EquipmentSlot.HAT to "item_hat_starter_explorer",
                        EquipmentSlot.HAND_RIGHT to "item_hand_right_math_sword_1"
                    )
                ),
                totalStars = 42,
                addedAtEpochMillis = now
            )
        )
    }

    fun recordAppLaunch() {
        _privacySettings.update { current ->
            val nextCount = current.appLaunchCount + 1
            val shouldPrompt = nextCount >= BalanceConfig.CRASHLYTICS_CONSENT_LAUNCH_COUNT &&
                !current.crashlyticsConsentPromptShown
            current.copy(
                appLaunchCount = nextCount,
                showCrashlyticsDialogNow = shouldPrompt
            )
        }
    }

    fun setCrashlyticsConsent(granted: Boolean) {
        cloudGateway.setCrashlyticsCollectionEnabled(granted)
        _privacySettings.update { current ->
            current.copy(
                crashlyticsConsentPromptShown = true,
                crashlyticsConsentGranted = granted,
                showCrashlyticsDialogNow = false
            )
        }
    }

    fun selectProfile(profileId: String) {
        _activeProfileId.value = profileId
    }

    fun createSiblingProfile(displayName: String, ageYears: Int): ChildProfile {
        val now = Clock.System.now().toEpochMilliseconds()
        val newProfile = ChildProfile(
            id = "profile_${now}_${displayName.lowercase().hashCode().toUInt()}",
            displayName = displayName.trim().ifBlank { "Syskon" },
            ageYears = ageYears.coerceIn(6, 12),
            tutorialCompleted = false,
            appearance = AvatarAppearance(avatarType = AvatarType.CAT),
            unlockedAvatars = setOf(AvatarType.CAT),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        _profiles.update { it + newProfile }
        _activeProfileId.value = newProfile.id
        return newProfile
    }

    fun updateProfile(updated: ChildProfile) {
        _profiles.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
    }

    fun unlockWardrobeItem(itemId: String) {
        _unlockedItemIds.update { it + itemId }
    }

    fun equipItemOnActiveProfile(slot: EquipmentSlot, itemId: String?) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        _profiles.update { list ->
            list.map { profile ->
                if (profile.id != activeId) profile
                else {
                    val nextEquipped = profile.appearance.equippedItems.toMutableMap()
                    if (itemId == null) {
                        nextEquipped.remove(slot)
                    } else if (_unlockedItemIds.value.contains(itemId)) {
                        nextEquipped[slot] = itemId
                    }
                    profile.copy(
                        appearance = profile.appearance.copy(equippedItems = nextEquipped),
                        updatedAtEpochMillis = now
                    )
                }
            }
        }
    }

    fun selectAvatarTypeOnActiveProfile(avatarType: AvatarType) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        _profiles.update { list ->
            list.map { profile ->
                if (profile.id != activeId || !profile.unlockedAvatars.contains(avatarType)) profile
                else profile.copy(
                    appearance = profile.appearance.copy(avatarType = avatarType),
                    updatedAtEpochMillis = now
                )
            }
        }
    }

    fun customizeBaseAppearanceOnActiveProfile(
        primaryTintHex: Long,
        eyeExpressionIndex: Int,
        smileExpressionIndex: Int
    ) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        _profiles.update { list ->
            list.map { profile ->
                if (profile.id != activeId) profile
                else profile.copy(
                    appearance = profile.appearance.copy(
                        primaryTintHex = primaryTintHex,
                        eyeExpressionIndex = eyeExpressionIndex,
                        smileExpressionIndex = smileExpressionIndex
                    ),
                    updatedAtEpochMillis = now
                )
            }
        }
    }

    fun saveLevelProgress(progress: LevelProgress) {
        _levelProgressByProfile.update { map ->
            val currentForProfile = map[progress.profileId].orEmpty().toMutableMap()
            currentForProfile[progress.levelId] = progress
            map + (progress.profileId to currentForProfile)
        }
    }

    fun saveDailyActivity(record: DailyActivityRecord) {
        _dailyActivityByProfile.update { map ->
            val currentForProfile = map[record.profileId].orEmpty().toMutableMap()
            currentForProfile[record.calendarDateIso] = record
            map + (record.profileId to currentForProfile)
        }
    }

    fun logStruggledWord(profileId: String, wordNormalized: String, levelId: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val dictEntry = _dictionary.value[ForcedAlignmentEngine.normalizeForAlignment(wordNormalized)]
        val syllables = dictEntry?.syllables ?: splitSwedishSyllablesHeuristic(wordNormalized)

        _struggledWordsByProfile.update { map ->
            val currentList = map[profileId].orEmpty().toMutableList()
            val idx = currentList.indexOfFirst { it.wordNormalized == wordNormalized.lowercase() }
            if (idx >= 0) {
                val existing = currentList[idx]
                currentList[idx] = existing.copy(
                    encounterCount = existing.encounterCount + 1,
                    lastEncounteredEpochMillis = now
                )
            } else {
                currentList.add(
                    StruggledWordLog(
                        id = "sw_${profileId}_${wordNormalized.lowercase()}",
                        profileId = profileId,
                        wordNormalized = wordNormalized.lowercase(),
                        syllables = syllables,
                        levelId = levelId,
                        encounterCount = 1,
                        lastEncounteredEpochMillis = now
                    )
                )
            }
            map + (profileId to currentList.sortedByDescending { it.encounterCount })
        }
    }

    fun logMathError(
        profileId: String,
        problemType: MathProblemType,
        problemPrompt: String,
        wrongAttempts: Int,
        hintsUsed: Int
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = MathErrorLog(
            id = "me_${profileId}_$now",
            profileId = profileId,
            problemType = problemType,
            problemPrompt = problemPrompt,
            wrongAttemptsCount = wrongAttempts,
            hintsUsedCount = hintsUsed,
            timestampEpochMillis = now
        )
        _mathErrorsByProfile.update { map ->
            val currentList = map[profileId].orEmpty()
            map + (profileId to (listOf(entry) + currentList))
        }
    }

    fun lookupDictionaryWord(rawWord: String): DictionaryEntry {
        val cleaned = ForcedAlignmentEngine.normalizeForAlignment(rawWord)
        _dictionary.value[cleaned]?.let { return it }
        val syllables = splitSwedishSyllablesHeuristic(cleaned)
        return DictionaryEntry(
            wordNormalized = cleaned,
            displayWord = rawWord.trim { !it.isLetterOrDigit() },
            syllables = syllables,
            explanationSv = "Lyssna på ordet och läs det i stavelser: $syllables",
            exampleSentenceSv = ""
        )
    }

    suspend fun addFriendByQuery(query: String): Result<FriendProfile> {
        val lookupResult = cloudGateway.lookupFriendByUsernameOrQr(query)
        if (lookupResult.isFailure) {
            val cause = lookupResult.exceptionOrNull()
            val reason = (cause as? AddFriendException)?.reason ?: AddFriendError.NETWORK
            return Result.failure(AddFriendException(reason))
        }
        val friend = lookupResult.getOrNull()
            ?: return Result.failure(AddFriendException(AddFriendError.NOT_FOUND))

        val alreadyFriend = _friends.value.any {
            it.userId == friend.userId || it.username.equals(friend.username, ignoreCase = true)
        }
        if (alreadyFriend) {
            return Result.failure(AddFriendException(AddFriendError.ALREADY_FRIEND))
        }

        val toAdd = if (friend.addedAtEpochMillis == 0L) {
            friend.copy(addedAtEpochMillis = Clock.System.now().toEpochMilliseconds())
        } else {
            friend
        }
        _friends.update { current -> listOf(toAdd) + current }
        return Result.success(toAdd)
    }

    fun removeFriend(userId: String) {
        _friends.update { list -> list.filterNot { it.userId == userId } }
    }

    suspend fun sendEmailSignInLink(email: String): Result<Unit> {
        val cleaned = email.trim()
        val res = cloudGateway.sendSignInLinkToEmail(cleaned)
        return res.onSuccess {
            PendingEmailLinkStorage.saveEmail(cleaned)
            _privacySettings.update {
                it.copy(
                    pendingSignInEmail = cleaned,
                    emailLinkSent = true
                )
            }
        }
    }

    suspend fun completeEmailLinkSignIn(
        link: String,
        emailOverride: String? = null
    ): Result<Unit> {
        val cleanedLink = link.trim()
        if (!cloudGateway.isSignInWithEmailLink(cleanedLink)) {
            return Result.failure(IllegalArgumentException("Ogiltig e-postlänk"))
        }

        val resolvedEmail = emailOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: _privacySettings.value.pendingSignInEmail?.takeIf { it.isNotBlank() }
            ?: PendingEmailLinkStorage.getSavedEmail()?.takeIf { it.isNotBlank() }

        if (resolvedEmail == null) {
            // Link opened on a different device where the email wasn't saved locally;
            // store the incoming link so the user can provide their email to complete sign-in.
            _privacySettings.update {
                it.copy(pendingIncomingEmailLink = cleanedLink)
            }
            return Result.failure(IllegalStateException("Ange e-postadress för att slutföra inloggningen"))
        }

        val res = cloudGateway.signInWithEmailLink(resolvedEmail, cleanedLink)
        return res.map { uid ->
            PendingEmailLinkStorage.clearSavedEmail()
            _privacySettings.update {
                it.copy(
                    isLocalOnlyMode = false,
                    optionalAccountEmail = resolvedEmail,
                    optionalCloudUserId = uid,
                    pendingSignInEmail = null,
                    emailLinkSent = false,
                    pendingIncomingEmailLink = null
                )
            }
        }
    }

    suspend fun connectOptionalEmailAccount(email: String, password: String): Result<Unit> {
        val res = cloudGateway.registerOptionalEmailAccount(email, password)
        return res.map { uid ->
            PendingEmailLinkStorage.clearSavedEmail()
            _privacySettings.update {
                it.copy(
                    isLocalOnlyMode = false,
                    optionalAccountEmail = email.trim(),
                    optionalCloudUserId = uid,
                    pendingSignInEmail = null,
                    emailLinkSent = false,
                    pendingIncomingEmailLink = null
                )
            }
        }
    }

    fun enableLocalOnlyMode() {
        (cloudGateway as? FirebaseSyncService)?.setLocalOnlyMode(true)
        _privacySettings.update {
            it.copy(isLocalOnlyMode = true)
        }
    }

    suspend fun deleteCloudAccountAndSwitchToLocalOnly(): Result<Unit> {
        val uid = _privacySettings.value.optionalCloudUserId
        if (uid != null) {
            cloudGateway.deleteAccountAndAllCloudData(uid)
        }
        PendingEmailLinkStorage.clearSavedEmail()
        _privacySettings.update {
            it.copy(
                isLocalOnlyMode = true,
                optionalAccountEmail = null,
                optionalCloudUserId = null,
                pendingSignInEmail = null,
                emailLinkSent = false,
                pendingIncomingEmailLink = null
            )
        }
        return Result.success(Unit)
    }

    fun currentLocalDateIso(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return now.toString()
    }

    /**
     * Loads the previously cached lexicon (dictionary) from the Room-backed [lexiconCache], if any,
     * into memory. Must run before [syncCurriculumFromCloud] so the remote version check has a
     * real local version to compare against instead of `null`.
     */
    suspend fun loadCachedLexicon() {
        val cache = lexiconCache ?: return
        val version: Int?
        val entries: List<DictionaryEntry>
        try {
            version = cache.loadVersion()
            entries = cache.loadEntries()
        } catch (_: Throwable) {
            return
        }
        if (version == null || entries.isEmpty()) return
        _dictionary.value = entries.associateBy { ForcedAlignmentEngine.normalizeForAlignment(it.wordNormalized) }
        lexiconVersion = version
    }

    /**
     * Downloads the lexicon from Firestore (`lexicon/meta` + `lexicon/chunk_N`) when the remote
     * version differs from the locally known one, caches it to Room, and updates in-memory state.
     * Returns true only when a newer lexicon was actually downloaded and applied.
     */
    private suspend fun syncLexiconFromCloud(): Boolean {
        val meta = cloudGateway.fetchLexiconMeta() ?: return false
        if (meta.chunkCount <= 0 || meta.version == lexiconVersion) return false
        val entries = cloudGateway.fetchLexiconEntries(meta.chunkCount)
        if (entries.isEmpty()) return false
        try {
            lexiconCache?.replace(meta.version, entries)
        } catch (_: Throwable) {
            // Cache write failure must not block serving the freshly downloaded lexicon in memory.
        }
        _dictionary.value = entries.associateBy { ForcedAlignmentEngine.normalizeForAlignment(it.wordNormalized) }
        lexiconVersion = meta.version
        return true
    }

    /**
     * Downloads public curriculum content (`courses`, `levels`, `tasks`, `lexicon`) from Firestore
     * using delta version checking (`project.md` §3: "Delta-uppdatering via versionscheck;
     * endast ändrade nivåer hämtas"). Safe no-op fallback if offline. The lexicon syncs
     * independently of courses/levels/tasks so it is never skipped when courses fail to load.
     */
    suspend fun syncCurriculumFromCloud(): Boolean {
        val lexiconUpdated = syncLexiconFromCloud()

        val remoteCourses = cloudGateway.fetchRemoteCourses()
        if (remoteCourses.isEmpty()) return lexiconUpdated

        val coursesToSync = SyncMergeEngine.selectCoursesNeedingDeltaSync(
            localCourses = _courses.value,
            remoteCourses = remoteCourses
        )
        if (coursesToSync.isNotEmpty()) {
            val mergedCoursesById = _courses.value.associateBy { it.id }.toMutableMap()
            val mergedLevelsById = _levels.value.associateBy { it.id }.toMutableMap()
            val syncedLevelIds = mutableSetOf<String>()

            for (course in coursesToSync) {
                mergedCoursesById[course.id] = course
                val remoteLevels = cloudGateway.fetchLevelsForCourse(course.id)
                for (lvl in remoteLevels) {
                    mergedLevelsById[lvl.id] = lvl
                    syncedLevelIds.add(lvl.id)
                }
            }

            // FirebaseSyncService.fetchTasksForCourse ignores its courseId argument and always
            // returns the full task set, so fetch it exactly once (instead of once per course)
            // and keep only the tasks belonging to the levels we're actually syncing.
            val remoteTasks = cloudGateway.fetchTasksForCourse(coursesToSync.first().id)
                .filter { it.levelId in syncedLevelIds }

            if (remoteTasks.isNotEmpty()) {
                _tasksById.update { current ->
                    val next = current.toMutableMap()
                    remoteTasks.forEach { task -> next[task.id] = task }
                    next
                }
            }

            _courses.value = mergedCoursesById.values.toList()
            _levels.value = mergedLevelsById.values.sortedBy { it.order }
        }

        return coursesToSync.isNotEmpty() || lexiconUpdated
    }

    companion object {
        fun splitSwedishSyllablesHeuristic(word: String): String {
            if (word.length <= 4) return word
            val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y', 'å', 'ä', 'ö')
            val builder = StringBuilder()
            var countSinceBreak = 0
            for (i in word.indices) {
                val ch = word[i]
                builder.append(ch)
                countSinceBreak++
                if (ch.lowercaseChar() in vowels && i < word.length - 2 && countSinceBreak >= 2) {
                    val next1 = word[i + 1].lowercaseChar()
                    val next2 = word[i + 2].lowercaseChar()
                    if (next1 !in vowels && next2 in vowels) {
                        builder.append('-')
                        countSinceBreak = 0
                    } else if (i < word.length - 3 && next1 !in vowels && next2 !in vowels) {
                        builder.append(word[i + 1])
                        builder.append('-')
                        countSinceBreak = 0
                        // Skip the consonant we just appended in next iteration handled via index check
                    }
                }
            }
            // Clean up any double hyphens from heuristic
            return builder.toString().replace("--", "-").trim('-')
        }

        fun buildSeedWardrobeCatalog(): List<WardrobeItem> {
            val items = mutableListOf<WardrobeItem>()
            // 1. Starter item unlocked by Tutorial
            items.add(
                WardrobeItem(
                    id = BalanceConfig.TUTORIAL_FIRST_REWARD_ITEM_ID,
                    nameSv = "Skogsgläntans Upptäckarhatt",
                    descriptionSv = "Den första glittrande hatten som Lixo hittade under tutorial-sagan!",
                    slot = EquipmentSlot.HAT,
                    rarity = Rarity.COMMON,
                    subjectTheme = Subject.SWEDISH,
                    meshAssetPath = "3d_sample.glb",
                    meshNodeName = "socket_hat_explorer",
                    unlockStarRequirement = 0,
                    primaryColorHex = 0xFF2ECC71
                )
            )

            // 2. Named signature items used in demos/achievements
            items.add(
                WardrobeItem(
                    id = "item_hand_right_math_sword_1",
                    nameSv = "Siffersvärdet av Kristall",
                    descriptionSv = "Ett lysande svärd smitt av kloka uträkningar i matematik.",
                    slot = EquipmentSlot.HAND_RIGHT,
                    rarity = Rarity.RARE,
                    subjectTheme = Subject.MATH,
                    meshAssetPath = "3d_sample.glb",
                    meshNodeName = "socket_hand_right_sword",
                    unlockStarRequirement = 6,
                    primaryColorHex = 0xFF3498DB
                )
            )
            items.add(
                WardrobeItem(
                    id = "item_shoulders_back_sv_cape_1",
                    nameSv = "Sagoberättarens Stjärnmantel",
                    descriptionSv = "En mjuk mantel som fladdrar när du läser svenska sagor med inlevelse.",
                    slot = EquipmentSlot.SHOULDERS_BACK,
                    rarity = Rarity.EPIC,
                    subjectTheme = Subject.SWEDISH,
                    meshAssetPath = "3d_sample.glb",
                    meshNodeName = "socket_shoulders_cape",
                    unlockStarRequirement = 15,
                    unlockAchievementId = "ach_speed_reader_5",
                    primaryColorHex = 0xFF9B59B6
                )
            )

            val slotTemplates = listOf(
                Triple(EquipmentSlot.HAT, Subject.ENGLISH, listOf("Krona", "Trollhatt", "Riddarhjälm", "Stjärndiadem", " Äventyrshuva", "Drakmask", "Alvluva", " Kungahatt", " Solhatt", "Månkrona", " Runhjälm", " Vintermössa")),
                Triple(EquipmentSlot.SHOULDERS_BACK, Subject.SWEDISH, listOf("Vintermantel", "Drakvingar", "Riddarcape", "Skogsmantel", "Solmantel", "Molnkappa", "Stjärnslöja", "Äventyrsryggsäck", " Fjädermantel", "Norrskenscape", "Sköldkappa", "Kungamantel")),
                Triple(EquipmentSlot.ARMS_GLOVES, Subject.MATH, listOf("Järnhandskar", "Magikerhandskar", "Guldarmband", "Skogsvantar", "Kristallvantar", "Riddararmar", "Blixthandskar", "Solarmband", " Stjärnhandskar", "Runvantar", "Klovantar", "Mästarhandskar")),
                Triple(EquipmentSlot.ARMOR_SHIRT, Subject.SWEDISH, listOf("Riddarrustning", "Trollkarlsrock", "Skogstunika", "Prinsessklänning", "Stjärnharnesk", "Drakpansar", "Äventyrsväst", "Måntunika", "Solrustning", "Kristallpansar", "Sagodräkt", "Legendarisk Rustning")),
                Triple(EquipmentSlot.PANTS, Subject.SWEDISH, listOf("Äventyrsbyxor", "Riddarbenskenor", "Skogsbyxor", "Stjärnbyxor", "Magikerbyxor", "Drakbenskenor", "Solbyxor", "Månbyxor", " Vinterbyxor", "Kristallbyxor", "Kungabyxor", "Guldbenskenor")),
                Triple(EquipmentSlot.SHOES, Subject.SWEDISH, listOf("Vindstövlar", "Riddarskor", "Smygartofflor", "Stjärnstövlar", "Fjäderskor", "Drakstövlar", "Solskor", "Månstövlar", "Hoppskor", "Kristallstövlar", "Skogsstövlar", "Guldskor")),
                Triple(EquipmentSlot.HAND_RIGHT, Subject.MATH, listOf("Mattesvärd", "Trollstav", "Stjärnspira", "Runyxa", "Solstav", "Kristallklinga", "Blixtsvärd", "Månstav", " Kunskapsstav", "Draksvärd", "Sifferspira", "Evighetssvärd")),
                Triple(EquipmentSlot.HAND_LEFT, Subject.MATH, listOf("Siffersköld", "Stjärnsköld", "Runbok", "Kristallsköld", "Draksköld", "Solsköld", "Månlykta", "Magisk Lykta", "Timglassköld", "Ekbarkssköld", "Äventyrskompass", "Guldsköld")),
                Triple(EquipmentSlot.TAIL, Subject.SWEDISH, listOf("Fluffig Kattsvans", "Glad Hundsvans", "Draksvans", "Stjärnsvans", "Rävsvans", "Eldsvans", "Regnbågssvans", "Kristallsvans", "Månsvans", "Solsvans", "Komet-svans", "Guldkatt-svans"))
            )

            val rarities = Rarity.entries
            val palette = listOf(0xFF9E9E9E, 0xFF2ECC71, 0xFF3498DB, 0xFF9B59B6, 0xFFF39C12)

            var globalIdx = 1
            for ((slot, subject, names) in slotTemplates) {
                for ((idx, rawName) in names.withIndex()) {
                    val rarity = when {
                        idx < 4 -> Rarity.COMMON
                        idx < 7 -> Rarity.UNCOMMON
                        idx < 9 -> Rarity.RARE
                        idx < 11 -> Rarity.EPIC
                        else -> Rarity.LEGENDARY
                    }
                    val rarityIdx = rarities.indexOf(rarity)
                    val isStreakSet = idx == 11
                    items.add(
                        WardrobeItem(
                            id = "item_${slot.id}_${idx + 1}",
                            nameSv = rawName.trim(),
                            descriptionSv = "Upplåsbar ${rawName.trim().lowercase()} för alla avatarer i Lixo.",
                            slot = slot,
                            rarity = rarity,
                            subjectTheme = subject,
                            meshAssetPath = "3d_sample.glb",
                            meshNodeName = "${slot.socketName}_${idx + 1}",
                            scaleMultiplier = if (rarity == Rarity.LEGENDARY) 1.15f else 1.0f,
                            unlockStarRequirement = (globalIdx * 2).coerceAtMost(250),
                            unlockAchievementId = if (rarity == Rarity.EPIC || rarity == Rarity.LEGENDARY) "ach_${slot.id}_$idx" else null,
                            isWeeklyStreakSetPiece = isStreakSet,
                            primaryColorHex = palette[rarityIdx]
                        )
                    )
                    globalIdx++
                }
            }
            return items
        }

        /**
         * Builds the 160 campaign levels + tasks. Swedish (40) and English (20) are interleaved
         * across orders 1..100 (order%5==0 -> English, else even -> math slot, else Swedish);
         * the 100 math levels are built separately from [MathCurriculum] and sit at the even,
         * non-multiple-of-10 orders within 1..100 (n1..n40) plus orders 101..160 (n41..n100).
         */
        fun buildSeedCampaignLevelsAndTasks(
            wardrobeItems: List<WardrobeItem>
        ): Pair<List<CampaignLevel>, Map<String, HomeworkTask>> {
            val levels = mutableListOf<CampaignLevel>()
            val tasks = mutableMapOf<String, HomeworkTask>()

            val biomes = MathCurriculum.BIOMES

            // Ordered by difficulty: 40 unique stories, one per Swedish level (no repeats).
            // 1-14 = course_sv_1 (grade 1), 15-27 = course_sv_2 (grade 2), 28-40 = course_sv_3 (grade 3).
            val swedishStories = listOf(
                "Lixo i Skogen" to "Lixo är en katt. Lixo bor i en stor skog. Solen är varm. Lixo vill sova. Men en myra kittlar Lixo på nosen. Atjo!",
                "Den Lilla Fågeln" to "Lixo ser en fågel. Fågeln är liten och gul. Den sjunger fint. Lixo vill också sjunga. Men Lixo kan bara säga mjau. Fågeln skrattar.",
                "Hunden Bo" to "Här är hunden Bo. Bo har en röd boll. Lixo kastar bollen. Bo springer fort. Men Bo kommer tillbaka med en sko! Vems sko är det?",
                "Blåbär i Skogen" to "Lixo är hungrig. Lixo hittar blåbär. Lixo äter och äter. Nu är nosen blå. Bo skrattar: ”Du har en blå nos!”",
                "Regn i Skogen" to "Det regnar i skogen. Lixo blir blöt. Lixo springer in under en gran. Där sitter en groda. Grodan säger: ”Vilket fint väder!” Lixo tycker inte det.",
                "Den Kloka Ugglan" to "I ett högt träd bor en uggla. Ugglan är klok. Hon kan läsa. Varje kväll läser hon en saga för Lixo. Men i kväll somnar ugglan först. Hon snarkar!",
                "Bron och Bäcken" to "Lixo går över en liten bro. Under bron rinner en bäck. I bäcken simmar en fisk. Lixo vill säga hej. Lixo lutar sig fram och plask! Nu simmar Lixo också.",
                "Ekorrens Nötter" to "En ekorre samlar nötter. Den gömmer en nöt här och en nöt där. Men var är nötterna nu? Ekorren har glömt! Lixo och Bo letar. De hittar sju nötter.",
                "Klockan i Byn" to "Lixo kommer till en by. Mitt i byn står ett torn med en stor klocka. Klockan slår tre slag. Ding, dong, ding! Bo tror att det är dags för mat. Han springer till bagaren.",
                "Bagarens Nyckel" to "Bagaren är ledsen. Han har tappat sin nyckel. Nu kan han inte öppna sitt bageri. Lixo och Bo letar. Bo hittar en sko, en pinne och till slut nyckeln! Bagaren ger dem en bulle var.",
                "Grytan som Sjöng" to "Trollkarlen har en stor gryta. Han lägger i blåbär och lite socker. Då börjar grytan sjunga! Den sjunger fel och mycket högt. Lixo håller för öronen. Trollkarlen dansar ändå.",
                "Fjärilarna i Boken" to "Prinsessan Mira och Bo bygger ett torn av kuddar. Sedan öppnar de en sagobok. Små fjärilar flyger ut. En fjäril landar på Bos nos. Bo tittar på den. Oj, nu går ögonen i kors!",
                "Ugglans Karta" to "Ugglan har en gammal karta. På kartan finns en stig. Stigen går förbi en sjö och upp på ett berg. Lixo vill gå dit. Men vänta! Ugglan håller kartan upp och ner.",
                "Den Konstiga Kompassen" to "Lixo hittar en kompass i mossan. Nålen snurrar runt, runt, runt. Lixo snurrar också. Nu är Lixo yr! Till slut pekar nålen mot ett gammalt äppelträd. Vad finns där?",
                "Skattkistan under Näckrosen" to "Vid den lugna sjön låg en vit näckros på vattnet. Hunden Bo doppade försiktigt tassen. Då såg han något som glimmade på botten. Det var en liten skattkista! Lixo och Bo drog upp den med ett rep. Inuti fanns inget guld, bara en gammal strumpa och en lapp från ugglan: ”Tack för att ni hittade min strumpa!”",
                "Marknaden i Sifferdalen" to "På lördagen var det marknad i Sifferdalen. Lixo hade tio kronor i fickan. Det fanns äpplen, bullar och färgglada ballonger. Lixo köpte ett äpple för fem kronor och en ballong för fem kronor. Nu var pengarna slut. Plötsligt blåste det till, och ballongen lyfte Bo en liten bit upp i luften! Han landade mjukt i en hög med mjöl hos bagaren.",
                "Båtarna i Hamnen" to "Nere i Glosornas Hamn gungade segelbåtarna i vinden. Lixo satt på bryggan och räknade dem: sju vita och tre blå. En sjöman från England vinkade och ropade ”Hello!” Lixo kände igen ordet från engelskan och ropade ”Hello!” tillbaka. Bo ville också vara med, men det blev bara ”voff”. Sjömannen skrattade och bjöd dem på en båttur.",
                "Snäckorna på Stranden" to "När solen gick ner promenerade Lixo längs stranden. Överallt låg vackra snäckor som glittrade i kvällsljuset. Lixo lyfte upp den största snäckan för att lyssna på havet. Men ut ur snäckan kikade en liten krabba, som nös rakt på Lixos nos! Lixo ställde försiktigt tillbaka snäckan och sa förlåt.",
                "Fyren vid Havet" to "Längst ut på klippan stod en hög fyr. Varje kväll klättrade fyrvaktaren upp för den långa trappan och putsade det stora glaset. En kväll hade hon glömt sina glasögon. Hon putsade och putsade, men det var inte glaset. Det var en mås som satt i fönstret! Måsen tyckte att det kittlades. Sedan tände hon lampan, så att båtarna hittade hem.",
                "Brevduvan med Guldbrevet" to "En morgon landade en vit brevduva på fönsterbrädan. I näbben hade den ett kuvert av guldpapper. Det var en inbjudan till den stora sagofestivalen i Stjärnborgen! Lixo blev så glad att det pirrade i magen. Lixo skrev ett svar och gav det till duvan. Tyvärr var duvan hungrig och åt upp halva brevet på vägen hem.",
                "Svamparna som Lyste" to "När det blev kväll i skogen hände något märkligt. Små svampar mellan stenarna började lysa i grönt och blått. Ljuset visade vägen för alla djur som var på väg hem. Bo viftade så glatt på svansen att den svepte över svamparna. Då började hela svansen lysa! Lixo behövde ingen lampa resten av vägen hem.",
                "Den Flygande Bokhyllan" to "I trollkarlens bibliotek fanns böcker från golv till tak. En dag hade böckerna fått små vingar av papper. När Lixo viskade namnet på sin favoritbok svävade den sakta ner från hyllan. Men då nös trollkarlen, och alla böcker flög iväg på en gång. En kokbok fastnade i taklampan, och en tjock ordbok jagade trollkarlen runt bordet.",
                "Drakens Varma Halsduk" to "Högt uppe i bergen satt en liten grön drake och huttrade i snön. Den hade tappat sin halsduk i blåsten. Prinsessan Mira tog fram garn och stickor. Hela kvällen stickade hon en lång, randig halsduk. Den blev så lång att draken kunde linda den tolv varv runt halsen och ändå ha en bit över till svansen.",
                "Grottan som Spelade" to "Riddaren Ruben kröp försiktigt in i en mörk grotta. Plötsligt började väggarna lysa i blått och lila. Det var kristaller! Varje gång han tog ett steg hördes en klar ton, precis som från ett piano. Ruben blev så glad att han började dansa. Det lät som världens konstigaste konsert, och ekot svarade med en hel låt.",
                "Riddarens Vagn" to "Tidigt på morgonen tog riddaren Ruben på sig sin blå mantel. Han lastade sin sköld på riddarvagnen och åkte genom dalen. Bönderna behövde hjälp, eftersom en stor sten låg mitt på vägen. Ruben drog och drog, men stenen rörde sig inte. Då nös stenen! Det var ingen sten alls, utan ett trött troll som hade somnat på vägen.",
                "Bron mellan Bergen" to "Mellan två bergstoppar hängde en smal bro av rep och plankor. Långt där nere brusade en flod. Hunden Bo gick först, och Lixo följde tätt efter. Mitt på bron mötte de en get som också ville över. Ingen ville backa. Till slut bjöd Lixo geten på ett äpple, och då gick hon glatt baklänges hela vägen tillbaka.",
                "Snöleopardens Spår" to "I den nyfallna snön syntes runda tassavtryck. Lixo och Bo följde spåren uppför berget. De ledde till en solig bergshylla där en snöleopard låg och vilade. Den var inte farlig alls, utan vänlig och lite blyg. Den hade gått i cirklar hela morgonen, eftersom den hade följt sina egna spår! Nu visade den dem en hemlig väg genom bergen.",
                "Spegelsjöns Gåta" to "Spegelsjön låg så blank och stilla att molnen såg ut att simma under ytan. På andra sidan sjön fanns en port i berget, men den var låst. I porten fanns en gåta inristad. Den sa att porten bara öppnades för den som kunde räkna stjärnbilderna i vattnet. Lixo väntade tålmodigt tills det blev mörkt och räknade sedan till sju. Bo räknade till åtta, men den åttonde stjärnan var bara hans egen blöta nos i vattnet. Till slut gled porten långsamt upp med ett mjukt mullrande.",
                "Smedens Stjärnsköld" to "Byns smed stod vid sin glödande ässja medan gnistorna yrde omkring honom. Av en bit gammal stjärnmalm, som hade fallit från himlen för länge sedan, smidde han en lätt men hållbar sköld. Med en fin mejsel prydde han den med siffror och runor. Skölden blev så blank att riddaren Ruben, som kom för att hämta den, blev rädd för sin egen spegelbild och gömde sig bakom en tunna. Smeden skrattade så att hans mustasch fladdrade.",
                "Klocktornets Melodi" to "Mitt i Sifferdalen stod ett gammalt klocktorn av röd sten. Varje gång visarna pekade på en hel timme öppnades en liten lucka högst upp. Ut kom mekaniska fåglar av mässing som spelade en vacker trudelutt för byborna. En dag tystnade fåglarna. Lixo klättrade upp i tornet och upptäckte att en kugge hade lossnat. Efter lite skruvande sjöng fåglarna igen, men nu slog klockan tretton slag i stället för tolv. Byborna bestämde genast att klockan tretton skulle vara den nya fikatiden.",
                "Ugglans Hemliga Pergament" to "Uppe i den högsta granen satt ugglan och rullade ut ett gulnat pergament. Den här gången höll hon det åt rätt håll, vilket hon var mycket stolt över. På kartan syntes en slingrande stig som ledde förbi det dånande vattenfallet och vidare upp mot Stjärnborgen. Ugglan förklarade att kartan var flera hundra år gammal och att bara den som var modig och nyfiken kunde följa den hela vägen. Lixo stoppade kartan i ryggsäcken och började vandra.",
                "Norrskenets Dans över Borgen" to "En gnistrande kall vinterkväll samlades alla vännerna på slottets takterrass med filtar och varm choklad. Plötsligt började himlen skimra. Gröna och violetta band av ljus slingrade sig fram över stjärnorna, som om någon målade med en jättestor pensel. Häxan Hilda påstod att norrskenet dansade vals, medan riddaren Ruben tyckte att det var en polka. Bo försökte dansa med, trasslade in sig i filtarna och rullade som en korv över hela terrassen. Sedan satt alla tysta och tittade upp, alldeles förtrollade.",
                "Trädgårdens Pratande Solros" to "Bakom slottets växthus växte en solros som var nästan lika hög som ett äppelträd. Varje morgon vände den sitt gula ansikte mot solen och hälsade artigt god morgon till alla som gick förbi. Trädgårdsmästaren påstod att den hade börjat prata efter en natt med stjärnfall. Lixo var tveksam ända tills solrosen en dag frågade vilken bok Lixo läste just nu. Sedan gav solrosen boktips i en hel timme, och Lixo kom för sent till middagen.",
                "Timglasets Sista Sandkorn" to "På skrivbordet i observatoriet stod ett stort timglas fyllt med gyllene sand. Astronomen förklarade att timglaset var förtrollat. Den som läste ett helt kapitel högt innan sanden hade runnit ut fick ge namn åt en ny stjärna. Lixo vände på timglaset, tog ett djupt andetag och började läsa. Precis när det sista sandkornet föll vände Lixo den sista sidan. Astronomen applåderade så ivrigt att hon välte sin tekopp över stjärnkartan, och sedan dess ser Karlavagnen ut att ha badat i te.",
                "Kometens Hälsning" to "Genom teleskopet i det högsta tornet upptäckte häxan Hilda en komet med en lång, silvervit svans. Hon förklarade för Lixo att kometen bara passerade jorden vart tionde år. Medan de tittade tycktes kometen blinka mot dem, nästan som en hälsning. Hilda sa att den som ser en komet ska önska något klokt. Lixo önskade att alla barn skulle få läsa spännande sagor. Hilda önskade sig en kvast som inte gnisslade varje gång hon svängde åt vänster.",
                "Kullen som Andades" to "Utanför Stjärnborgens murar låg en kulle som var täckt av mossa och ljung. En morgon märkte Lixo att kullen sakta höjdes och sänktes, som om den andades. Det var ingen kulle alls, utan en vänlig jätte som hade sovit i över hundra år! När jätten vaknade gäspade han så stort att fåglarna flög upp ur träden. Lixo berättade allt som hade hänt medan han sov, men det som förvånade jätten mest var att det fanns kanelbullar. Han åt fyrtio stycken till frukost, och bagaren fick baka hela dagen.",
                "Ekorrens Vinterförråd" to "Häxan Hilda flög lågt över ängen där klöver och blåklockor doftade sommar. Nedanför såg hon den lilla ekorren, som precis som förra året hade glömt var den hade gömt sina nötter. Vintern närmade sig, och förrådet i eken var fortfarande nästan tomt. Hilda landade, svängde med sin trollstav och lät en mild vind skaka hasselbuskarna. Snart regnade det nötter över gräset, och ekorren tackade henne med ett glatt litet skutt. En hasselnöt hade dock fastnat i Hildas hatt, och den upptäckte hon inte förrän på kvällen.",
                "Skeppet bland Molnen" to "Från Stjärnborgens högsta torn kunde man ibland se ett skepp som seglade bland molnen. Seglen var vävda av spindeltråd och dimma, och skeppet styrdes av en gammal kapten med ett långt, vitt skägg. En kväll kastade kaptenen ner en repstege och bjöd in Lixo på en resa. Medan skeppet gled tyst över de sovande byarna fick Lixo lära sig att navigera efter stjärnorna. När vinden plötsligt mojnade hängde kaptenen upp sitt skägg som extra segel, och så seglade de vidare.",
                "Drakens Gåta" to "Djupt inne i Stjärnborgen fanns en hemlig läsesal som vaktades av en gammal drake med runda glasögon. Draken släppte bara in besökare som kunde lösa en av hans gåtor. Han frågade vad det är som har en rygg men ingen mage, och massor av sidor men inga armar. Lixo funderade en stund och svarade att det måste vara en bok. Sedan frågade Lixo vad det är som går och går men aldrig kommer fram. Draken funderade så länge att han somnade, så Lixo viskade svaret i hans öra: klockan.",
                "Stjärnborgens Stora Bibliotek" to "Äntligen öppnades de tunga ekportarna till Stjärnborgens innersta sal. Lixo klev försiktigt in och tappade nästan andan. Hyllorna sträckte sig ända upp mot det välvda taket, och där väntade tusentals berättelser och kloka gåtor. Alla vänner från resan stod redan där: ugglan, Bo, prinsessan Mira, riddaren Ruben, häxan Hilda och till och med det trötta trollet. Brevduvan kom flygande med andra halvan av Lixos brev, bagaren bjöd på kanelbullar, och trollkarlens gryta sjöng falskt men glatt. Tillsammans firade de att Lixo hade läst sig hela vägen genom sagoriket."
            )

            // Swedish (40 levels) and English (20 levels) occupy orders 1..100, interleaved with
            // math slots: order%5==0 -> ENGLISH, else even -> MATH (handled separately below),
            // else SWEDISH. Math itself is independent of this loop; see MathCurriculum for its
            // own orders (2,4,6,...,98 for n1..n40, and 101..160 for n41..n100).
            var swedishIdx = 0
            for (order in 1..100) {
                val subject = when {
                    order % 5 == 0 -> Subject.ENGLISH
                    order % 2 == 0 -> continue
                    else -> Subject.SWEDISH
                }
                val biome = biomes[(order - 1) / 20]
                val difficulty = (((order - 1) / 17) + 1).coerceIn(1, 6)

                val courseId = when (subject) {
                    Subject.SWEDISH -> when {
                        order <= 33 -> "course_sv_1"
                        order <= 67 -> "course_sv_2"
                        else -> "course_sv_3"
                    }
                    else -> "course_en_1"
                }
                val levelId = "level_$order"
                val taskId = "task_$order"
                val rewardItem = wardrobeItems.getOrNull(order % wardrobeItems.size)
                val refWpm = BalanceConfig.referenceWpmForDifficulty(difficulty)

                val currentStory = if (subject == Subject.SWEDISH) {
                    swedishStories[swedishIdx % swedishStories.size].also { swedishIdx++ }
                } else null

                val titleSv = when (subject) {
                    Subject.SWEDISH -> "Bana $order: ${currentStory?.first ?: "Sagan i $biome"}"
                    else -> "Bana $order: Glosäventyr i $biome"
                }

                levels.add(
                    CampaignLevel(
                        id = levelId,
                        courseId = courseId,
                        order = order,
                        title = titleSv,
                        biomeNameSv = biome,
                        subject = subject,
                        difficulty = difficulty,
                        timeRequirementSeconds = 45 + (difficulty * 10),
                        referenceWpm = refWpm,
                        taskId = taskId
                    )
                )

                val task = when (subject) {
                    Subject.SWEDISH -> {
                        HomeworkTask(
                            id = taskId,
                            levelId = levelId,
                            type = TaskType.READING,
                            subject = Subject.SWEDISH,
                            textContent = currentStory?.second,
                            rewardItemId = rewardItem?.id
                        )
                    }
                    else -> {
                        HomeworkTask(
                            id = taskId,
                            levelId = levelId,
                            type = TaskType.ENGLISH_VOCAB,
                            subject = Subject.ENGLISH,
                            textContent = "The cat has a big blue hat. The dog can jump high in the sun.",
                            englishItems = listOf(
                                EnglishGlosaItem(
                                    id = "en_${order}_1",
                                    englishWord = "hat",
                                    swedishTranslation = "hatt",
                                    simpleSentenceEn = "The cat has a blue hat.",
                                    simpleSentenceSv = "Katten har en blå hatt."
                                ),
                                EnglishGlosaItem(
                                    id = "en_${order}_2",
                                    englishWord = "star",
                                    swedishTranslation = "stjärna",
                                    simpleSentenceEn = "I see a bright star.",
                                    simpleSentenceSv = "Jag ser en klar stjärna."
                                ),
                                EnglishGlosaItem(
                                    id = "en_${order}_3",
                                    englishWord = "castle",
                                    swedishTranslation = "slott",
                                    simpleSentenceEn = "The knight lives in a castle.",
                                    simpleSentenceSv = "Riddaren bor i ett slott."
                                )
                            ),
                            rewardItemId = rewardItem?.id
                        )
                    }
                }
                tasks[taskId] = task
            }

            for (n in 1..MathCurriculum.MATH_LEVEL_COUNT) {
                val spec = MathCurriculum.mathLevelSpec(n)
                val order = spec.order
                val levelId = "level_$order"
                val taskId = "task_$order"
                val rewardItem = wardrobeItems.getOrNull(order % wardrobeItems.size)

                levels.add(
                    CampaignLevel(
                        id = levelId,
                        courseId = spec.courseId,
                        order = order,
                        title = spec.title,
                        biomeNameSv = spec.biome,
                        subject = Subject.MATH,
                        difficulty = spec.difficulty,
                        timeRequirementSeconds = spec.timeRequirementSeconds,
                        referenceWpm = spec.referenceWpm,
                        taskId = taskId
                    )
                )

                tasks[taskId] = HomeworkTask(
                    id = taskId,
                    levelId = levelId,
                    type = TaskType.MATH,
                    subject = Subject.MATH,
                    mathProblems = MathCurriculum.buildMathProblems(n, order),
                    rewardItemId = rewardItem?.id
                )
            }

            return levels.sortedBy { it.order } to tasks
        }

        fun buildSeedAchievements(): List<Achievement> = listOf(
            Achievement(
                id = "ach_speed_reader_5",
                titleSv = "Blixtläsaren",
                descriptionSv = "Klara 5 sagor i AI-läge med full 3-stjärnig referenshastighet.",
                rarity = Rarity.EPIC,
                requiresAiMode = true,
                targetCount = 5,
                currentCount = 2,
                isUnlocked = false,
                rewardItemId = "item_shoulders_back_sv_cape_1"
            ),
            Achievement(
                id = "ach_pronunciation_master_10",
                titleSv = "Kristallklart Uttal",
                descriptionSv = "Läs 10 sagor högt med AI-läsassistenten utan att hoppa över något ord.",
                rarity = Rarity.LEGENDARY,
                requiresAiMode = true,
                targetCount = 10,
                currentCount = 4,
                isUnlocked = false,
                rewardItemId = "item_armor_shirt_12"
            ),
            Achievement(
                id = "ach_math_clock_money",
                titleSv = "Tid- & Pengamästaren",
                descriptionSv = "Lös 15 uppgifter med Klockan och Pengar på första försöket.",
                rarity = Rarity.EPIC,
                requiresAiMode = false,
                targetCount = 15,
                currentCount = 6,
                isUnlocked = false,
                rewardItemId = "item_hand_right_12"
            ),
            Achievement(
                id = "ach_weekly_streak_7",
                titleSv = "Veckans Hjälte (7-dagars Streak)",
                descriptionSv = "Klara både läsning och matte 7 kalenderdagar i rad för ett legendariskt helset!",
                rarity = Rarity.LEGENDARY,
                requiresAiMode = false,
                targetCount = 7,
                currentCount = 1,
                isUnlocked = false,
                rewardItemId = "item_hat_12"
            )
        )
    }
}
