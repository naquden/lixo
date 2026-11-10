package se.lixo.app

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug-variant App Check provider (doc/plans/030 §2a): the debug provider lets a non-attested
 * emulator/dev build register a debug token (doc/friends.md) instead of a real Play Integrity
 * attestation. [firebase-appcheck-debug] is `debugImplementation`-only (H1 in temp/verify/030_review.md:
 * a release build referencing [DebugAppCheckProviderFactory] directly fails to compile), so this
 * function -- with the exact same signature as the release variant's -- lives only in this
 * `src/debug` source set.
 */
fun installAppCheckProviderFactory() {
    Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
