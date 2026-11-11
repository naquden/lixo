package se.lixo.app.platform

import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Clock
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTaskHintDictation
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFTranscription
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import se.lixo.app.domain.model.BalanceConfig

/** Builds (lazily) the Swedish `SFSpeechRecognizer`, or null if locale/recognizer creation fails. */
internal fun iosSwedishSpeechRecognizer(lang: String = "sv-SE"): SFSpeechRecognizer? =
    try {
        SFSpeechRecognizer(locale = NSLocale(localeIdentifier = lang))
    } catch (_: Throwable) {
        null
    }

/**
 * iOS speech recognition via `SFSpeechRecognizer` + a single long-lived `AVAudioEngine` input
 * tap for the whole listening session (doc/plans/042 §8). Mirrors the Android state machine
 * (`AndroidSpeechRecognitionEngine`, `PlatformAndroid.kt:199-520`): permission checks, restart
 * delays (100 ms after a final result, 200 ms on "no speech", 1000 ms on other errors), and a
 * proactive restart before Apple's ~1 minute recognition task limit. Only the recognition
 * request/task are recreated on each restart; the audio engine and tap keep running.
 *
 * The constructor never touches `AVAudioSession`, permissions, or the audio engine: tests
 * construct this engine directly, and the test binary has no usage-description strings, so any
 * TCC prompt would abort the process.
 */
