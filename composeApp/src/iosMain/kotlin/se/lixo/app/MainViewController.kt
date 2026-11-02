package se.lixo.app

import androidx.compose.ui.window.ComposeUIViewController
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.ui.LixoApp

fun MainViewController() = ComposeUIViewController {
    LixoApp()
}

/**
 * Entry point invoked from Swift (`iOSApp.swift` Universal Links / `onContinueUserActivity` / `onOpenURL`)
 * when an incoming Firebase Email Sign-In Link opens the iOS app.
 */
fun handleIncomingEmailSignInLink(url: String): Boolean =
    EmailLinkAuthDispatcher.onIncomingLink(url)
