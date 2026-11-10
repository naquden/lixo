package se.lixo.app

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release-variant App Check provider (doc/plans/030 §2a): Play Integrity is the real-device
 * provider. Same signature as the debug variant's `installAppCheckProviderFactory` in
 * `src/debug`, so [MainActivity] calls one function regardless of build type and never needs a
 * `BuildConfig.DEBUG` branch that would pull the debug-only `firebase-appcheck-debug` artifact
 * into a release compile (H1 in temp/verify/030_review.md).
 */
fun installAppCheckProviderFactory() {
    Firebase.appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}
