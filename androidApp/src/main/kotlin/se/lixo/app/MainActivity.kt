package se.lixo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.ui.LixoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidPlatformContext.init(applicationContext)
        handleIncomingEmailLinkIntent(intent)
        setContent {
            LixoApp()
        }
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
