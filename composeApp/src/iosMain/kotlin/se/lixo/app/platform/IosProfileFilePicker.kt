package se.lixo.app.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject

private const val MAX_IMPORT_BYTES = 5_000_000

/**
 * `UIDocumentPickerViewController`-backed [ProfileFilePicker] (doc/plans/045 §D). Does no UIKit
 * work in its constructor: `LixoApp` builds this on every launch, including in `iosTest`.
 */
internal class IosProfileFilePicker : ProfileFilePicker {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun saveText(suggestedFileName: String, mimeType: String, text: String): FileSaveResult =
        withContext(Dispatchers.Main) {
            val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + suggestedFileName)
            val wrote = NSString.create(string = text).writeToURL(
                url,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            if (!wrote) return@withContext FileSaveResult.Failed("could not write temp file")

            val top = topViewController() ?: return@withContext FileSaveResult.Failed("no window")
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)

            val result = suspendCancellableCoroutine<FileSaveResult> { cont ->
                var resumed = false
                val delegate = DocumentPickerDelegate(
                    onPicked = { _ ->
                        if (resumed) return@DocumentPickerDelegate
                        resumed = true
                        cont.resume(FileSaveResult.Saved)
                    },
                    onCancelled = {
                        if (resumed) return@DocumentPickerDelegate
                        resumed = true
                        cont.resume(FileSaveResult.Cancelled)
                    }
                )
                activeDelegate = delegate
                picker.delegate = delegate
                top.presentViewController(picker, animated = true, completion = null)
                cont.invokeOnCancellation {
                    picker.dismissViewControllerAnimated(true, completion = null)
                }
            }
            activeDelegate = null
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
            result
        }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun openText(mimeTypes: List<String>): FileOpenResult =
        withContext(Dispatchers.Main) {
            val top = topViewController() ?: return@withContext FileOpenResult.Failed("no window")
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeJSON),
                asCopy = true
            )

            suspendCancellableCoroutine<FileOpenResult> { cont ->
                var resumed = false
                val delegate = DocumentPickerDelegate(
                    onPicked = { urls ->
                        if (resumed) return@DocumentPickerDelegate
                        resumed = true
                        val url = urls.firstOrNull()
                        if (url == null) {
                            cont.resume(FileOpenResult.Failed("no file"))
                            return@DocumentPickerDelegate
                        }
                        val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
                        cont.resume(
                            when {
                                text == null -> FileOpenResult.Failed("could not read file")
                                text.length > MAX_IMPORT_BYTES -> FileOpenResult.Failed("too large")
                                else -> FileOpenResult.Opened(text)
                            }
                        )
                    },
                    onCancelled = {
                        if (resumed) return@DocumentPickerDelegate
                        resumed = true
                        cont.resume(FileOpenResult.Cancelled)
                    }
                )
                activeDelegate = delegate
                picker.delegate = delegate
                top.presentViewController(picker, animated = true, completion = null)
                cont.invokeOnCancellation {
                    picker.dismissViewControllerAnimated(true, completion = null)
                }
            }
        }.also { activeDelegate = null }

    // picker.delegate is a weak reference; hold the delegate strongly here so it isn't
    // deallocated before the callback fires (same pattern as IosQrScanner's MetadataDelegate).
    private var activeDelegate: NSObject? = null
}

private class DocumentPickerDelegate(
    private val onPicked: (List<platform.Foundation.NSURL>) -> Unit,
    private val onCancelled: () -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        @Suppress("UNCHECKED_CAST")
        onPicked(didPickDocumentsAtURLs as List<platform.Foundation.NSURL>)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}

actual fun createPlatformProfileFilePicker(): ProfileFilePicker = IosProfileFilePicker()
