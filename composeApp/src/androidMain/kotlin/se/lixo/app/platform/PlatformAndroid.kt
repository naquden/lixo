package se.lixo.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import se.lixo.app.data.local.ContentPackCache
import se.lixo.app.data.local.LIXO_MIGRATIONS
import se.lixo.app.data.local.LixoDatabase
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.local.RoomContentPackCache
import se.lixo.app.data.local.RoomProfileStore
import se.lixo.app.domain.engine.InMemoryTrustedClockStore
import se.lixo.app.domain.engine.TrustedClockAnchor
import se.lixo.app.domain.engine.TrustedClockStore
import se.lixo.app.domain.model.BalanceConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

object AndroidPlatformContext {
    var applicationContext: Context? = null
        private set

    private var recordAudioRequester: (() -> Unit)? = null
    private var pendingRecordAudio: CompletableDeferred<Boolean>? = null

    /**
     * @param requestRecordAudio launches the Activity's runtime permission dialog for RECORD_AUDIO.
     */
    fun init(context: Context, requestRecordAudio: (() -> Unit)? = null) {
        applicationContext = context.applicationContext
        recordAudioRequester = requestRecordAudio
    }

    fun clearPermissionRequester() {
        recordAudioRequester = null
        pendingRecordAudio?.complete(false)
        pendingRecordAudio = null
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        pendingRecordAudio?.complete(granted)
        pendingRecordAudio = null
    }

    /** Returns true if RECORD_AUDIO is granted, showing the system dialog first when needed. */
    suspend fun requestRecordAudioPermission(): Boolean {
        val ctx = applicationContext ?: return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) return true

        val requester = recordAudioRequester ?: return false
        val deferred = pendingRecordAudio ?: CompletableDeferred<Boolean>().also {
            pendingRecordAudio = it
            requester()
        }
        return deferred.await()
    }
}

/**
 * doc/plans/030 §7: the Google code scanner (`play-services-code-scanner`) needs no `CAMERA`
 * permission -- it launches Google Play Services' own full-screen scanning UI and hands back
 * only the decoded text. [AndroidPlatformContext.applicationContext] is enough (no `Activity`
 * reference is required by `GmsBarcodeScanning`).
 */
private class AndroidGmsQrScanner : QrScanner {
    override suspend fun scan(): QrScanResult {
        val ctx = AndroidPlatformContext.applicationContext ?: return QrScanResult.Unavailable
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, options)
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                scanner.startScan()
                    .addOnSuccessListener { barcode ->
                        val raw = barcode.rawValue
                        cont.resume(if (raw != null) QrScanResult.Success(raw) else QrScanResult.Cancelled) {}
                    }
                    .addOnCanceledListener {
                        cont.resume(QrScanResult.Cancelled) {}
                    }
                    .addOnFailureListener {
                        cont.resume(QrScanResult.Unavailable) {}
                    }
            }
        } catch (_: Exception) {
            QrScanResult.Unavailable
        }
    }
}

actual fun createPlatformQrScanner(): QrScanner = AndroidGmsQrScanner()

actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "Android"
    actual val isSwedishTtsSupported: Boolean = true
    actual val isOnDeviceSttSupported: Boolean = true
    actual val isNative3DSceneViewSupported: Boolean = true
    actual val isDebugBuild: Boolean =
        (AndroidPlatformContext.applicationContext?.applicationInfo?.flags ?: 0) and
            ApplicationInfo.FLAG_DEBUGGABLE != 0

    // composeApp is a library module (namespace se.lixo.shared), so there is no
    // BuildConfig.VERSION_CODE for the app itself; read it from the installed package instead.
    actual val appVersionCode: Int = try {
        AndroidPlatformContext.applicationContext?.let { ctx ->
            PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
        } ?: 0
    } catch (_: Throwable) {
        0
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * Debug-only Logcat line (never audio or transcripts, project.md §6 GDPR) for diagnosing which
 * recognizer was chosen and whether/how biasing was applied, only when the app is debuggable.
 */
private fun sttDebugLog(ctx: Context, msg: String) {
    if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        Log.d(AndroidSpeechRecognitionEngine.LOG_TAG, msg)
    }
}

/**
 * Builds the `RecognizerIntent` shared by the cloud and on-device recognizers: the existing
 * language-model/partial-results/calling-package extras, `EXTRA_MAX_RESULTS` for N-best (A), and
 * (API 33+) `EXTRA_BIASING_STRINGS`/`EXTRA_PREFER_OFFLINE` for the expected-vocabulary bias (D).
 * `EXTRA_BIASING_STRINGS` is `@SuppressLint`-guarded as `InlinedApi` since it's only ever read
 * behind the `sdkInt >= 33` check.
 */
