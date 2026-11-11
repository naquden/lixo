package se.lixo.app

import androidx.compose.ui.window.ComposeUIViewController
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.ui.LixoApp

fun MainViewController() = ComposeUIViewController {
    LixoApp()
}

/**
 * Entry point invoked from Swift (`iOSApp.swift` Universal Links / `onContinueUserActivity` /
 * `onOpenURL`, and the AppDelegate equivalents) for every incoming URL: a `lixo://friend/<code>`
 * deep link (`FriendDeepLinkDispatcher`) or a Firebase Email Sign-In Link
 * (`EmailLinkAuthDispatcher`). Returns true if either dispatcher recognized and routed the URL.
 */
fun handleIncomingUrl(url: String): Boolean =
    FriendDeepLinkDispatcher.onIncomingLink(url) || EmailLinkAuthDispatcher.onIncomingLink(url)

/**
 * Kept for the Firebase Email Link Auth guide's naming; delegates to [handleIncomingUrl] since
 * both deep link kinds share the same Swift entry points.
 */
fun handleIncomingEmailSignInLink(url: String): Boolean = handleIncomingUrl(url)
