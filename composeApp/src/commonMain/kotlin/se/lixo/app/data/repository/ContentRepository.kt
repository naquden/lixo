package se.lixo.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import se.lixo.app.data.content.BundledContentPack
import se.lixo.app.data.local.ContentPackCache
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentPackChunks
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.SUPPORTED_SCHEMA_VERSION
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.engine.TrustedClock
import se.lixo.app.platform.PlatformFeatureFlags

/**
 * Holds the app's currently-[active] content pack (022). The flow stays a mutable
 * [MutableStateFlow] rather than a plain value so that 024 can hot-swap the pack after a
 * cloud sync without restarting the app.
 *
 * ## Publish flow (024)
 * A new pack is published with `tools/content_pipeline/pack/build_pack.py --version N`, then
 * `tools/content_pipeline/pack/upload_pack.py` (which re-validates it, runs the stability diff
 * against the last published pack, then writes its chunks to Firestore `content_chunks/{schema}_{N}_{i}`
 * first and the `content/meta` doc last). The app then, on startup and on resume, fetches
 * `content/meta`, downloads the chunks, checks their joined sha256 against the meta entry, runs
 * [ContentPackValidator] against the currently-active pack, caches the verified JSON to Room, and
 * swaps [active] -- no reinstall needed. A run already in progress keeps its frozen snapshot
 * (023) because nothing but [sync]/[syncIfDue] ever writes to [_active].
 */