@SuppressLint("InlinedApi")
fun buildRecognizerIntent(
    ctx: Context,
    language: String,
    biasStrings: List<String>,
    sdkInt: Int,
    preferOffline: Boolean = false
): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, BalanceConfig.STT_MAX_RESULTS)
    if (sdkInt >= 33 && biasStrings.isNotEmpty()) {
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasStrings))
    }
    if (preferOffline) {
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
}

class AndroidSpeechRecognitionEngine(
    private val context: Context?
) : SpeechRecognitionEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val activeProvider: SttProviderRank = SttProviderRank.PLATFORM_NATIVE_SPEECH

    private val _state = MutableStateFlow(SttEngineState.IDLE)
    override val state: StateFlow<SttEngineState> = _state.asStateFlow()

    private val _recognizedTokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 32)
    override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = _recognizedTokens.asSharedFlow()

    private val _lastLatencyMillis = MutableStateFlow(45L)
    override val lastLatencyMillis: StateFlow<Long> = _lastLatencyMillis.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var shouldKeepListening = false
    private var currentLanguage = "sv-SE"
    private var emittedInUtterance = 0
    private var utteranceStartMillis = 0L
    private var restartJob: Job? = null

    private var expectedWords: List<String> = emptyList()
    private var upcomingWords: List<String> = emptyList()
    private var usingOnDevice = false
    private var onDeviceReadyOnce = false
    private var onDeviceFailed = false

    companion object {
        // Process-lifetime cache; only ever touched on the main thread.
        private val onDeviceSupportByLanguage = mutableMapOf<String, Boolean>()
        private val modelDownloadTriggered = mutableSetOf<String>()
        const val LOG_TAG = "LixoStt"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SttEngineState.LISTENING
            if (usingOnDevice) onDeviceReadyOnce = true
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {
            // Zero voice storage: audio buffer is never persisted or logged (project.md §6).
        }

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!shouldKeepListening) return
            if (usingOnDevice &&
                (error == 12 || error == 13 || (error == SpeechRecognizer.ERROR_CLIENT && !onDeviceReadyOnce))
            ) {
                fallBackToCloudRecognizer()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shouldKeepListening = false
                    _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
                }
                // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 31+)
                12, 13 -> {
                    shouldKeepListening = false
                    _state.value = SttEngineState.ERROR_ENGINE_UNAVAILABLE
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(200L)
                else -> scheduleRestart(1000L)
            }
        }

        override fun onResults(results: Bundle?) {
            handleSpeechBundle(results)
            scheduleRestart(100L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            handleSpeechBundle(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override suspend fun requestMicrophonePermission(): Boolean {
        if (context == null) return true // headless/simulated environments
        val granted = AndroidPlatformContext.requestRecordAudioPermission()
        if (!granted) _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
        return granted
    }

    override suspend fun startListening(languageIso: String, expectedWords: List<String>) {
        val ctx = context
        if (ctx == null) {
            _state.value = SttEngineState.LISTENING
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            _state.value = SttEngineState.ERROR_ENGINE_UNAVAILABLE
            return
        }
        if (!requestMicrophonePermission()) return

        currentLanguage = languageIso
        this.expectedWords = expectedWords
        resolveOnDeviceSupport(ctx, languageIso)
        shouldKeepListening = true
        withContext(Dispatchers.Main.immediate) { beginRecognition(ctx) }
    }

    /** The current recognizer biasing list (the upcoming phrase, then upcoming words, then the rest). */
    fun currentBiasStrings(): List<String> = buildBiasStrings(expectedWords, upcomingWords)

    /** The intent [beginRecognition] would use right now, exposed for tests. */
    fun buildCurrentRecognizerIntent(ctx: Context): Intent =
        buildRecognizerIntent(ctx, currentLanguage, currentBiasStrings(), Build.VERSION.SDK_INT, preferOffline = usingOnDevice)

    override fun updateExpectedPosition(nextWords: List<String>) {
        upcomingWords = nextWords
    }

    /**
     * Chooses between the on-device and cloud recognizer for [language]. Must be called on the
     * main thread -- `SpeechRecognizer` create/check/destroy calls are all main-thread-only.
     */
    fun chooseRecognizer(ctx: Context, language: String = currentLanguage): Pair<SpeechRecognizer, Boolean> {
        val useOnDevice = BalanceConfig.STT_PREFER_ON_DEVICE &&
            !onDeviceFailed &&
            Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) &&
            onDeviceSupportByLanguage[language] == true
        sttDebugLog(ctx, "chooseRecognizer onDevice=$useOnDevice language=$language")
        return if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) to true
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx) to false
        }
    }

    /**
     * Probes (and caches, for the process lifetime) whether the on-device recognizer actually has
     * an installed model for [language]. `supportedOnDeviceLanguages` means downloadable but NOT
     * installed -- using that alone would start a recognizer with no model, so only
     * `installedOnDeviceLanguages` counts as "true"; a supported-but-not-installed language
     * triggers a best-effort download and falls back to the cloud recognizer for this session.
     */
    suspend fun resolveOnDeviceSupport(ctx: Context, language: String): Boolean {
        if (!BalanceConfig.STT_PREFER_ON_DEVICE) return false
        if (Build.VERSION.SDK_INT < 33) return false
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return false
        onDeviceSupportByLanguage[language]?.let { return it }

        return try {
            withContext(Dispatchers.Main.immediate) { probeOnDeviceSupport33(ctx, language) }
        } catch (_: Throwable) {
            onDeviceSupportByLanguage[language] = false
            false
        }
    }

    @TargetApi(33)
    private suspend fun probeOnDeviceSupport33(ctx: Context, language: String): Boolean {
        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        try {
            val intent = buildRecognizerIntent(ctx, language, emptyList(), 33, preferOffline = true)
            val support = withTimeoutOrNull(BalanceConfig.STT_ON_DEVICE_SUPPORT_TIMEOUT_MS) {
                suspendCancellableCoroutine<RecognitionSupport?> { cont ->
                    probe.checkRecognitionSupport(
                        intent,
                        ctx.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (cont.isActive) cont.resume(support)
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }
            }

            val result = when {
                support == null -> false
                languageTagsMatch(language, support.installedOnDeviceLanguages) -> true
                languageTagsMatch(language, support.supportedOnDeviceLanguages) &&
                    language !in modelDownloadTriggered -> {
                    modelDownloadTriggered.add(language)
                    try {
                        probe.triggerModelDownload(intent)
                    } catch (_: Throwable) {
                        // Best-effort only; model download failure doesn't change the fallback.
                    }
                    false
                }
                else -> false
            }
            onDeviceSupportByLanguage[language] = result
            sttDebugLog(ctx, "resolveOnDeviceSupport language=$language onDevice=$result")
            return result
        } finally {
            probe.destroy()
        }
    }

    private fun languageTagsMatch(language: String, tags: List<String>): Boolean {
        val target = language.replace('_', '-').lowercase()
        val targetPrimary = target.substringBefore('-')
        return tags.any { tag ->
            val t = tag.replace('_', '-').lowercase()
            t == target || t.substringBefore('-') == targetPrimary
        }
    }

    private fun fallBackToCloudRecognizer() {
        onDeviceFailed = true
        onDeviceSupportByLanguage[currentLanguage] = false
        usingOnDevice = false
        context?.let { sttDebugLog(it, "fallBackToCloudRecognizer language=$currentLanguage") }

        restartJob?.cancel()
        restartJob = scope.launch {
            delay(100)
            val old = speechRecognizer
            speechRecognizer = null
            old?.cancel()
            old?.destroy()
            if (shouldKeepListening) {
                context?.let { beginRecognition(it) }
            }
        }
    }

    private fun beginRecognition(ctx: Context) {
        _state.value = SttEngineState.INITIALIZING
        emittedInUtterance = 0
        utteranceStartMillis = Clock.System.now().toEpochMilliseconds()

        val recognizer = speechRecognizer ?: chooseRecognizer(ctx).let { (r, onDevice) ->
            r.setRecognitionListener(listener)
            usingOnDevice = onDevice
            onDeviceReadyOnce = false
            speechRecognizer = r
            r
        }

        val bias = currentBiasStrings()
        val intent = buildCurrentRecognizerIntent(ctx)
        sttDebugLog(
            ctx,
            "start onDevice=$usingOnDevice biasing=${Build.VERSION.SDK_INT >= 33 && bias.isNotEmpty()} " +
                "biasCount=${bias.size} phrase=\"${bias.firstOrNull().orEmpty()}\""
        )
        recognizer.startListening(intent)
    }

    private fun scheduleRestart(delayMillis: Long) {
        val ctx = context ?: return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMillis)
            if (shouldKeepListening) {
                speechRecognizer?.cancel()
                beginRecognition(ctx)
            }
        }
    }

    private fun handleSpeechBundle(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (matches.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        _lastLatencyMillis.value = (now - utteranceStartMillis).coerceIn(20L, 250L)

        val total = tokenizeSttTranscript(matches.first()).size
        val fresh = parseSpeechBundle(
            matches,
            bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
            emittedInUtterance,
            now
        )
        emittedInUtterance = maxOf(emittedInUtterance, total)
        if (fresh.isNotEmpty()) {
            _recognizedTokens.tryEmit(fresh)
        }
    }

    override suspend fun stopListening() {
        cancelListening()
    }

    override fun cancelListening() {
        shouldKeepListening = false
        restartJob?.cancel()
        restartJob = null
        speechRecognizer?.let {
            it.cancel()
            it.destroy()
        }
        speechRecognizer = null
        upcomingWords = emptyList()
        usingOnDevice = false
        _state.value = SttEngineState.IDLE
    }

    override suspend fun emitSimulatedWords(words: List<String>, confidence: Float) {
        val now = Clock.System.now().toEpochMilliseconds()
        val tokens = words.map { raw ->
            RecognizedSpeechToken(
                rawWord = raw,
                normalizedWord = raw.lowercase().trim { !it.isLetterOrDigit() },
                confidence = confidence,
                timestampMillis = now
            )
        }
        _recognizedTokens.emit(tokens)
    }
}

