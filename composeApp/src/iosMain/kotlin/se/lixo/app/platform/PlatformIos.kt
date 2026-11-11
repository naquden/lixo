package se.lixo.app.platform

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.HTTPMethod
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSBundle
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.darwin.sysctlbyname
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np
import platform.posix.timeval
import se.lixo.app.data.local.LIXO_MIGRATIONS
import se.lixo.app.data.local.LixoDatabase
import se.lixo.app.domain.engine.TrustedClockAnchor
import se.lixo.app.domain.engine.TrustedClockStore
import kotlin.coroutines.resume
import kotlin.native.Platform

/**
 * iOS implementation of PlatformFeatureFlags with graceful degradation support
 * as required by project.md §1 ("Om en funktion inte är tillgänglig på en plattform
 * inaktiveras den funktionen på den plattformen (graceful degradation via expect/actual + feature flags)").
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual class PlatformFeatureFlags actual constructor() {
    actual val platformName: String = "iOS"
    actual val isSwedishTtsSupported: Boolean =
        AVSpeechSynthesisVoice.voiceWithLanguage("sv-SE") != null
    actual val isOnDeviceSttSupported: Boolean =
        iosSwedishSpeechRecognizer()?.supportsOnDeviceRecognition ?: false
    actual val isNative3DSceneViewSupported: Boolean = true
    actual val isDebugBuild: Boolean = Platform.isDebugBinary
    actual val appVersionCode: Int =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)
            ?.toIntOrNull() ?: 0
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; navigation uses on-screen controls.
}

actual fun createPlatformSpeechRecognitionEngine(): SpeechRecognitionEngine =
    IosSpeechRecognitionEngine()

actual fun createPlatformTextToSpeechEngine(): TextToSpeechEngine =
    IosTextToSpeechEngine()

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val result = ByteArray(size)
    if (size == 0) return result
    result.usePinned { pinned ->
        platform.Security.SecRandomCopyBytes(platform.Security.kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    return result
}

/** iOS: only in debug binaries, read from `Documents/content_pack_preview.json`. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun readDebugContentPreviewFile(): String? {
    if (!Platform.isDebugBinary) return null
    return try {
        val dir = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null
        )?.path ?: return null
        val path = "$dir/content_pack_preview.json"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    } catch (_: Throwable) {
        null
    }
}

/** iOS: `NSURLSession` HEAD request, 5 s timeout, no cache; parses the `Date` response header. */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun fetchHttpServerTimeMillis(url: String): Long? = try {
    withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine<Long?> { cont ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val request = NSMutableURLRequest(uRL = nsUrl).apply {
                HTTPMethod = "HEAD"
                setTimeoutInterval(5.0)
                setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            }
            val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { _, response, error ->
                val millis = (response as? NSHTTPURLResponse)
                    ?.takeIf { error == null }
                    ?.valueForHTTPHeaderField("Date")
                    ?.let(::parseHttpDateMillis)
                cont.resume(millis)
            }
            task.resume()
            cont.invokeOnCancellation { task.cancel() }
        }
    }
} catch (_: Throwable) {
    null
}

private var cachedLixoDatabase: LixoDatabase? = null

/**
 * The DB lives in `Application Support` (not `Documents`), with the directory marked
 * `NSURLIsExcludedFromBackupKey` so child data never leaves the device via iCloud backup, same
 * privacy stance as Android 041 (commit 8ee65d6). This only applies to fresh iOS installs; there
 * are no existing iOS users to migrate.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformLixoDatabase(): LixoDatabase? {
    cachedLixoDatabase?.let { return it }
    val dirUrl = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    ) ?: return null
    dirUrl.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    val dir = dirUrl.path ?: return null
    return try {
        Room.databaseBuilder<LixoDatabase>(name = "$dir/lixo.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // 022 reset the schema to v1 with no explicit migration yet (see LIXO_MIGRATIONS'
            // KDoc). Existing installs are at v7, so opening at v1 is a DOWNGRADE: both the
            // upgrade and downgrade destructive fallbacks are required.
            .addMigrations(*LIXO_MIGRATIONS)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
            .also { cachedLixoDatabase = it }
    } catch (_: Throwable) {
        null
    }
}

/** Counts across sleep on Darwin, unlike `NSProcessInfo.systemUptime`. */
@OptIn(ExperimentalForeignApi::class)
actual fun platformMonotonicMillis(): Long =
    (clock_gettime_nsec_np(CLOCK_MONOTONIC.convert()) / 1_000_000uL).toLong()

