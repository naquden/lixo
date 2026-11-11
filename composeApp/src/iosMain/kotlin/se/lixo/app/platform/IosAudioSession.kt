package se.lixo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive

/**
 * Shared `AVAudioSession` category management for STT and TTS. Main-thread only: Objective-C
 * exceptions are not catchable from Kotlin, so this must never be called from a constructor
 * (`AVAudioSession` calls can throw synchronously on invalid state).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosAudioSession {
    /** Whether STT currently owns the audio session; TTS backs off while this is true. */
    var sttActive: Boolean = false
        private set

    /** `.playAndRecord`, default to speaker, Bluetooth allowed. */
    fun activateForRecording(): Boolean {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
            error = null
        )
        session.setActive(true, error = null)
        sttActive = true
        return true
    }

    /** `.playback`, only when STT is not currently active. */
    fun activateForPlayback() {
        if (sttActive) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    fun deactivateRecording() {
        sttActive = false
        AVAudioSession.sharedInstance().setActive(
            false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null
        )
    }
}