class AndroidTextToSpeechEngine(
    context: Context?
) : TextToSpeechEngine {

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null

    init {
        if (context != null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val svLocale = Locale("sv", "SE")
                    val result = tts?.setLanguage(svLocale)
                    _isAvailable.value =
                        result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                    })
                } else {
                    _isAvailable.value = false
                }
            }
        } else {
            // In preview / unit tests without Android Context, mark available for UI state testing
            _isAvailable.value = true
        }
    }

    override fun speak(text: String, languageIso: String, speechRate: Float) {
        val engine = tts
        if (engine == null) {
            _isSpeaking.value = true
            _isSpeaking.value = false
            return
        }
        val parts = languageIso.split("-", "_")
        val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(languageIso)
        engine.language = locale
        engine.setSpeechRate(speechRate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lixo_utt_${System.currentTimeMillis()}")
    }

    override fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }
}

actual fun createPlatformSpeechRecognitionEngine(): SpeechRecognitionEngine =
    AndroidSpeechRecognitionEngine(AndroidPlatformContext.applicationContext)

actual fun createPlatformTextToSpeechEngine(): TextToSpeechEngine =
    AndroidTextToSpeechEngine(AndroidPlatformContext.applicationContext)

private var cachedLixoDatabase: LixoDatabase? = null