/**
 * `sysctlbyname("kern.boottime")` seconds, formatted as a string. This changes if the user
 * changes the wall clock, which fails safe: the anchor becomes untrusted rather than silently
 * wrong (do not "fix" this with an uptime-based heuristic).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun platformBootId(): String? = try {
    memScoped {
        val tv = alloc<timeval>()
        val size = alloc<ULongVar>()
        size.value = sizeOf<timeval>().convert()
        val ok = sysctlbyname("kern.boottime", tv.ptr, size.ptr, null, 0u) == 0
        if (ok) tv.tv_sec.toString() else null
    }
} catch (_: Throwable) {
    null
}

/**
 * iOS implementation of [TrustedClockStore] backed by `NSUserDefaults.standardUserDefaults`
 * (same pattern as [PendingEmailLinkStorage]).
 */
private class IosTrustedClockStore : TrustedClockStore {
    companion object {
        private const val KEY_ANCHOR_SERVER_MILLIS = "lixo_trusted_clock_anchor_server_millis"
        private const val KEY_ANCHOR_MONOTONIC_MILLIS = "lixo_trusted_clock_anchor_monotonic_millis"
        private const val KEY_ANCHOR_BOOT_ID = "lixo_trusted_clock_anchor_boot_id"
        private const val KEY_LAST_TRUSTED_MILLIS = "lixo_trusted_clock_last_trusted_millis"
    }

    override fun loadAnchor(): TrustedClockAnchor? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(KEY_ANCHOR_SERVER_MILLIS) == null ||
            defaults.objectForKey(KEY_ANCHOR_MONOTONIC_MILLIS) == null
        ) {
            return null
        }
        return TrustedClockAnchor(
            serverMillis = defaults.integerForKey(KEY_ANCHOR_SERVER_MILLIS),
            monotonicMillis = defaults.integerForKey(KEY_ANCHOR_MONOTONIC_MILLIS),
            bootId = defaults.stringForKey(KEY_ANCHOR_BOOT_ID)
        )
    }

    override fun saveAnchor(anchor: TrustedClockAnchor) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(anchor.serverMillis, forKey = KEY_ANCHOR_SERVER_MILLIS)
        defaults.setInteger(anchor.monotonicMillis, forKey = KEY_ANCHOR_MONOTONIC_MILLIS)
        if (anchor.bootId != null) {
            defaults.setObject(anchor.bootId, forKey = KEY_ANCHOR_BOOT_ID)
        } else {
            defaults.removeObjectForKey(KEY_ANCHOR_BOOT_ID)
        }
    }

    override fun loadLastTrustedMillis(): Long? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(KEY_LAST_TRUSTED_MILLIS) == null) {
            null
        } else {
            defaults.integerForKey(KEY_LAST_TRUSTED_MILLIS)
        }
    }

    override fun saveLastTrustedMillis(millis: Long) {
        NSUserDefaults.standardUserDefaults.setInteger(millis, forKey = KEY_LAST_TRUSTED_MILLIS)
    }
}

actual fun createPlatformTrustedClockStore(): TrustedClockStore = IosTrustedClockStore()

/**
 * iOS implementation of [PendingEmailLinkStorage] backed by `NSUserDefaults.standardUserDefaults`
 * (`UserDefaults.standard.set(email, forKey: "Email")` in the Apple Firebase Email Link guide).
 */
actual object PendingEmailLinkStorage {
    private const val KEY_SAVED_EMAIL = "Email"

    actual fun saveEmail(email: String) {
        NSUserDefaults.standardUserDefaults.setObject(email.trim(), forKey = KEY_SAVED_EMAIL)
    }

    actual fun getSavedEmail(): String? {
        val stored = NSUserDefaults.standardUserDefaults.stringForKey(KEY_SAVED_EMAIL)
        return stored?.takeIf { it.isNotBlank() }
    }

    actual fun clearSavedEmail() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_SAVED_EMAIL)
    }
}

actual fun createPlatformQrScanner(): QrScanner = IosQrScanner()

/** doc/plans/030 §7: iOS's "reduce motion" accessibility setting. */
actual fun isReducedMotionEnabled(): Boolean =
    runCatching { UIAccessibilityIsReduceMotionEnabled() }.getOrDefault(false)
