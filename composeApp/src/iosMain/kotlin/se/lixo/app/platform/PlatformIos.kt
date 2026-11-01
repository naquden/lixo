package se.lixo.app.platform

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
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of PlatformFeatureFlags with graceful degradation support
 * as required by project.md §1 ("Om en funktion inte är tillgänglig på en plattform
 * inaktiveras den funktionen på den plattformen (graceful degradation via expect/actual + feature flags)").
 */
actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "iOS"
    actual val isSwedishTtsSupported: Boolean =
        AVSpeechSynthesisVoice.voiceWithLanguage("sv-SE") != null
    actual val isOnDeviceSttSupported: Boolean = true
    actual val isNative3DSceneViewSupported: Boolean = true
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