private fun buildLixoDatabase(ctx: Context, fileName: String): LixoDatabase =
    Room.databaseBuilder<LixoDatabase>(
        context = ctx,
        name = ctx.getDatabasePath(fileName).absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // 022 reset the schema to v1 with no explicit migration yet (see LIXO_MIGRATIONS' KDoc).
        // Existing installs are at v7, so opening at v1 is a DOWNGRADE: both the upgrade and
        // downgrade destructive fallbacks are required, or Room throws on the first query.
        .addMigrations(*LIXO_MIGRATIONS)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

actual fun createPlatformLixoDatabase(): LixoDatabase? {
    cachedLixoDatabase?.let { return it }
    val ctx = AndroidPlatformContext.applicationContext ?: return null
    return try {
        buildLixoDatabase(ctx, "lixo.db").also { cachedLixoDatabase = it }
    } catch (_: Throwable) {
        null
    }
}

/**
 * For instrumented tests only: builds a [ProfileStore] backed by a separate Room database file
 * (not "lixo.db" and not cached), so a test never reads or writes the real app database.
 * androidApp has no Room on its androidTest classpath, so this returns the [ProfileStore]
 * interface rather than [LixoDatabase] / `LixoDao` directly.
 */
fun createAndroidProfileStore(context: Context, dbFileName: String): ProfileStore =
    RoomProfileStore(buildLixoDatabase(context.applicationContext, dbFileName).lixoDao())

/**
 * For instrumented tests only (same rationale as [createAndroidProfileStore]): builds a
 * [ContentPackCache] backed by a separate Room database file.
 */
fun createAndroidContentPackCache(context: Context, dbFileName: String): ContentPackCache =
    RoomContentPackCache(buildLixoDatabase(context.applicationContext, dbFileName).contentPackDao())

actual suspend fun fetchHttpServerTimeMillis(url: String): Long? = withContext(Dispatchers.IO) {
    try {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
            try {
                responseCode
                date.takeIf { it > 0L }
            } finally {
                disconnect()
            }
        }
    } catch (_: Throwable) {
        null
    }
}

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes
}

