package se.lixo.app.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import se.lixo.app.data.local.LIXO_MIGRATIONS
import se.lixo.app.data.local.LixoDatabase
import se.lixo.app.domain.engine.TrustedClockAnchor
import se.lixo.app.domain.engine.TrustedClockStore

/**
 * iOS implementation of PlatformFeatureFlags with graceful degradation support
 * as required by project.md §1 ("Om en funktion inte är tillgänglig på en plattform
 * inaktiveras den funktionen på den plattformen (graceful degradation via expect/actual + feature flags)").
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "iOS"
    actual val isSwedishTtsSupported: Boolean =
        AVSpeechSynthesisVoice.voiceWithLanguage("sv-SE") != null
    actual val isOnDeviceSttSupported: Boolean = true
    actual val isNative3DSceneViewSupported: Boolean = true
    actual val isDebugBuild: Boolean = kotlin.native.Platform.isDebugBinary
    actual val appVersionCode: Int =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
}

class IosSpeechRecognitionEngine : SpeechRecognitionEngine {
    override val activeProvider: SttProviderRank = SttProviderRank.PLATFORM_NATIVE_SPEECH

    private val _state = MutableStateFlow(SttEngineState.IDLE)
    override val state: StateFlow<SttEngineState> = _state.asStateFlow()

    private val _recognizedTokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 32)
    override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = _recognizedTokens.asSharedFlow()

    private val _lastLatencyMillis = MutableStateFlow(55L)
    override val lastLatencyMillis: StateFlow<Long> = _lastLatencyMillis.asStateFlow()

    override suspend fun requestMicrophonePermission(): Boolean {
        return true
    }

    override suspend fun startListening(languageIso: String, expectedWords: List<String>) {
        _state.value = SttEngineState.LISTENING
    }

    override suspend fun stopListening() {
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

class IosTextToSpeechEngine : TextToSpeechEngine {
    private val synthesizer = AVSpeechSynthesizer()
    private val svVoice = AVSpeechSynthesisVoice.voiceWithLanguage("sv-SE")

    private val _isAvailable = MutableStateFlow(svVoice != null)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override fun speak(text: String, languageIso: String, speechRate: Float) {
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageIso)
        if (voice == null) {
            // Graceful degradation if voice pack is missing on iOS device
            _isAvailable.value = false
            return
        }
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text).apply {
            this.voice = voice
            this.rate = (speechRate * 0.5f).coerceIn(0.2f, 0.7f)
        }
        _isSpeaking.value = true
        synthesizer.speakUtterance(utterance)
        _isSpeaking.value = false
    }

    override fun stop() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        _isSpeaking.value = false
    }
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; navigation uses on-screen controls.
}

actual fun createPlatformSpeechRecognitionEngine(): SpeechRecognitionEngine =
    IosSpeechRecognitionEngine()

actual fun createPlatformTextToSpeechEngine(): TextToSpeechEngine =
    IosTextToSpeechEngine()

actual fun readDebugContentPreviewFile(): String? = null

// iOS: not implemented yet, so no server time (024's ContentRepository.sync treats this as
// "unavailable" and simply records a null lastServerTimeMillis).
actual suspend fun fetchHttpServerTimeMillis(url: String): Long? = null

private var cachedLixoDatabase: LixoDatabase? = null

@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformLixoDatabase(): LixoDatabase? {
    cachedLixoDatabase?.let { return it }
    val dir = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )?.path ?: return null
    return try {
        Room.databaseBuilder<LixoDatabase>(name = "$dir/lixo.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 022 reset the schema to v1 with no explicit migration yet (see LIXO_MIGRATIONS'
            // KDoc). Existing installs are at v7, so opening at v1 is a DOWNGRADE: both the
            // upgrade and downgrade destructive fallbacks are required.
            .addMigrations(*LIXO_MIGRATIONS)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
            .also { cachedLixoDatabase = it }
    } catch (_: Throwable) {
        null
    }
}

actual fun platformMonotonicMillis(): Long =
    (platform.Foundation.NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

// iOS has no stable boot-id API; a persisted TrustedClockAnchor is therefore never trusted
// across a process restart (see TrustedClock.now()).
actual fun platformBootId(): String? = null

/**
 * iOS implementation of [TrustedClockStore] backed by `NSUserDefaults.standardUserDefaults`
 * (same pattern as [PendingEmailLinkStorage]).
 */
private class IosTrustedClockStore : TrustedClockStore {
    companion object {
        private const val KEY_ANCHOR_SERVER_MILLIS = "lixo_trusted_clock_anchor_server_millis"
        private const val KEY_ANCHOR_MONOTONIC_MILLIS = "lixo_trusted_clock_anchor_monotonic_millis"
        private const val KEY_ANCHOR_BOOT_ID = "lixo_trusted_clock_anchor_boot_id"
        private const val KEY_LAST_TRUSTED_MILLIS = "lixo_trusted_clock_last_trusted_millis"
    }

    override fun loadAnchor(): TrustedClockAnchor? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(KEY_ANCHOR_SERVER_MILLIS) == null ||
            defaults.objectForKey(KEY_ANCHOR_MONOTONIC_MILLIS) == null
        ) {
            return null
        }
        return TrustedClockAnchor(
            serverMillis = defaults.integerForKey(KEY_ANCHOR_SERVER_MILLIS),
            monotonicMillis = defaults.integerForKey(KEY_ANCHOR_MONOTONIC_MILLIS),
            bootId = defaults.stringForKey(KEY_ANCHOR_BOOT_ID)
        )
    }

    override fun saveAnchor(anchor: TrustedClockAnchor) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(anchor.serverMillis, forKey = KEY_ANCHOR_SERVER_MILLIS)
        defaults.setInteger(anchor.monotonicMillis, forKey = KEY_ANCHOR_MONOTONIC_MILLIS)
        if (anchor.bootId != null) {
            defaults.setObject(anchor.bootId, forKey = KEY_ANCHOR_BOOT_ID)
        } else {
            defaults.removeObjectForKey(KEY_ANCHOR_BOOT_ID)
        }
    }

    override fun loadLastTrustedMillis(): Long? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(KEY_LAST_TRUSTED_MILLIS) == null) {
            null
        } else {
            defaults.integerForKey(KEY_LAST_TRUSTED_MILLIS)
        }
    }

    override fun saveLastTrustedMillis(millis: Long) {
        NSUserDefaults.standardUserDefaults.setInteger(millis, forKey = KEY_LAST_TRUSTED_MILLIS)
    }
}

actual fun createPlatformTrustedClockStore(): TrustedClockStore = IosTrustedClockStore()

/**
 * iOS implementation of [PendingEmailLinkStorage] backed by `NSUserDefaults.standardUserDefaults`
 * (`UserDefaults.standard.set(email, forKey: "Email")` in the Apple Firebase Email Link guide).
 */
actual object PendingEmailLinkStorage {
    private const val KEY_SAVED_EMAIL = "Email"

    actual fun saveEmail(email: String) {
        NSUserDefaults.standardUserDefaults.setObject(email.trim(), forKey = KEY_SAVED_EMAIL)
    }

    actual fun getSavedEmail(): String? {
        val stored = NSUserDefaults.standardUserDefaults.stringForKey(KEY_SAVED_EMAIL)
        return stored?.takeIf { it.isNotBlank() }
    }

    actual fun clearSavedEmail() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_SAVED_EMAIL)
    }
}
