package se.lixo.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform feature flags for graceful degradation as specified in project.md §1:
 * "Om en funktion inte är tillgänglig på en plattform (t.ex. TTS på iOS) inaktiveras
 * den funktionen på den plattformen (graceful degradation via expect/actual + feature flags)
 * i stället för att blockera releasen."
 */
expect class PlatformFeatureFlags() {
    val platformName: String
    val isSwedishTtsSupported: Boolean
    val isOnDeviceSttSupported: Boolean
    val isNative3DSceneViewSupported: Boolean
    val isDebugBuild: Boolean

    /**
     * The installed app's version code: Android `versionCode` / iOS `CFBundleVersion`, or `0`
     * when unknown (e.g. no Android `Context`, or an unparsable iOS bundle value). Currently
     * unused by content-pack code; reserved for content-pack version/compatibility sync (024).
     */
    val appVersionCode: Int
}

enum class SttEngineState {
    IDLE,
    INITIALIZING,
    LISTENING,
    PAUSED,
    ERROR_MIC_PERMISSION_DENIED,
    ERROR_ENGINE_UNAVAILABLE
}

data class RecognizedSpeechToken(
    val rawWord: String,
    val normalizedWord: String,
    val confidence: Float,
    val timestampMillis: Long,
    /** Normalized words at this same position from lower-ranked N-best transcripts; matching only. */
    val alternatives: List<String> = emptyList()
)

/**
 * Ranked STT engine candidates for prototype evaluation (project.md §2 & §8).
 */
enum class SttProviderRank(val priority: Int, val displayName: String, val isOnDevice: Boolean) {
    VOSK_ON_DEVICE_SV(1, "Vosk Offline Swedish Small", isOnDevice = true),
    KBLAB_WAV2VEC2_ONNX(2, "KBLab wav2vec2 Swedish ONNX", isOnDevice = true),
    WHISPER_CPP_TINY_SV(3, "Whisper.cpp Quantized Swedish", isOnDevice = true),
    PLATFORM_NATIVE_SPEECH(4, "Platform Native SpeechRecognizer", isOnDevice = true),
    SIMULATED_DETERMINISTIC(5, "Simulated Realtime Reader (Demo/Test)", isOnDevice = true)
}

/**
 * Contract for real-time speech recognition feeding into ForcedAlignmentEngine.
 * Audio is processed strictly in memory and discarded immediately (project.md §6 "Ingen röstlagring").
 */
interface SpeechRecognitionEngine {
    val activeProvider: SttProviderRank
    val state: StateFlow<SttEngineState>
    val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>>
    val lastLatencyMillis: StateFlow<Long>

    suspend fun requestMicrophonePermission(): Boolean
    suspend fun startListening(languageIso: String = "sv-SE", expectedWords: List<String>)
    suspend fun stopListening()

    /** Non-suspending teardown, safe to call from `onDispose`. */
    fun cancelListening() {}

    /**
     * Allows feeding simulated spoken words (for emulator testing or demo mode).
     */
    suspend fun emitSimulatedWords(words: List<String>, confidence: Float = 0.95f)

    /**
     * The upcoming unread normalized words from the reading cursor, used to bias the recognizer's
     * expected vocabulary at its next (re)start. No-op by default so iOS and fakes compile unchanged.
     */
    fun updateExpectedPosition(nextWords: List<String>) {}
}

/**
 * Contract for Swedish and English Text-To-Speech (project.md §2 "TTS: Appen måste ha svensk röst/tal").
 * Supports graceful degradation if unavailable on a specific device/OS build.
 */
interface TextToSpeechEngine {
    val isAvailable: StateFlow<Boolean>
    val isSpeaking: StateFlow<Boolean>

    fun speak(text: String, languageIso: String = "sv-SE", speechRate: Float = 0.92f)
    fun stop()
}

/** No-op [TextToSpeechEngine], used as the default where a real engine isn't wired in (e.g. tests). */
object NoOpTextToSpeechEngine : TextToSpeechEngine {
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)
    override fun speak(text: String, languageIso: String, speechRate: Float) {}
    override fun stop() {}
}

/** Handles the system back gesture/button where the platform has one (no-op on iOS). */
@androidx.compose.runtime.Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

expect fun createPlatformSpeechRecognitionEngine(): SpeechRecognitionEngine
expect fun createPlatformTextToSpeechEngine(): TextToSpeechEngine

/**
 * Builds the platform's Room database, or `null` when there is no platform context to bind it
 * to (e.g. the androidHostTest JVM unit tests, where there is no `Context`). Callers must treat
 * `null` as "fall back to in-memory state" rather than throwing.
 */
expect fun createPlatformLixoDatabase(): se.lixo.app.data.local.LixoDatabase?

/**
 * The server's wall-clock time (1 s precision) read from the HTTP `Date` header of a lightweight
 * request to [url], or null if offline, unsupported, or the request fails. Used by
 * `ContentRepository.sync` (024) to feed 025's TrustedClock; GitLive's Firestore SDK exposes no
 * document read-time, so this HTTP fallback is the only cross-platform source.
 */
expect suspend fun fetchHttpServerTimeMillis(url: String): Long?

/**
 * The debug content-pack preview override written to `<filesDir>/content_pack_preview.json`
 * (`doc/plans/021-content-pack-authoring-pipeline.md`'s README preview-from-a-file flow), or
 * null if it doesn't exist / there is no platform `Context` (iOS: always null).
 */