actual fun readDebugContentPreviewFile(): String? = try {
    AndroidPlatformContext.applicationContext
        ?.filesDir
        ?.resolve("content_pack_preview.json")
        ?.takeIf { it.isFile }
        ?.readText()
} catch (_: Throwable) {
    null
}

// The androidHostTest JVM throws "Method elapsedRealtime not mocked" against the android.jar
// stubs, so this must never be called unguarded from ContentRepository's default TrustedClock().
actual fun platformMonotonicMillis(): Long = try {
    android.os.SystemClock.elapsedRealtime()
} catch (_: Throwable) {
    System.nanoTime() / 1_000_000
}

actual fun platformBootId(): String? =
    AndroidPlatformContext.applicationContext
        ?.let { ctx -> runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull() }
        ?.takeIf { it >= 0 }
        ?.toString()

private class AndroidTrustedClockStore(private val context: Context) : TrustedClockStore {
    companion object {
        private const val PREFS_NAME = "lixo_trusted_clock"
        private const val KEY_ANCHOR_SERVER_MILLIS = "anchor_server_millis"
        private const val KEY_ANCHOR_MONOTONIC_MILLIS = "anchor_monotonic_millis"
        private const val KEY_ANCHOR_BOOT_ID = "anchor_boot_id"
        private const val KEY_LAST_TRUSTED_MILLIS = "last_trusted_millis"
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAnchor(): TrustedClockAnchor? {
        val p = prefs()
        if (!p.contains(KEY_ANCHOR_SERVER_MILLIS) || !p.contains(KEY_ANCHOR_MONOTONIC_MILLIS)) return null
        return TrustedClockAnchor(
            serverMillis = p.getLong(KEY_ANCHOR_SERVER_MILLIS, 0L),
            monotonicMillis = p.getLong(KEY_ANCHOR_MONOTONIC_MILLIS, 0L),
            bootId = p.getString(KEY_ANCHOR_BOOT_ID, null)
        )
    }

    override fun saveAnchor(anchor: TrustedClockAnchor) {
        prefs().edit()
            .putLong(KEY_ANCHOR_SERVER_MILLIS, anchor.serverMillis)
            .putLong(KEY_ANCHOR_MONOTONIC_MILLIS, anchor.monotonicMillis)
            .putString(KEY_ANCHOR_BOOT_ID, anchor.bootId)
            .apply()
    }

    override fun loadLastTrustedMillis(): Long? {
        val p = prefs()
        return if (p.contains(KEY_LAST_TRUSTED_MILLIS)) p.getLong(KEY_LAST_TRUSTED_MILLIS, 0L) else null
    }

    override fun saveLastTrustedMillis(millis: Long) {
        prefs().edit().putLong(KEY_LAST_TRUSTED_MILLIS, millis).apply()
    }
}

actual fun createPlatformTrustedClockStore(): TrustedClockStore =
    AndroidPlatformContext.applicationContext?.let { AndroidTrustedClockStore(it) } ?: InMemoryTrustedClockStore()

actual object PendingEmailLinkStorage {
    private const val PREFS_NAME = "lixo_email_link_auth_prefs"
    private const val KEY_SAVED_EMAIL = "saved_email_for_sign_in"
    private var inMemoryFallbackEmail: String? = null

    actual fun saveEmail(email: String) {
        val cleaned = email.trim()
        inMemoryFallbackEmail = cleaned
        val ctx = AndroidPlatformContext.applicationContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_EMAIL, cleaned)
            .apply()
    }

    actual fun getSavedEmail(): String? {
        val ctx = AndroidPlatformContext.applicationContext
        if (ctx != null) {
            val stored = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVED_EMAIL, null)
            if (!stored.isNullOrBlank()) return stored
        }
        return inMemoryFallbackEmail
    }

    actual fun clearSavedEmail() {
        inMemoryFallbackEmail = null
        val ctx = AndroidPlatformContext.applicationContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SAVED_EMAIL)
            .apply()
    }
}

/** doc/plans/030 §7: Android's "remove animations" accessibility setting. */
actual fun isReducedMotionEnabled(): Boolean {
    val ctx = AndroidPlatformContext.applicationContext ?: return false
    return runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}
