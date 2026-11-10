package se.lixo.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.Firebase
import com.google.firebase.initialize
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.ui.LixoApp

class MainActivity : ComponentActivity() {

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        AndroidPlatformContext.onRecordAudioPermissionResult(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        AndroidPlatformContext.init(applicationContext) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        installAppCheckProvider()
        handleIncomingEmailLinkIntent(intent)
        handleIncomingFriendDeepLinkIntent(intent)
        setContent {
            LixoApp()
        }
    }

    override fun onDestroy() {
        AndroidPlatformContext.clearPermissionRequester()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingEmailLinkIntent(intent)
        handleIncomingFriendDeepLinkIntent(intent)
    }

    private fun handleIncomingEmailLinkIntent(intent: Intent?) {
        val emailLink = intent?.data?.toString()
        if (!emailLink.isNullOrBlank()) {
            EmailLinkAuthDispatcher.onIncomingLink(emailLink)
        }
    }

    /** doc/plans/030 §7: `lixo://friend/<code>` only prefills the code; never auto-redeems. */
    private fun handleIncomingFriendDeepLinkIntent(intent: Intent?) {
        val link = intent?.data?.toString()
        if (!link.isNullOrBlank()) {
            FriendDeepLinkDispatcher.onIncomingLink(link)
        }
    }

    /**
     * doc/plans/030 §2a: installs the App Check provider before any other Firebase call --
     * Play Integrity in a release build on a real device, the debug provider (registered per
     * doc/friends.md) in a debug build so a dev device/emulator without Play Integrity
     * attestation can still get a token. Enforcement on Firestore/Auth is NOT turned on yet.
     *
     * [installAppCheckProviderFactory] is resolved per build-type source set
     * (`src/debug/kotlin`/`src/release/kotlin`, see `AppCheckInstaller.kt` in each), not a
     * `BuildConfig.DEBUG` branch: `firebase-appcheck-debug` is `debugImplementation`-only, so a
     * release compile that referenced its `DebugAppCheckProviderFactory` directly would fail
     * (H1 in temp/verify/030_review.md).
     */
    private fun installAppCheckProvider() {
        Firebase.initialize(this)
        installAppCheckProviderFactory()
    }
}
