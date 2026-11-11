package se.lixo.app.platform

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject

/**
 * iOS text-to-speech via `AVSpeechSynthesizer`. Not verified on the simulator (simulator audio
 * is unreliable); only compiled and checked for `isAvailable`. `isSpeaking` is driven by
 * [TtsDelegate] rather than set synchronously around `speakUtterance`, since speech is async.
 */
class IosTextToSpeechEngine : TextToSpeechEngine {
    private val synthesizer = AVSpeechSynthesizer()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Held as a strong field: AVSpeechSynthesizer.delegate is a weak reference.
    private val delegate = TtsDelegate { speaking -> _isSpeaking.value = speaking }

    init {
        synthesizer.delegate = delegate
    }

    private val _isAvailable = MutableStateFlow(bestVoice("sv-SE") != null)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private fun bestVoice(lang: String): AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.equals(lang, ignoreCase = true) }
            .maxByOrNull { it.quality }
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(lang)

    override fun speak(text: String, languageIso: String, speechRate: Float) {
        val voice = bestVoice(languageIso)
        if (voice == null) {
            _isAvailable.value = false
            return
        }
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        IosAudioSession.activateForPlayback()
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text).apply {
            this.voice = voice
            this.rate = (speechRate * 0.5f).coerceIn(0.2f, 0.7f)
        }
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        _isSpeaking.value = false
    }

    private class TtsDelegate(val onChange: (Boolean) -> Unit) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            onChange(true)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            onChange(false)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            onChange(false)
        }
    }
}
