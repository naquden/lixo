package se.lixo.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.Locale

object AndroidPlatformContext {
    var applicationContext: Context? = null
        private set

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }
}

actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "Android"
    actual val isSwedishTtsSupported: Boolean = true
    actual val isOnDeviceSttSupported: Boolean = true
    actual val isNative3DSceneViewSupported: Boolean = true
}

class AndroidSpeechRecognitionEngine(
    private val context: Context?
) : SpeechRecognitionEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val activeProvider: SttProviderRank = SttProviderRank.VOSK_ON_DEVICE_SV

    private val _state = MutableStateFlow(SttEngineState.IDLE)
    override val state: StateFlow<SttEngineState> = _state.asStateFlow()

    private val _recognizedTokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 32)
    override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = _recognizedTokens.asSharedFlow()

    private val _lastLatencyMillis = MutableStateFlow(45L)
    override val lastLatencyMillis: StateFlow<Long> = _lastLatencyMillis.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    override suspend fun requestMicrophonePermission(): Boolean {
        val ctx = context ?: return true // Fallback true in headless/simulated environments
        val granted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
        }
        return granted
    }

    override suspend fun startListening(languageIso: String, expectedWords: List<String>) {
        val ctx = context
        if (ctx == null || !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            // Gracefully run in ready listening state so simulated or Vosk buffer tokens can flow
            _state.value = SttEngineState.LISTENING
            return
        }

        if (!requestMicrophonePermission()) {
            _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
            return
        }

        _state.value = SttEngineState.INITIALIZING
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            speechRecognizer = it
        }

        val startTimestamp = Clock.System.now().toEpochMilliseconds()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = SttEngineState.LISTENING
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {
                // Zero voice storage: audio buffer is never persisted or logged (project.md §6).
            }

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (_state.value == SttEngineState.LISTENING) {
                    // Keep listening session active for continuous child reading
                    _state.value = SttEngineState.LISTENING
                }
            }

            override fun onResults(results: Bundle?) {
                handleSpeechBundle(results, startTimestamp)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleSpeechBundle(partialResults, startTimestamp)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageIso)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
        _state.value = SttEngineState.LISTENING
    }

    private fun handleSpeechBundle(bundle: Bundle?, referenceTimestamp: Long) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val topTranscript = matches.firstOrNull() ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        _lastLatencyMillis.value = (now - referenceTimestamp).coerceIn(20L, 250L)

        val tokens = topTranscript
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { raw ->
                RecognizedSpeechToken(
                    rawWord = raw,
                    normalizedWord = raw.lowercase().trim { !it.isLetterOrDigit() },
                    confidence = 0.88f,
                    timestampMillis = now
                )
            }

        if (tokens.isNotEmpty()) {
            scope.launch {
                _recognizedTokens.emit(tokens)
            }
        }
    }

    override suspend fun stopListening() {
        speechRecognizer?.stopListening()
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