class ContentRepository(
    initial: ActiveContent,
    private val gateway: CloudSyncGateway? = null,
    private val cache: ContentPackCache? = null,
    private val appAchievementIds: () -> Set<String> = BundledContentPack::appAchievementIds,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val trustedClock: TrustedClock = TrustedClock()
) {

    private val _active = MutableStateFlow(initial)
    val active: StateFlow<ActiveContent> = _active.asStateFlow()

    private val syncMutex = Mutex()
    private var lastSyncAttemptMillis: Long? = null

    /**
     * The server wall-clock time last reported by [CloudSyncGateway.fetchContentMeta]. Also fed
     * into [trustedClock] (025), which is what [se.lixo.app.domain.engine.EventEngine] actually
     * reads to decide whether a holiday special is active.
     */
    var lastServerTimeMillis: Long? = null
        private set

    companion object {
        /** Minimum time between two [syncIfDue] network attempts: avoids a startup+resume double fetch. */
        const val SYNC_MIN_INTERVAL_MILLIS = 15 * 60 * 1000L

        /**
         * Validates [json] and builds a [ContentRepository] from it.
         *
         * Accepted: every skipped entry is logged as a WARN and the result is built from the
         * (possibly partially-skipped) pack. Rejected: in a debug build this is a hard error (the
         * bundled pack must always be valid); in a release build it is logged and the app falls
         * back to [SampleContentPack.activeContent].
         */
        fun fromJson(
            json: String,
            isDebugBuild: Boolean,
            appAchievementIds: () -> Set<String> = BundledContentPack::appAchievementIds
        ): ContentRepository {
            return when (val result = BundledContentPack.validate(json, appAchievementIds())) {
                is ValidationResult.Accepted -> {
                    for (skip in result.skipped) {
                        println("LixoContent: WARN bundled skip $skip")
                    }
                    ContentRepository(ActiveContent.from(result.pack))
                }
                is ValidationResult.Rejected -> {
                    if (isDebugBuild) {
                        error("Bundled content pack rejected: ${result.reason}")
                    }
                    println("LixoContent: ERROR bundled content pack rejected: ${result.reason}")
                    ContentRepository(SampleContentPack.activeContent())
                }
            }
        }

        /** Loads and validates the bundled pack's JSON, off the main thread. */
        suspend fun loadBundled(isDebugBuild: Boolean = PlatformFeatureFlags().isDebugBuild): ContentRepository =
            withContext(Dispatchers.Default) {
                fromJson(BundledContentPack.readJson(), isDebugBuild)
            }

        /**
         * Builds the initial [ContentRepository] for app startup (024): starts from the bundled
         * pack, then -- if [cache] has a cached pack whose `version` is strictly newer than the
         * bundled one and that still validates against it (no removed item ids) -- uses the
         * cache instead. A cache from before an app update (older than the new bundled version,
         * or missing an item the new bundled pack ships) always loses.
         */
        suspend fun loadInitial(
            gateway: CloudSyncGateway?,
            cache: ContentPackCache?,
            isDebugBuild: Boolean = PlatformFeatureFlags().isDebugBuild,
            bundledJson: suspend () -> String = { BundledContentPack.readJson() },
            appAchievementIds: () -> Set<String> = BundledContentPack::appAchievementIds,
            trustedClock: TrustedClock = TrustedClock()
        ): ContentRepository = withContext(Dispatchers.Default) {
            val bundled = fromJson(bundledJson(), isDebugBuild, appAchievementIds).active.value

            val cachedJson = try {
                cache?.load()
            } catch (_: Throwable) {
                null
            }

            val initial = if (cachedJson != null) {
                when (
                    val result = ContentPackValidator.validate(
                        cachedJson,
                        appAchievementIds(),
                        previous = bundled,
                        requireNewerVersion = true
                    )
                ) {
                    is ValidationResult.Accepted -> ActiveContent.from(result.pack)
                    is ValidationResult.Rejected -> {
                        println("LixoContent: cache ignored: ${result.reason}")
                        bundled
                    }
                }
            } else {
                bundled
            }

            ContentRepository(initial, gateway, cache, appAchievementIds, trustedClock = trustedClock)
        }
    }

    /**
     * Downloads, verifies and hot-swaps a newer content pack, if the cloud has one. Returns
     * true only if [active] actually changed. Safe to call repeatedly / concurrently: a sync
     * already in progress makes a concurrent call return false immediately instead of queuing.
     */
    suspend fun sync(): Boolean {
        val gw = gateway ?: return false
        if (!syncMutex.tryLock()) return false
        try {
            return doSync(gw)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            println("LixoContent: sync failed: ${t.message}")
            return false
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun doSync(gw: CloudSyncGateway): Boolean {
        lastSyncAttemptMillis = nowMillis()

        val meta = gw.fetchContentMeta() ?: return false
        meta.serverTimeMillis?.let { lastServerTimeMillis = it; trustedClock.onServerTime(it) }

        if (meta.schemaVersion != SUPPORTED_SCHEMA_VERSION) return false

        val current = _active.value
        if (meta.entry.version <= current.version) return false

        if (meta.entry.chunkCount !in 1..ContentPackChunks.MAX_CHUNKS) return false

        val chunks = gw.fetchContentChunks(SUPPORTED_SCHEMA_VERSION, meta.entry.version, meta.entry.chunkCount)
            ?: return false
        if (chunks.size != meta.entry.chunkCount) return false

        val newActive = withContext(Dispatchers.Default) {
            val json = ContentPackChunks.joinVerified(chunks, meta.entry.sha256) ?: run {
                println("LixoContent: sync rejected: sha256 mismatch")
                return@withContext null
            }
            when (val result = ContentPackValidator.validate(json, appAchievementIds(), previous = current)) {
                is ValidationResult.Rejected -> {
                    println("LixoContent: sync rejected: ${result.reason}")
                    null
                }
                is ValidationResult.Accepted -> {
                    if (result.pack.version != meta.entry.version) {
                        println(
                            "LixoContent: sync rejected: pack version ${result.pack.version} != meta version ${meta.entry.version}"
                        )
                        null
                    } else {
                        ActiveContent.from(result.pack)
                    }
                }
            }
        } ?: return false

        try {
            cache?.replace(SUPPORTED_SCHEMA_VERSION, meta.entry.version, meta.entry.sha256, chunks)
        } catch (t: Throwable) {
            println("LixoContent: cache write failed: ${t.message}")
        }

        _active.value = newActive
        return true
    }

    /** [sync], throttled to at most once per [SYNC_MIN_INTERVAL_MILLIS] (e.g. app-resume). */
    suspend fun syncIfDue(): Boolean {
        val last = lastSyncAttemptMillis
        if (last != null && nowMillis() - last < SYNC_MIN_INTERVAL_MILLIS) return false
        return sync()
    }
}
