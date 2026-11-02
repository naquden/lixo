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
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.EmailLinkAuthDispatcher
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
        handleIncomingEmailLinkIntent(intent)
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
    }

    private fun handleIncomingEmailLinkIntent(intent: Intent?) {
        val emailLink = intent?.data?.toString()
        if (!emailLink.isNullOrBlank()) {
            EmailLinkAuthDispatcher.onIncomingLink(emailLink)
        }
    }
}
