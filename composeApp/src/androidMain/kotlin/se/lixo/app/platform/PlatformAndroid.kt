package se.lixo.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import se.lixo.app.data.local.LIXO_MIGRATIONS
import se.lixo.app.data.local.LixoDatabase
import java.util.Locale

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

actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "Android"
    actual val isSwedishTtsSupported: Boolean = true
    actual val isOnDeviceSttSupported: Boolean = true
    actual val isNative3DSceneViewSupported: Boolean = true
    actual val isDebugBuild: Boolean =
        (AndroidPlatformContext.applicationContext?.applicationInfo?.flags ?: 0) and
            ApplicationInfo.FLAG_DEBUGGABLE != 0
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
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

    private val listener = object : RecognitionListener {
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
            if (!shouldKeepListening) return
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
        shouldKeepListening = true
        withContext(Dispatchers.Main.immediate) { beginRecognition(ctx) }
    }

    private fun beginRecognition(ctx: Context) {
        _state.value = SttEngineState.INITIALIZING
        emittedInUtterance = 0
        utteranceStartMillis = Clock.System.now().toEpochMilliseconds()

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            speechRecognizer = it
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
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
        val topTranscript = matches.firstOrNull() ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        _lastLatencyMillis.value = (now - utteranceStartMillis).coerceIn(20L, 250L)

        val allTokens = topTranscript
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

        // Partial results are cumulative for the utterance; only forward words not yet delivered.
        val fresh = allTokens.drop(emittedInUtterance)
        emittedInUtterance = maxOf(emittedInUtterance, allTokens.size)
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

actual fun createPlatformLixoDatabase(): LixoDatabase? {
    cachedLixoDatabase?.let { return it }
    val ctx = AndroidPlatformContext.applicationContext ?: return null
    return try {
        Room.databaseBuilder<LixoDatabase>(
            context = ctx,
            name = ctx.getDatabasePath("lixo.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // Every LixoDatabase version bump needs a Migration in LIXO_MIGRATIONS (no destructive fallback).
            .addMigrations(*LIXO_MIGRATIONS)
            .build()
            .also { cachedLixoDatabase = it }
    } catch (_: Throwable) {
        null
    }
}

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
