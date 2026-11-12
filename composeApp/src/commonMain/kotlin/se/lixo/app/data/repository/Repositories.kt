package se.lixo.app.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Clock
import se.lixo.app.data.local.FriendRemovalTombstone
import se.lixo.app.data.local.LexiconCache
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.local.createPlatformLexiconCache
import se.lixo.app.data.local.createPlatformProfileStore
import se.lixo.app.data.remote.CardState
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.FriendCloud
import se.lixo.app.data.remote.FriendInvite
import se.lixo.app.data.remote.SyncMergeEngine
import se.lixo.app.data.remote.isValidFriendCardId
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.GeographyChoices
import se.lixo.app.domain.content.GeographyCurriculum
import se.lixo.app.domain.content.LevelReward
import se.lixo.app.domain.content.MathCurriculum
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ACHIEVEMENT_LADDERS
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.AchievementAction
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
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
import se.lixo.app.domain.model.StruggledWordLog
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType
import se.lixo.app.domain.model.accepts
import se.lixo.app.domain.model.achievementId
import se.lixo.app.domain.model.isHand
import se.lixo.app.domain.model.requiresAiMode
import se.lixo.app.domain.social.Nickname
import se.lixo.app.domain.social.parseFriendCode
import se.lixo.app.domain.social.randomNickname
import se.lixo.app.platform.PendingEmailLinkStorage
import se.lixo.app.platform.PlatformFeatureFlags
import se.lixo.app.domain.model.AchievementStrings

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
    /** doc/plans/030 §4: separate from [cloudGateway] so tests can inject a `FakeFriendCloud`. */
    private val friendCloud: FriendCloud = cloudGateway as? FriendCloud ?: FirebaseSyncService(),
    /** Injected so friend-flow tests can fast-forward a 10-minute invite window deterministically. */
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val lexiconCache: LexiconCache? = createPlatformLexiconCache(),
    /**
     * Debug builds seed the dummy "Saga" profile (`DEBUG_SAGA_PROFILE_ID`) only when it is
     * missing from [profileStore] -- never modifying or removing other profiles, including a
     * user-edited Saga. Release builds seed no profile; the first profile comes from
     * [se.lixo.app.ui.screens.onboarding.FirstProfileSetupScreen]. Profiles, the active profile
     * id and the universal unlocked-item pool persist in Room through [profileStore].
     */
    private val isDebugBuild: Boolean = PlatformFeatureFlags().isDebugBuild,
    private val profileStore: ProfileStore? = createPlatformProfileStore(),
    val content: ContentRepository
) {
    private var lexiconVersion: Int? = null

    /**
     * The tutorial's curated reward item, from `content.dropRules.tutorialRewardItemId`
     * (doc/content/content-pack.md § Drop rules). A getter (not an initializer), so it always
     * reads the live pack and there is no init-order problem with [content], a constructor val.
     */
    private val tutorialRewardItemId: String get() = content.active.value.dropRules.tutorialRewardItemId

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

    private val _friendsByProfile = MutableStateFlow<Map<String, List<FriendProfile>>>(emptyMap())
    val friendsByProfile: StateFlow<Map<String, List<FriendProfile>>> = _friendsByProfile.asStateFlow()

    fun friendsOfActiveProfile(): List<FriendProfile> =
        _activeProfileId.value?.let { _friendsByProfile.value[it] }.orEmpty()

    /** doc/plans/030 §1: the current Firebase Auth uid, or null until [ensureCloudIdentity] succeeds. */
    private val _cloudUid = MutableStateFlow<String?>(null)
    val cloudUid: StateFlow<String?> = _cloudUid.asStateFlow()

    /** The open invite per profile created by [createFriendCode], so re-opening the sheet within the 10-min window shows the same code. */
    private val openInviteByProfile = mutableMapOf<String, FriendInvite>()

    /** doc/plans/030 §6: `content.sync()` is triggered at most once per session for an unknown friend item id. */
    private var didSyncForUnknownFriendItem = false

    private var cardUpsertJob: Job? = null

    /**
     * doc/plans/030 review M8: serializes [ensureMyCard] so two near-simultaneous callers (e.g.
     * [createFriendCode] and [addFriendByCode] both firing from a cold Avatar-tab open) can't both
     * see `profile.friendCardId == null` and each create their own `friend_cards` document.
     */
    private val ensureMyCardMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 041 review HIGH 1: serializes [ensureCloudIdentity] so the startup call (LixoApp), the
     * startup tombstone retry and any friend action can't verify/reset concurrently.
     */
    private val cloudIdentityMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 041 review HIGH 1: true once [_cloudUid] has been checked against the real Firebase Auth
     * uid in this process. A persisted uid (e.g. restored onto a new phone via Android
     * device-to-device transfer) is never trusted before that.
     */
    private var cloudUidVerified = false

    /**
     * doc/plans/030 review L4: profile ids whose own card failed to push (debounced upsert, or an
     * offline [ensureMyCard]); retried from [refreshFriends] rather than lost.
     */
    private val dirtyCardProfileIds = mutableSetOf<String>()

    /** doc/plans/030 review M2: uids currently tombstoned per profile, loaded once at startup. */
    private val _tombstonedUidsByProfile = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val _privacySettings = MutableStateFlow(AppPrivacySettings())
    val privacySettings: StateFlow<AppPrivacySettings> = _privacySettings.asStateFlow()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _profilesLoaded = MutableStateFlow(profileStore == null)
    val profilesLoaded: StateFlow<Boolean> = _profilesLoaded.asStateFlow()

    init {
        seedInitialData()
        val store = profileStore
        if (store != null) {
            persistScope.launch {
                loadPersistedProfiles(store)
                for (op in profileWrites) {
                    runCatching { op() }.onFailure { println("LixoProfiles: WARN profile store write failed: $it") }
                }
            }
        }
    }

    private fun buildDebugSagaProfile(now: Long): ChildProfile = ChildProfile(
        id = DEBUG_SAGA_PROFILE_ID,
        displayName = "Saga",
        ageYears = 8,
        tutorialCompleted = true,
        tutorialSkippableByParent = false,
        appearance = AvatarAppearance(
            avatarId = content.active.value.defaultAvatar.id,
            equipped = mapOf(EquipmentSlot.HAT to tutorialRewardItemId)
        ),
        unlockedAvatarIds = content.active.value.defaultAvatarIds,
        currentLevelOrder = 1,
        totalStars = 0,
        currentStreakDays = 0,
        bestStreakDays = 0,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now
    )

    /**
     * Reads the persisted profiles, unlocked item ids, active profile id and per-level progress
     * from [store], merges them with whatever is already in memory (in-memory wins so a user
     * edit made before the load finished is never clobbered), and resolves the active profile
     * id. Any failure is treated as "store is empty" so a corrupt/missing DB never blocks the UI
     * (`finally` always flips [_profilesLoaded] to true).
     */
    private suspend fun loadPersistedProfiles(store: ProfileStore) {
        try {
            val persisted = runCatching { store.loadProfiles() }
                .onFailure { println("LixoProfiles: WARN loadProfiles failed: $it") }
                .getOrDefault(emptyList())
            var persistedUnlocked = runCatching { store.loadUnlockedItemIds() }
                .onFailure { println("LixoProfiles: WARN loadUnlockedItemIds failed: $it") }
                .getOrDefault(emptySet())
            val persistedActiveId = runCatching { store.loadActiveProfileId() }
                .onFailure { println("LixoProfiles: WARN loadActiveProfileId failed: $it") }
                .getOrNull()
            val persistedProgress = runCatching { store.loadLevelProgress() }
                .onFailure { println("LixoProfiles: WARN loadLevelProgress failed: $it") }
                .getOrDefault(emptyList())
            val persistedFriends = runCatching { store.loadFriends() }
                .onFailure { println("LixoProfiles: WARN loadFriends failed: $it") }
                .getOrDefault(emptyMap())
            val persistedCloudUid = runCatching { store.loadCloudUid() }
                .onFailure { println("LixoProfiles: WARN loadCloudUid failed: $it") }
                .getOrNull()
            val persistedTombstones = runCatching { store.loadFriendRemovalTombstones() }
                .onFailure { println("LixoProfiles: WARN loadFriendRemovalTombstones failed: $it") }
                .getOrDefault(emptyList())

            var profilesToMerge = persisted
            if (isDebugBuild && persisted.none { it.id == DEBUG_SAGA_PROFILE_ID }) {
                val now = Clock.System.now().toEpochMilliseconds()
                val saga = buildDebugSagaProfile(now)
                profilesToMerge = persisted + saga
                persistedUnlocked = persistedUnlocked + tutorialRewardItemId
                runCatching { store.saveProfile(saga) }
                    .onFailure { println("LixoProfiles: WARN debug saveProfile(saga) failed: $it") }
                runCatching { store.saveUnlockedItem(tutorialRewardItemId, "DEBUG_SEED") }
                    .onFailure { println("LixoProfiles: WARN debug saveUnlockedItem failed: $it") }
            }

            _profiles.update { mem ->
                val memIds = mem.map { it.id }.toSet()
                profilesToMerge.filterNot { it.id in memIds } + mem
            }
            _unlockedItemIds.update { it + persistedUnlocked }
            _levelProgressByProfile.update { mem ->
                val persistedByProfile = persistedProgress.groupBy { it.profileId }
                    .mapValues { (_, rows) -> rows.associateBy { it.levelId } }
                (persistedByProfile.keys + mem.keys).associateWith { pid ->
                    persistedByProfile[pid].orEmpty() + mem[pid].orEmpty()
                }
            }
            _friendsByProfile.update { mem ->
                (persistedFriends.keys + mem.keys).associateWith { pid ->
                    val memUids = mem[pid].orEmpty().map { it.userId }.toSet()
                    persistedFriends[pid].orEmpty().filterNot { it.userId in memUids } + mem[pid].orEmpty()
                }
            }
            if (_cloudUid.value == null) _cloudUid.value = persistedCloudUid
            for (tombstone in persistedTombstones) {
                pendingRemovalsByProfile.getOrPut(tombstone.profileId) { mutableMapOf() }
                    .getOrPut(tombstone.friendUid) { tombstone }
            }
            _tombstonedUidsByProfile.update { mem ->
                val persistedByProfile = persistedTombstones.groupBy({ it.profileId }, { it.friendUid })
                    .mapValues { (_, uids) -> uids.toSet() }
                (persistedByProfile.keys + mem.keys).associateWith { pid ->
                    persistedByProfile[pid].orEmpty() + mem[pid].orEmpty()
                }
            }
            if (persistedCloudUid != null) {
                // 041 review HIGH 1: verify the restored uid before replaying removals keyed to it; on a
                // mismatch ensureCloudIdentity() wipes the tombstones, so replay only what is left.
                // (ensureCloudIdentity waits for _profilesLoaded, set in `finally` below -- no deadlock,
                // this is a separate coroutine.)
                persistScope.launch {
                    if (ensureCloudIdentity().isSuccess) {
                        retryPendingFriendRemovals(pendingRemovalsByProfile.values.flatMap { it.values.toList() })
                    }
                }
            } else {
                persistScope.launch { retryPendingFriendRemovals(persistedTombstones) }
            }

            val merged = _profiles.value
            val resolvedActiveId = _activeProfileId.value?.takeIf { id -> merged.any { it.id == id } }
                ?: persistedActiveId?.takeIf { id -> merged.any { it.id == id } }
                ?: merged.firstOrNull()?.id
            _activeProfileId.value = resolvedActiveId
            if (resolvedActiveId != null && resolvedActiveId != persistedActiveId) {
                enqueueProfileWrite { saveActiveProfileId(resolvedActiveId) }
            }
        } catch (t: Throwable) {
            println("LixoProfiles: WARN loadPersistedProfiles failed, treating store as empty: $t")
            // Treat as empty; debug Saga seed below still applies so a debug build never ends up
            // with zero profiles just because the store failed to read.
            if (isDebugBuild && _profiles.value.none { it.id == DEBUG_SAGA_PROFILE_ID }) {
                val now = Clock.System.now().toEpochMilliseconds()
                val saga = buildDebugSagaProfile(now)
                _profiles.update { it + saga }
                _unlockedItemIds.update { it + tutorialRewardItemId }
                _activeProfileId.value = _activeProfileId.value ?: saga.id
            }
        } finally {
            _profilesLoaded.value = true
        }
    }

    private fun enqueueProfileWrite(op: suspend ProfileStore.() -> Unit) {
        val store = profileStore ?: return
        profileWrites.trySend { store.op() }
    }

    /**
     * Updates [_profiles] and persists only the profiles that actually changed, in enqueue order
     * (a single FIFO [profileWrites] consumer, never one launch per mutation, so concurrent
     * writes on [Dispatchers.Default] can never finish out of order and persist a stale profile).
     */
    private fun updateProfiles(transform: (List<ChildProfile>) -> List<ChildProfile>) {
        var before: List<ChildProfile> = emptyList()
        var after: List<ChildProfile> = emptyList()
        _profiles.update { cur ->
            before = cur
            transform(cur).also { after = it }
        }
        val beforeById = before.associateBy { it.id }
        after.filter { beforeById[it.id] != it }.forEach { p ->
            enqueueProfileWrite { saveProfile(p) }
        }
    }

    /** Lets unit tests wait for all previously enqueued profile writes to finish. */
    internal suspend fun awaitPendingProfileWrites() {
        if (profileStore == null) return
        val done = CompletableDeferred<Unit>()
        profileWrites.send { done.complete(Unit) }
        done.await()
    }

    private fun seedInitialData() {
        val now = Clock.System.now().toEpochMilliseconds()

        // Debug builds seed a dummy "Saga" profile with the tutorial already completed, ending
        // in the same state as finishing it (LixoApp onTutorialCompleted): starter hat unlocked
        // and equipped. Release builds seed no profile ("Ingenting är upplåst första gången;
        // spelaren börjar med en ren bas-avatar utan extra tillbehör" - project.md §2): profiles
        // stays empty, activeProfileId stays null, and nothing is unlocked until the player
        // creates their first profile via FirstProfileSetupScreen. When a [profileStore] exists,
        // the debug Saga seed happens in [loadPersistedProfiles] instead, only if Saga is
        // missing, so a persisted/edited Saga is never duplicated or overwritten.
        if (profileStore == null && isDebugBuild) {
            val saga = buildDebugSagaProfile(now)
            _profiles.value = listOf(saga)
            _unlockedItemIds.value = setOf(tutorialRewardItemId)
            _activeProfileId.value = saga.id
        }

        // Seed Courses (baseline version 1 so Firestore version 2+ triggers delta download)
        val seedTimestamp = 1730000000000L
        _courses.value = listOf(
            Course("course_sv_1", "Svenska Sagor: Viskande Skogen", Subject.SWEDISH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_sv_2", "Svenska Sagor: Kristallbergen", Subject.SWEDISH, minGrade = 2, version = 1, updatedAt = seedTimestamp),
            Course("course_sv_3", "Svenska Sagor: Stjärnborgens Krönikor", Subject.SWEDISH, minGrade = 3, version = 1, updatedAt = seedTimestamp),
            Course("course_math_1", "Matteäventyret: Plus, Minus & Tiokompisar", Subject.MATH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_math_2", "Klocktornet & Guldbörsen: Tiotal, Tid och Pengar", Subject.MATH, minGrade = 1, version = 1, updatedAt = seedTimestamp),
            Course("course_math_3", "Sifferdalens Kluringar: Hundratal, Gåtor & Klockmästaren", Subject.MATH, minGrade = 2, version = 1, updatedAt = seedTimestamp),
            Course("course_en_1", "Engelska Glosresan", Subject.ENGLISH, minGrade = 2, version = 1, updatedAt = seedTimestamp),
            Course("course_geo_1", "Världsresan: Länder & flaggor", Subject.GEOGRAPHY, minGrade = 1, version = 1, updatedAt = seedTimestamp)
        )

        // Seed 190 Campaign Levels (40 Swedish, 100 Math, 20 English, 30 Geography) + Tasks
        val (generatedLevels, generatedTasks) = buildSeedCampaignLevelsAndTasks(content.active.value.dropRules.levelRewards)
        _levels.value = generatedLevels
        _tasksById.value = generatedTasks

        // Seed the plan-014 ladders (48 tiered achievements, see AchievementLadders.kt)
        _achievements.value = buildSeedAchievements(content.active.value.dropRules.achievementRewards)
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
        enqueueProfileWrite { saveActiveProfileId(profileId) }
    }

    /** Creates and activates a new profile; used both for the first-run profile and for siblings. */
    fun createProfile(displayName: String, ageYears: Int): ChildProfile {
        val now = Clock.System.now().toEpochMilliseconds()
        val newProfile = ChildProfile(
            id = "profile_${now}_${displayName.lowercase().hashCode().toUInt()}",
            displayName = displayName.trim().ifBlank { "Syskon" },
            ageYears = ageYears.coerceIn(BalanceConfig.PROFILE_MIN_AGE_YEARS, BalanceConfig.PROFILE_MAX_AGE_YEARS),
            tutorialCompleted = false,
            appearance = AvatarAppearance(avatarId = content.active.value.defaultAvatar.id),
            unlockedAvatarIds = content.active.value.defaultAvatarIds,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        updateProfiles { it + newProfile }
        _activeProfileId.value = newProfile.id
        enqueueProfileWrite { saveActiveProfileId(newProfile.id) }
        return newProfile
    }

    fun updateProfile(updated: ChildProfile) {
        updateProfiles { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
    }

    fun unlockWardrobeItem(itemId: String) {
        val isNew = itemId !in _unlockedItemIds.value
        _unlockedItemIds.update { it + itemId }
        if (isNew) enqueueProfileWrite { saveUnlockedItem(itemId, "REWARD") }
    }

    fun equipItemOnActiveProfile(slot: EquipmentSlot, itemId: String?) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        updateProfiles { list ->
            list.map { profile ->
                if (profile.id != activeId) profile
                else {
                    val nextEquipped = profile.appearance.equipped.toMutableMap()
                    if (itemId == null) {
                        nextEquipped.remove(slot)
                    } else {
                        // 032: hand items are shared, but one item is held in only one hand; equipping it in the other hand moves it.
                        val item = content.active.value.itemsById[itemId]
                        if (_unlockedItemIds.value.contains(itemId) && item != null && slot.accepts(item.slot)) {
                            if (slot.isHand) {
                                val otherHand = if (slot == EquipmentSlot.HAND_LEFT) EquipmentSlot.HAND_RIGHT else EquipmentSlot.HAND_LEFT
                                if (nextEquipped[otherHand] == itemId) nextEquipped.remove(otherHand)
                            }
                            nextEquipped[slot] = itemId
                        }
                    }
                    profile.copy(
                        appearance = profile.appearance.copy(equipped = nextEquipped),
                        updatedAtEpochMillis = now
                    )
                }
            }
        }
        scheduleCardUpsertDebounced()
    }

    fun selectAvatarOnActiveProfile(avatarId: String) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        updateProfiles { list ->
            list.map { profile ->
                if (profile.id != activeId || avatarId !in content.active.value.unlockedAvatarIds(profile.unlockedAvatarIds)) profile
                else profile.copy(
                    appearance = profile.appearance.copy(avatarId = avatarId),
                    updatedAtEpochMillis = now
                )
            }
        }
        scheduleCardUpsertDebounced()
    }

    fun customizeBaseAppearanceOnActiveProfile(
        tintHex: Long?,
        eyeIndex: Int,
        smileIndex: Int
    ) {
        val activeId = _activeProfileId.value ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        updateProfiles { list ->
            list.map { profile ->
                if (profile.id != activeId) profile
                else profile.copy(
                    appearance = profile.appearance.copy(
                        tintHex = tintHex,
                        eyeIndex = eyeIndex,
                        smileIndex = smileIndex
                    ),
                    updatedAtEpochMillis = now
                )
            }
        }
        scheduleCardUpsertDebounced()
    }

    /**
     * doc/plans/030 §4: "own gear goes live" -- 2 s after the equip/avatar/customize calls above,
     * re-upserts the active profile's own friend card (if it has one) so friends see the change.
     * The label is device-only and never part of this payload.
     */
    private fun scheduleCardUpsertDebounced() {
        val activeId = _activeProfileId.value ?: return
        cardUpsertJob?.cancel()
        cardUpsertJob = persistScope.launch {
            delay(2000)
            val profile = _profiles.value.firstOrNull { it.id == activeId } ?: return@launch
            val cardId = profile.friendCardId ?: return@launch
            val nickname = profile.nickname ?: return@launch
            // doc/plans/030 review L4: the debounced push's result used to be discarded, so a
            // failed push (offline, transient error) was silently lost -- the friend's gear would
            // never update for anyone else until the NEXT unrelated equip/avatar change happened
            // to succeed. Mark the profile dirty on failure and retry from [refreshFriends].
            friendCloud.upsertMyCard(cardId, nickname, profile.appearance)
                .onSuccess { dirtyCardProfileIds.remove(activeId) }
                .onFailure { dirtyCardProfileIds.add(activeId) }
        }
    }

    /**
     * doc/plans/030 review L4: retries this profile's own card push if [scheduleCardUpsertDebounced]
     * (or [ensureMyCard]) previously failed. Called from [refreshFriends].
     */
    private suspend fun retryDirtyCardIfAny(profile: ChildProfile) {
        if (profile.id !in dirtyCardProfileIds) return
        val cardId = profile.friendCardId ?: return
        val nickname = profile.nickname ?: return
        friendCloud.upsertMyCard(cardId, nickname, profile.appearance)
            .onSuccess { dirtyCardProfileIds.remove(profile.id) }
    }

    fun saveLevelProgress(progress: LevelProgress) {
        _levelProgressByProfile.update { map ->
            val currentForProfile = map[progress.profileId].orEmpty().toMutableMap()
            currentForProfile[progress.levelId] = progress
            map + (progress.profileId to currentForProfile)
        }
        enqueueProfileWrite { saveLevelProgress(progress) }
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

    /**
     * The same clock [createFriendCode]/[addFriendByCode]/etc. use internally (doc/plans/030 §7),
     * exposed so the UI's invite countdown reads the same time source -- and so instrumented
     * tests that construct this repository with a fake `clockMillis` can fast-forward the
     * countdown deterministically instead of racing a real wall clock.
     */
    fun nowMillis(): Long = clockMillis()

    /**
     * doc/plans/030 §1: the current Firebase Auth uid, signing in anonymously if needed. Cached
     * in [_cloudUid] (and Room, via [ProfileStore.saveCloudUid]) so a failed/offline attempt is
     * retried lazily the next time a friend action needs it, rather than blocking startup.
     * 041 review HIGH 1: also verifies the persisted uid against the real Firebase Auth uid once
     * per process, and resets cloud-bound friend state on a mismatch (see [resetLostCloudIdentity]).
     */
    suspend fun ensureCloudIdentity(): Result<String> {
        // Never resolve the identity before Room is merged: otherwise a fresh sign-in would win the
        // race with loadPersistedProfiles, overwrite the persisted (stale) uid, and leave stale
        // friendCardIds in place with no way to detect the mismatch.
        _profilesLoaded.first { it }
        return cloudIdentityMutex.withLock {
            val cached = _cloudUid.value
            if (cached != null && cloudUidVerified) return@withLock Result.success(cached)
            val result = cloudGateway.ensureSignedIn()
            result.onSuccess { uid ->
                if (cached != null && cached != uid) resetLostCloudIdentity()
                cloudUidVerified = true
                if (cached != uid) {
                    _cloudUid.value = uid
                    enqueueProfileWrite { saveCloudUid(uid) } // AFTER the reset's deletes (FIFO)
                }
            }
            result
        }
    }

    /**
     * 041 review HIGH 1: the persisted cloud uid no longer matches the signed-in Firebase Auth user
     * (Room restored onto a new phone via device transfer, where Auth state is not carried over, or
     * the Auth user was deleted). Firestore rules key every card/reader/tombstone to the old uid, so
     * none of it is usable any more: drop it so [ensureMyCard] creates a fresh card under the real uid.
     * Nicknames are kept (local, generated). Never logs uids. Called only under [cloudIdentityMutex].
     */
    private fun resetLostCloudIdentity() {
        println("LixoProfiles: cloud identity changed; resetting friend cards, friends and tombstones")
        cardUpsertJob?.cancel()
        cardUpsertJob = null
        openInviteByProfile.clear()
        dirtyCardProfileIds.clear()

        val tombstoneKeys = (
            _tombstonedUidsByProfile.value.flatMap { (pid, uids) -> uids.map { pid to it } } +
                pendingRemovalsByProfile.flatMap { (pid, byUid) -> byUid.keys.map { pid to it } }
            ).toSet()
        pendingRemovalsByProfile.clear()
        _tombstonedUidsByProfile.value = emptyMap()
        tombstoneKeys.forEach { (pid, uid) -> enqueueProfileWrite { deleteFriendRemovalTombstone(pid, uid) } }

        val friendKeys = _friendsByProfile.value.flatMap { (pid, list) -> list.map { pid to it.userId } }
        _friendsByProfile.value = emptyMap()
        friendKeys.forEach { (pid, uid) -> enqueueProfileWrite { deleteFriend(pid, uid) } }

        updateProfiles { list -> list.map { if (it.friendCardId != null) it.copy(friendCardId = null) else it } }
    }

    private fun cloudFailure(cause: Throwable): Result<Nothing> =
        Result.failure(cause as? AddFriendException ?: AddFriendException(AddFriendError.NETWORK))

    private fun activeProfileOrNull(): ChildProfile? =
        _activeProfileId.value?.let { id -> _profiles.value.firstOrNull { it.id == id } }

    /**
     * doc/plans/030 §4: lazily creates this profile's friend card (plus a generated nickname, if
     * it doesn't have one yet) and persists `friendCardId`/`nickname` on the profile. A no-op
     * (returns the existing id) once the card exists.
     */
    suspend fun ensureMyCard(): Result<String> = ensureMyCardMutex.withLock {
        // doc/plans/030 review M8: re-read the profile AFTER acquiring the lock. Two concurrent
        // callers (e.g. opening the add-friend sheet re-enters both `createFriendCode` and a
        // deep-link-prefilled `addFriendByCode` at once) would otherwise both observe
        // `friendCardId == null` and each `upsertMyCard(cardId = null, ...)`, creating two cards
        // for the same profile.
        if (activeProfileOrNull() == null) return@withLock Result.failure(IllegalStateException("No active profile"))
        // 041 review HIGH 1: verify identity FIRST -- it may clear a stale (restored) friendCardId.
        ensureCloudIdentity().onFailure { return@withLock cloudFailure(it) }
        val profile = activeProfileOrNull() ?: return@withLock Result.failure(IllegalStateException("No active profile"))
        profile.friendCardId?.let { return@withLock Result.success(it) }

        val nickname = profile.nickname ?: randomNickname(content.active.value.nicknameWords, Random.Default)
        val created = friendCloud.upsertMyCard(cardId = null, nick = nickname, appearance = profile.appearance)
        created.fold(
            onSuccess = { cardId ->
                updateProfiles { list ->
                    list.map { if (it.id == profile.id) it.copy(friendCardId = cardId, nickname = nickname) else it }
                }
                Result.success(cardId)
            },
            onFailure = { cloudFailure(it) }
        )
    }

    /**
     * Opens (or returns the still-open) 10-minute invite window for the active profile's card
     * (doc/plans/030 §4): re-opening the sheet within the window shows the same code.
     */
    suspend fun createFriendCode(): Result<FriendInvite> {
        val activeId = _activeProfileId.value ?: return Result.failure(AddFriendException(AddFriendError.NETWORK))
        openInviteByProfile[activeId]?.let { cached ->
            if (cached.expiresAtEpochMillis > clockMillis()) return Result.success(cached)
        }
        val cardId = ensureMyCard().getOrElse { return cloudFailure(it) }
        return friendCloud.createInvite(cardId).onSuccess { invite -> openInviteByProfile[activeId] = invite }
    }

    /** "Stoppa koden": ends the active profile's open invite window early. */
    suspend fun stopFriendCode() {
        val activeId = _activeProfileId.value ?: return
        val invite = openInviteByProfile.remove(activeId) ?: return
        friendCloud.deleteInvite(invite.code)
    }

    /**
     * Live new friends on *my own* card while the add-friend sheet is open (doc/plans/030 §4): a
     * listener on my card's readers, emitting each newly-seen reader as a [FriendProfile] and
     * inserting it into Room immediately (no accept step).
     */
    fun observeNewFriendsDuring(code: String): Flow<FriendProfile> = flow {
        val activeId = _activeProfileId.value ?: return@flow
        val myCardId = activeProfileOrNull()?.friendCardId ?: return@flow
        val seen = _friendsByProfile.value[activeId].orEmpty().map { it.userId }.toMutableSet()
        friendCloud.observeMyReaders(myCardId).collect { readers ->
            for (reader in readers) {
                if (reader.readerUid in seen) continue
                // doc/plans/030 review M1: a malformed readerCardId (e.g. containing '/') would
                // throw inside `document(...)` deep in the gateway; skip rather than crash.
                if (!isValidFriendCardId(reader.readerCardId)) continue
                seen += reader.readerUid
                val state = friendCloud.observeCard(reader.readerCardId).first()
                val card = (state as? CardState.Present)?.card ?: continue
                val friend = FriendProfile(
                    userId = reader.readerUid,
                    cardId = reader.readerCardId,
                    nick = card.nick,
                    appearance = card.appearance,
                    addedAtEpochMillis = clockMillis(),
                    revealedAtEpochMillis = null
                )
                insertFriendLocally(activeId, friend)
                emit(friend)
            }
        }
    }

    /** Adds a friend from a typed code or a scanned QR payload (doc/plans/030 §4); both go through [parseFriendCode]. */
    suspend fun addFriendByCode(raw: String, fromQr: Boolean): Result<FriendProfile> {
        val code = parseFriendCode(raw) ?: return Result.failure(AddFriendException(AddFriendError.INVALID_QR))
        val activeId = _activeProfileId.value ?: return Result.failure(AddFriendException(AddFriendError.NETWORK))
        val myCardId = ensureMyCard().getOrElse { return cloudFailure(it) }
        val redeemed = friendCloud.redeemInvite(code, myCardId)
        return redeemed.fold(
            onSuccess = { card ->
                val now = clockMillis()
                val friend = FriendProfile(
                    userId = card.ownerUid,
                    cardId = card.cardId,
                    nick = card.nick,
                    appearance = card.appearance,
                    addedAtEpochMillis = now,
                    revealedAtEpochMillis = now
                )
                insertFriendLocally(activeId, friend)
                Result.success(friend)
            },
            onFailure = { cloudFailure(it) }
        )
    }

    /**
     * On Avatar-tab open / app start (doc/plans/030 §4): picks up new readers of my own card
     * (people who redeemed my invite while the sheet was shut) and drops friends whose card the
     * server has confirmed is gone ([CardState.Gone] only -- doc/plans/030 review H5: an offline
     * or otherwise inconclusive read ([CardState.Unknown]) must never delete a friend). Captures
     * the active profile ONCE up front (review M3): previously the `myCardId` lookup after the
     * suspending per-friend loop re-read [activeProfileOrNull], so a profile switch mid-refresh
     * mixed the two profiles' friends. Also retries any still-tombstoned cloud removal (review M2)
     * and any dirty own-card push (review L4).
     */
    suspend fun refreshFriends() {
        _profilesLoaded.first { it }
        // 041 review HIGH 1: verify the (possibly restored) cloud uid before touching any cloud-bound
        // state; on a mismatch this resets friendCardId/friends/tombstones, so re-read them below.
        if (_cloudUid.value != null && ensureCloudIdentity().isFailure) return
        val activeId = _activeProfileId.value ?: return
        val profile = activeProfileOrNull() ?: return
        if (profile.id != activeId) return

        retryDirtyCardIfAny(profile)
        retryPendingFriendRemovals(loadTombstonesFor(activeId))

        val existing = _friendsByProfile.value[activeId].orEmpty()
        for (friend in existing) {
            when (val state = friendCloud.observeCard(friend.cardId).first()) {
                is CardState.Gone -> removeFriendLocally(activeId, friend.userId)
                is CardState.Present -> {
                    val card = state.card
                    if (card.nick != friend.nick || card.appearance != friend.appearance) {
                        updateFriendCardLocally(activeId, friend.userId, card.nick, card.appearance)
                    }
                }
                is CardState.Unknown -> Unit // inconclusive (offline/cache) -- never remove.
            }
            if (activeId != _activeProfileId.value) return // profile switched mid-loop; stop here.
        }

        val myCardId = profile.friendCardId ?: return
        val readers = friendCloud.listMyReaders(myCardId).getOrNull().orEmpty()
        val tombstonedUids = _tombstonedUidsByProfile.value[activeId].orEmpty()
        val knownUids = _friendsByProfile.value[activeId].orEmpty().map { it.userId }.toSet()
        for (reader in readers) {
            if (reader.readerUid in knownUids) continue
            // doc/plans/030 review M2: a uid I just removed (tombstoned) must not be re-added
            // just because its reader doc on my card hasn't been deleted by the cloud yet.
            if (reader.readerUid in tombstonedUids) continue
            if (!isValidFriendCardId(reader.readerCardId)) continue // review M1
            val state = friendCloud.observeCard(reader.readerCardId).first()
            val card = (state as? CardState.Present)?.card ?: continue
            if (activeId != _activeProfileId.value) return
            insertFriendLocally(
                activeId,
                FriendProfile(
                    userId = reader.readerUid,
                    cardId = reader.readerCardId,
                    nick = card.nick,
                    appearance = card.appearance,
                    addedAtEpochMillis = clockMillis(),
                    revealedAtEpochMillis = null
                )
            )
        }
    }

    private fun loadTombstonesFor(profileId: String): List<FriendRemovalTombstone> =
        _tombstonedUidsByProfile.value[profileId].orEmpty().mapNotNull { uid ->
            pendingRemovalsByProfile[profileId]?.get(uid)
        }

    /** In-memory mirror of the tombstone rows, keyed the same way (profileId -> uid -> tombstone). */
    private val pendingRemovalsByProfile = mutableMapOf<String, MutableMap<String, FriendRemovalTombstone>>()

    /**
     * doc/plans/030 review M2: retries the cloud-side [FriendCloud.removeFriend] for every
     * [tombstones] row still present, removing the tombstone (Room + memory) once it succeeds.
     */
    private suspend fun retryPendingFriendRemovals(tombstones: List<FriendRemovalTombstone>) {
        for (tombstone in tombstones) {
            friendCloud.removeFriend(tombstone.myCardId, tombstone.friendUid, tombstone.friendCardId)
                .onSuccess { clearTombstone(tombstone.profileId, tombstone.friendUid) }
        }
    }

    private fun clearTombstone(profileId: String, friendUid: String) {
        pendingRemovalsByProfile[profileId]?.remove(friendUid)
        _tombstonedUidsByProfile.update { current ->
            val set = current[profileId].orEmpty() - friendUid
            current + (profileId to set)
        }
        enqueueProfileWrite { deleteFriendRemovalTombstone(profileId, friendUid) }
    }

    /**
     * Room first, then the gateway (best effort) -- doc/plans/030 §4. doc/plans/030 review M2:
     * records a tombstone BEFORE the fire-and-forget cloud delete, so a crash/offline failure is
     * retried by [refreshFriends] instead of the friend quietly reappearing (replaying the "Ny
     * kompis!" reveal) the next time this device discovers the still-live reader doc.
     */
    fun removeFriend(friendUid: String) {
        val activeId = _activeProfileId.value ?: return
        val profile = activeProfileOrNull()
        val friend = _friendsByProfile.value[activeId]?.firstOrNull { it.userId == friendUid }
        removeFriendLocally(activeId, friendUid)
        val myCardId = profile?.friendCardId
        if (myCardId != null && friend != null) {
            val tombstone = FriendRemovalTombstone(
                profileId = activeId,
                friendUid = friendUid,
                myCardId = myCardId,
                friendCardId = friend.cardId,
                createdAtEpochMillis = clockMillis()
            )
            pendingRemovalsByProfile.getOrPut(activeId) { mutableMapOf() }[friendUid] = tombstone
            _tombstonedUidsByProfile.update { current -> current + (activeId to (current[activeId].orEmpty() + friendUid)) }
            enqueueProfileWrite { saveFriendRemovalTombstone(tombstone) }
            persistScope.launch {
                friendCloud.removeFriend(myCardId, friendUid, friend.cardId)
                    .onSuccess { clearTombstone(activeId, friendUid) }
            }
        }
    }

    /** Room-only, device-local (doc/plans/030 §5): the label never reaches the cloud or a log line. */
    fun setFriendLabel(friendUid: String, label: String?) {
        val activeId = _activeProfileId.value ?: return
        val cleaned = label?.trim()?.take(20)?.ifBlank { null }
        _friendsByProfile.update { current ->
            val list = current[activeId] ?: return@update current
            current + (activeId to list.map { if (it.userId == friendUid) it.copy(label = cleaned) else it })
        }
        enqueueProfileWrite { setFriendLabel(activeId, friendUid, cleaned) }
    }

    /**
     * doc/plans/030 review L1: used to call [ProfileStore.saveFriend] (a `REPLACE` upsert)
     * unconditionally, even when the in-memory guard found the uid already present and left
     * [_friendsByProfile] untouched -- a racing double insert (e.g. [observeNewFriendsDuring] and
     * [refreshFriends] both discovering the same new reader) would then overwrite the
     * already-saved row and wipe its [FriendProfile.label] / [FriendProfile.revealedAtEpochMillis]
     * back to this call's defaults. Only persist when the friend was actually added.
     */
    private fun insertFriendLocally(profileId: String, friend: FriendProfile) {
        var inserted = false
        _friendsByProfile.update { current ->
            val list = current[profileId].orEmpty()
            if (list.any { it.userId == friend.userId }) {
                current
            } else {
                inserted = true
                current + (profileId to (list + friend))
            }
        }
        if (inserted) {
            enqueueProfileWrite { saveFriend(profileId, friend) }
        }
    }

    private fun removeFriendLocally(profileId: String, friendUid: String) {
        _friendsByProfile.update { current ->
            val list = current[profileId] ?: return@update current
            current + (profileId to list.filterNot { it.userId == friendUid })
        }
        enqueueProfileWrite { deleteFriend(profileId, friendUid) }
    }

    private fun updateFriendCardLocally(profileId: String, friendUid: String, nick: Nickname, appearance: AvatarAppearance) {
        _friendsByProfile.update { current ->
            val list = current[profileId] ?: return@update current
            current + (profileId to list.map { if (it.userId == friendUid) it.copy(nick = nick, appearance = appearance) else it })
        }
        enqueueProfileWrite { updateFriendCard(profileId, friendUid, nick, appearance) }
    }

    /**
     * Live friend cards for [profileId] (doc/plans/030 §6): merges each friend's [FriendCloud.observeCard]
     * flow, writes every change through to Room, and re-emits the updated friend list from
     * [_friendsByProfile] so the UI (reading only from Room/this state) works offline with the
     * last known gear. Triggers [ContentRepository.sync] once per session if a friend is wearing
     * an item this device's content pack doesn't know yet.
     */
    fun observeFriendCards(profileId: String): Flow<List<FriendProfile>> {
        val friends = _friendsByProfile.value[profileId].orEmpty()
        if (friends.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return friends.map { friend ->
            friendCloud.observeCard(friend.cardId).map { state -> friend.userId to state }
        }.merge().onEach { (friendUid, state) ->
            // doc/plans/030 review H4/H5: a friend is only ever dropped on a server-confirmed
            // [CardState.Gone], never on [CardState.Unknown] (offline/cache-miss/other error)
            // while this live listener is running.
            when (state) {
                is CardState.Gone -> removeFriendLocally(profileId, friendUid)
                is CardState.Unknown -> Unit
                is CardState.Present -> {
                    val card = state.card
                    updateFriendCardLocally(profileId, friendUid, card.nick, card.appearance)
                    val hasUnknownItem = card.appearance.equipped.values.any { it !in content.active.value.itemsById }
                    if (hasUnknownItem && !didSyncForUnknownFriendItem) {
                        didSyncForUnknownFriendItem = true
                        persistScope.launch { content.sync() }
                    }
                }
            }
        }.map { _friendsByProfile.value[profileId].orEmpty() }
    }

    /** Friends not yet celebrated with the "Ny kompis!" reveal on this device (doc/plans/030 §7). */
    fun unrevealedFriends(profileId: String): Flow<List<FriendProfile>> =
        _friendsByProfile.map { it[profileId].orEmpty().filter { friend -> friend.revealedAtEpochMillis == null } }

    fun markRevealed(profileId: String, friendUid: String) {
        val now = clockMillis()
        _friendsByProfile.update { current ->
            val list = current[profileId] ?: return@update current
            current + (profileId to list.map { if (it.userId == friendUid) it.copy(revealedAtEpochMillis = now) else it })
        }
        enqueueProfileWrite { markFriendRevealed(profileId, friendUid, now) }
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

    /**
     * doc/plans/030 review M4: [CloudSyncGateway.deleteAccountAndAllCloudData] never deleted any
     * `friend_cards` document -- every profile's card (plus its readers/invites) was orphaned in
     * Firestore forever. Delete every profile's card FIRST (it needs the still-live auth session
     * to pass the rules' owner checks), then the account itself.
     */
    suspend fun deleteCloudAccountAndSwitchToLocalOnly(): Result<Unit> {
        for (profile in _profiles.value) {
            profile.friendCardId?.let { cardId -> friendCloud.deleteMyCard(cardId) }
        }
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
     * Downloads public curriculum content (`courses`, `levels`, `tasks`, `lexicon`) from
     * Firestore using delta version checking (`project.md` §3: "Delta-uppdatering via
     * versionscheck; endast ändrade nivåer hämtas"). Safe no-op fallback if offline. The content
     * pack itself is synced separately (024); this only covers courses/levels/tasks/lexicon.
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
            val remoteTasks = sanitizeRemoteFlagTasks(
                cloudGateway.fetchTasksForCourse(coursesToSync.first().id)
                    .filter { it.levelId in syncedLevelIds }
            )

            if (remoteTasks.isNotEmpty()) {
                _tasksById.update { current ->
                    val next = current.toMutableMap()
                    remoteTasks.forEach { task ->
                        // The app owns wardrobe item ids (doc/plans/002); any remote rewardItemId is ignored.
                        val rewardItemId = current[task.id]?.rewardItemId
                            ?: mergedLevelsById[task.levelId]?.order?.let { content.active.value.levelRewardItemId(it) }
                        next[task.id] = task.copy(rewardItemId = rewardItemId)
                    }
                    next
                }
            }

            _courses.value = mergedCoursesById.values.toList()
            _levels.value = mergedLevelsById.values.sortedBy { it.order }
        }

        return coursesToSync.isNotEmpty() || lexiconUpdated
    }

    /**
     * Excludes unsafe flag questions (e.g. bad Firestore data) from remote [HomeworkTask]s
     * before they're merged into [_tasksById] (plan 038). Tasks without flag questions pass
     * through unchanged. A [TaskType.GEOGRAPHY_FLAGS] task that ends up with zero valid
     * questions is dropped entirely (`mapNotNull` -> `null`) so the merge keeps whatever local
     * task already exists (or nothing, if there is none) instead of replacing it with an unusable
     * one. Any other task just gets its junk questions cleaned, never dropped.
     */
    private fun sanitizeRemoteFlagTasks(tasks: List<HomeworkTask>): List<HomeworkTask> {
        val notes = mutableListOf<String>()
        val result = tasks.mapNotNull { task ->
            if (task.type != TaskType.GEOGRAPHY_FLAGS && task.flagQuestions.isEmpty()) {
                return@mapNotNull task
            }
            val total = task.flagQuestions.size
            val valid = GeographyChoices.sanitize(task.flagQuestions)
            val dropped = total - valid.size
            if (task.type == TaskType.GEOGRAPHY_FLAGS && valid.isEmpty()) {
                if (dropped > 0) notes.add("${task.id}: $dropped/$total dropped, kept local task")
                return@mapNotNull null
            }
            if (dropped > 0) notes.add("${task.id}: $dropped/$total dropped")
            task.copy(flagQuestions = valid)
        }
        if (notes.isNotEmpty()) {
            println("LixoCurriculum: WARN invalid flag questions excluded from cloud sync: " + notes.joinToString("; "))
        }
        return result
    }

    companion object {
        const val DEBUG_SAGA_PROFILE_ID = "profile_child_1"

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

        /**
         * Builds the 190 campaign levels + tasks. Swedish (40) and English (20) are interleaved
         * across orders 1..100 (order%5==0 -> English, else even -> math slot, else Swedish);
         * the 100 math levels are built separately from [MathCurriculum] and sit at the even,
         * non-multiple-of-10 orders within 1..100 (n1..n40) plus orders 101..160 (n41..n100). The
         * 30 geography levels are built from [GeographyCurriculum] and sit at orders 161..190.
         */
        fun buildSeedCampaignLevelsAndTasks(
            levelRewards: List<LevelReward>
        ): Pair<List<CampaignLevel>, Map<String, HomeworkTask>> {
            val levels = mutableListOf<CampaignLevel>()
            val tasks = mutableMapOf<String, HomeworkTask>()

            val biomes = MathCurriculum.BIOMES

            // Curated reward item id per campaign level order (doc/plans/002), from the content
            // pack's dropRules.levelRewards.
            val levelRewardByOrder = levelRewards.associate { it.level to it.itemId }

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
                val rewardItemId = levelRewardByOrder[order]
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
                            rewardItemId = rewardItemId
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
                            rewardItemId = rewardItemId
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
                val rewardItemId = levelRewardByOrder[order]

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
                    rewardItemId = rewardItemId
                )
            }

            for ((level, task) in GeographyCurriculum.buildLevelsAndTasks(levelRewardByOrder)) {
                levels.add(level)
                tasks[task.id] = task
            }

            return levels.sortedBy { it.order } to tasks
        }

        /** Titles for the 10 COLLECT_STARS tiers, keyed by target star count (doc/plans/014). */
        private val STAR_ACHIEVEMENT_TITLES_SV: Map<Int, String> = mapOf(
            5 to "Stjärnglimt",
            10 to "Stjärngnista",
            20 to "Stjärnfrö",
            45 to "Stjärnplockare",
            75 to "Stjärnsamlare",
            120 to "Stjärnjägare",
            175 to "Stjärnväktare",
            250 to "Stjärnkapten",
            350 to "Stjärnmästare",
            480 to "Stjärnlegend (alla stjärnor)"
        )

        /** The 4 handmade legacy tiers keep their original hand-written title/description. */
        private val HANDMADE_ACHIEVEMENTS: Map<String, Pair<String, String>> = mapOf(
            "ach_speed_reader_5" to (
                "Blixtläsaren" to
                    "Klara 5 sagor i AI-läge med full 3-stjärnig referenshastighet."
                ),
            "ach_pronunciation_master_10" to (
                "Kristallklart Uttal" to
                    "Läs 10 sagor högt med AI-läsassistenten utan att hoppa över något ord."
                ),
            "ach_math_clock_money" to (
                "Tid- & Pengamästaren" to
                    "Lös 15 uppgifter med Klockan och Pengar på första försöket."
                ),
            "ach_weekly_streak_7" to (
                "Veckans Hjälte (7-dagars Streak)" to
                    "Klara både läsning och matte 7 kalenderdagar i rad för ett legendariskt helset!"
                )
        )

        /** Every achievement id the app build knows about (doc/plans/014), from [ACHIEVEMENT_LADDERS]. */
        fun seedAchievementIds(): Set<String> =
            ACHIEVEMENT_LADDERS.flatMap { (action, tiers) -> tiers.map { achievementId(action, it) } }.toSet()

        /**
         * Seed the plan-014 ladders (48 tiered achievements, see AchievementLadders.kt). All start
         * at 0 progress / locked: progress and unlocks are tracked per-profile
         * ([ChildProfile.achievementProgress] / [ChildProfile.unlockedAchievementIds]), not in this
         * static list. The 4 handmade legacy tiers ([HANDMADE_ACHIEVEMENTS]) keep their original
         * title/description; every other tier gets its copy from [AchievementStrings]. Reward item
         * ids come from the content pack's `dropRules.achievementRewards`.
         */
        fun buildSeedAchievements(achievementRewards: Map<String, String>): List<Achievement> =
            ACHIEVEMENT_LADDERS.flatMap { (action, tiers) ->
                tiers.mapIndexed { i, tier ->
                    val id = achievementId(action, tier)
                    val tierIndex = i + 1
                    val handmade = HANDMADE_ACHIEVEMENTS[id]
                    val titleSv = when {
                        handmade != null -> handmade.first
                        action == AchievementAction.COLLECT_STARS -> STAR_ACHIEVEMENT_TITLES_SV.getValue(tier.target)
                        else -> AchievementStrings.challengeTitleSv(action, tierIndex)
                    }
                    val descriptionSv = when {
                        handmade != null -> handmade.second
                        action == AchievementAction.COLLECT_STARS -> "Samla ${tier.target} stjärnor i kampanjen."
                        else -> AchievementStrings.challengeShortSv(action, tier.target)
                    }
                    Achievement(
                        id = id,
                        titleSv = titleSv,
                        descriptionSv = descriptionSv,
                        rarity = tier.rarity,
                        requiresAiMode = action.requiresAiMode,
                        targetCount = tier.target,
                        currentCount = 0,
                        isUnlocked = false,
                        rewardItemId = achievementRewards[id].orEmpty(),
                        action = action,
                        tier = tierIndex,
                        shortSv = AchievementStrings.challengeShortSv(action, tier.target),
                        spokenSv = AchievementStrings.challengeSpokenSv(action, tier.target, tier.rarity)
                    )
                }
            }
    }
}