class IosSpeechRecognitionEngine : SpeechRecognitionEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val activeProvider: SttProviderRank = SttProviderRank.PLATFORM_NATIVE_SPEECH

    private val _state = MutableStateFlow(SttEngineState.IDLE)
    override val state: StateFlow<SttEngineState> = _state.asStateFlow()

    private val _recognizedTokens = MutableSharedFlow<List<RecognizedSpeechToken>>(extraBufferCapacity = 32)
    override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = _recognizedTokens.asSharedFlow()

    private val _lastLatencyMillis = MutableStateFlow(55L)
    override val lastLatencyMillis: StateFlow<Long> = _lastLatencyMillis.asStateFlow()

    private var recognizer: SFSpeechRecognizer? = null
    private var audioEngine: AVAudioEngine? = null
    private val currentRequest = AtomicReference<SFSpeechAudioBufferRecognitionRequest?>(null)
    private var task: SFSpeechRecognitionTask? = null

    private var generation: Int = 0
    private var shouldKeepListening = false
    private var emittedInUtterance = 0
    private var utteranceStartMillis = 0L
    private var expectedWords: List<String> = emptyList()
    private var upcomingWords: List<String> = emptyList()
    private var currentLanguage = "sv-SE"
    private var restartJob: Job? = null
    private var taskLimitJob: Job? = null
    private val startMutex = Mutex()

    override suspend fun requestMicrophonePermission(): Boolean {
        val speechAuthorized = suspendCancellableCoroutine<Boolean> { cont ->
            SFSpeechRecognizer.requestAuthorization { status ->
                cont.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
        if (!speechAuthorized) {
            _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
            return false
        }
        val micGranted = suspendCancellableCoroutine<Boolean> { cont ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                cont.resume(granted)
            }
        }
        if (!micGranted) {
            _state.value = SttEngineState.ERROR_MIC_PERMISSION_DENIED
            return false
        }
        return true
    }

    // Double-tap guard: ProfileSelectAndTutorialScreen's mic button launches
    // `requestMicrophonePermission()` + `startListening()` on every tap, which can overlap. The
    // mutex serializes starts; the `generation` check below catches a `cancelListening()` (e.g.
    // from onDispose) that ran while this call was suspended inside the permission request.
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun startListening(languageIso: String, expectedWords: List<String>): Unit =
        startMutex.withLock {
            if (audioEngine != null || shouldKeepListening) cancelListening()
            val startGeneration = generation

            val rec = iosSwedishSpeechRecognizer(languageIso)
            if (rec == null || !rec.isAvailable()) {
                _state.value = SttEngineState.ERROR_ENGINE_UNAVAILABLE
                return@withLock
            }
            if (!requestMicrophonePermission()) return@withLock
            if (generation != startGeneration) return@withLock

            recognizer = rec
            currentLanguage = languageIso
            this.expectedWords = expectedWords
            shouldKeepListening = true
            IosAudioSession.activateForRecording()

            val engine = AVAudioEngine()
            val fmt = engine.inputNode.outputFormatForBus(0u)
            if (fmt.sampleRate <= 0.0 || fmt.channelCount == 0u) {
                cancelListening()
                _state.value = SttEngineState.ERROR_ENGINE_UNAVAILABLE
                return@withLock
            }
            engine.inputNode.installTapOnBus(0u, bufferSize = 1024u, format = fmt) { buffer, _ ->
                if (buffer != null) currentRequest.value?.appendAudioPCMBuffer(buffer)
            }
            audioEngine = engine

            engine.prepare()
            if (!engine.startAndReturnError(null)) {
                cancelListening()
                _state.value = SttEngineState.ERROR_ENGINE_UNAVAILABLE
                return@withLock
            }
            beginRecognition()
        }

    private fun beginRecognition() {
        val rec = recognizer ?: return
        _state.value = SttEngineState.INITIALIZING
        emittedInUtterance = 0
        utteranceStartMillis = Clock.System.now().toEpochMilliseconds()
        val gen = ++generation

        val request = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            requiresOnDeviceRecognition = BalanceConfig.STT_PREFER_ON_DEVICE && rec.supportsOnDeviceRecognition
            contextualStrings = buildBiasStrings(expectedWords, upcomingWords)
            addsPunctuation = false
            taskHint = SFSpeechRecognitionTaskHintDictation
        }
        currentRequest.value = request
        task = rec.recognitionTaskWithRequest(request) { result, error ->
            scope.launch { onTaskCallback(gen, result, error) }
        }
        _state.value = SttEngineState.LISTENING

        taskLimitJob?.cancel()
        taskLimitJob = scope.launch {
            delay(BalanceConfig.STT_IOS_TASK_RESTART_MILLIS)
            if (gen == generation) currentRequest.value?.endAudio()
        }
    }

    private fun onTaskCallback(gen: Int, result: SFSpeechRecognitionResult?, error: NSError?) {
        if (gen != generation || !shouldKeepListening) return

        if (result != null) {
            val now = Clock.System.now().toEpochMilliseconds()
            _lastLatencyMillis.value = (now - utteranceStartMillis).coerceIn(20L, 250L)

            val pairs = result.transcriptions.map { (it as SFTranscription).toSegmentPairs() }
            val fresh = iosTranscriptionsToTokens(pairs, emittedInUtterance, now)
            emittedInUtterance = maxOf(
                emittedInUtterance,
                tokenizeSttTranscript(result.bestTranscription.formattedString).size
            )
            if (fresh.isNotEmpty()) {
                _recognizedTokens.tryEmit(fresh)
            }
            if (result.isFinal()) {
                scheduleRestart(100L)
            }
        } else if (error != null) {
            val domain = error.domain
            val code = error.code
            if (domain == "kAFAssistantErrorDomain" && (code == 1110L || code == 203L)) {
                scheduleRestart(200L)
            } else {
                scheduleRestart(1000L)
            }
        }
    }

    private fun scheduleRestart(delayMillis: Long) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMillis)
            if (shouldKeepListening) {
                currentRequest.value?.endAudio()
                task?.cancel()
                beginRecognition()
            }
        }
    }

    override fun updateExpectedPosition(nextWords: List<String>) {
        upcomingWords = nextWords
    }

    override suspend fun stopListening() {
        cancelListening()
    }

    override fun cancelListening() {
        shouldKeepListening = false
        generation++
        restartJob?.cancel()
        restartJob = null
        taskLimitJob?.cancel()
        taskLimitJob = null
        currentRequest.value?.endAudio()
        currentRequest.value = null
        task?.cancel()
        task = null

        audioEngine?.let { engine ->
            engine.stop()
            engine.inputNode.removeTapOnBus(0u)
        }
        audioEngine = null

        IosAudioSession.deactivateRecording()
        upcomingWords = emptyList()
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