expect fun readDebugContentPreviewFile(): String?

/**
 * A monotonic clock reading in milliseconds, for [se.lixo.app.domain.engine.TrustedClock]:
 * Android `SystemClock.elapsedRealtime()` (counts across sleep), iOS `NSProcessInfo.systemUptime`
 * (does not count across sleep, but that only matters when there is an anchor to carry across
 * one, and this process never anchors on iOS -- see `PlatformIos.kt`).
 */
expect fun platformMonotonicMillis(): Long

/**
 * An opaque id for the current boot, stable across app restarts within the same boot and
 * different across a reboot, or null when unknown (Android: `Settings.Global.BOOT_COUNT`; iOS:
 * always null). Only when non-null is a persisted [se.lixo.app.domain.engine.TrustedClockAnchor]
 * trusted across a process restart.
 */
expect fun platformBootId(): String?

/** Builds the platform's [se.lixo.app.domain.engine.TrustedClockStore]. */
expect fun createPlatformTrustedClockStore(): se.lixo.app.domain.engine.TrustedClockStore

/**
 * [size] cryptographically-secure random bytes (doc/plans/030 §4): the friend invite code must
 * not be guessable with `kotlin.random.Random`'s non-CSPRNG default, since it is the only gate on
 * a no-accept-step friendship. Android: `java.security.SecureRandom`. iOS: `SecRandomCopyBytes`.
 */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Local persistence for the user's email address when initiating Firebase Email Link Authentication.
 * Required by both Android (`SharedPreferences`) and Apple (`NSUserDefaults`) Email Link Auth guides
 * so the user does not have to re-type their email when opening the sign-in link on the same device.
 */
expect object PendingEmailLinkStorage {
    fun saveEmail(email: String)
    fun getSavedEmail(): String?
    fun clearSavedEmail()
}

/** Result of one [QrScanner.scan] attempt (doc/plans/030 §7). */
sealed interface QrScanResult {
    /** [raw] is the decoded QR payload, not yet parsed into a friend code. */
    data class Success(val raw: String) : QrScanResult
    data object Cancelled : QrScanResult
    /** No scanner available on this platform/device (e.g. iOS today, or Play Services missing). */
    data object Unavailable : QrScanResult
}

/**
 * Cross-platform QR scanner contract (doc/plans/030 §7), provided to [se.lixo.app.ui.screens.social.AvatarMapScreen]
 * as a plain constructor parameter (not a composition local) so instrumented tests can pass a
 * fake implementation instead of [createPlatformQrScanner]. Android uses the Google code scanner
 * (`play-services-code-scanner`), which needs no `CAMERA` permission; iOS always returns
 * [QrScanResult.Unavailable] (no iOS scanner in scope for 030).
 */
interface QrScanner {
    suspend fun scan(): QrScanResult
}

/** Builds the platform's real [QrScanner]. */
expect fun createPlatformQrScanner(): QrScanner

/**
 * Cross-platform dispatcher receiving incoming Firebase Email Link deep links from:
 * - Android App Links (`MainActivity.onCreate` / `MainActivity.onNewIntent`)
 * - Apple Universal Links & custom URLs (`iOSApp.swift` / `AppDelegate` / `MainViewController.kt`)
 */
object EmailLinkAuthDispatcher {
    private val _incomingLink = MutableStateFlow<String?>(null)
    val incomingLink: StateFlow<String?> = _incomingLink.asStateFlow()

    fun onIncomingLink(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val looksLikeEmailSignInLink =
            trimmed.contains("oobCode=") ||
                trimmed.contains("/__/auth/links") ||
                trimmed.contains("/__/auth/action") ||
                trimmed.contains("finishSignUp")
        if (!looksLikeEmailSignInLink) return false
        _incomingLink.value = trimmed
        return true
    }

    fun consumeIncomingLink() {
        _incomingLink.value = null
    }
}

/**
 * Cross-platform dispatcher for the `lixo://friend/<code>` deep link (doc/plans/030 §7): a
 * [android.content.Intent] `VIEW`/`DEFAULT`/`BROWSABLE` filter on `MainActivity` only *routes*
 * the Avatar tab open with the code prefilled into the "Lägg till kompis" tab — nothing is ever
 * redeemed automatically. [se.lixo.app.ui.LixoApp] observes [incomingFriendCode] and clears it
 * via [consumeIncomingFriendCode] once the code has been handed to `AvatarMapScreen`.
 */
object FriendDeepLinkDispatcher {
    private val _incomingFriendCode = MutableStateFlow<String?>(null)
    val incomingFriendCode: StateFlow<String?> = _incomingFriendCode.asStateFlow()

    /** [raw] is the whole `lixo://friend/<code>` link; parsing happens downstream via `parseFriendCode`. */
    fun onIncomingLink(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.lowercase().startsWith("lixo://friend/")) return false
        _incomingFriendCode.value = trimmed
        return true
    }

    fun consumeIncomingFriendCode() {
        _incomingFriendCode.value = null
    }
}

/**
 * doc/plans/030 §7: whether the system "remove animations" setting is on, so [se.lixo.app.ui.LixoApp]
 * can provide [se.lixo.app.ui.feedback.LocalReducedMotion] at the root. Android: the
 * `ANIMATOR_DURATION_SCALE` system setting is `0`. iOS: `UIAccessibilityIsReduceMotionEnabled()`.
 */
expect fun isReducedMotionEnabled(): Boolean
