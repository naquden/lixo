package se.lixo.app.platform

import android.content.ActivityNotFoundException
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/**
 * Storage Access Framework-backed [ProfileFilePicker] (doc/plans/045 §D). No storage permission
 * is needed: `CreateDocument`/`OpenDocument` grant a scoped, one-off URI permission.
 */
internal class AndroidProfileFilePicker : ProfileFilePicker {

    override suspend fun saveText(suggestedFileName: String, mimeType: String, text: String): FileSaveResult {
        val context = AndroidPlatformContext.applicationContext
            ?: return FileSaveResult.Failed("no context")
        val uri: Uri = try {
            AndroidPlatformContext.createDocument(suggestedFileName) ?: return FileSaveResult.Cancelled
        } catch (e: ActivityNotFoundException) {
            return FileSaveResult.Failed(e.message ?: "no document picker")
        } catch (e: Exception) {
            return FileSaveResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = text.encodeToByteArray()
                val wrote = writeBytes(uri, "wt", bytes) || writeBytes(uri, "w", bytes)
                if (wrote) FileSaveResult.Saved else FileSaveResult.Failed("could not open output stream")
            } catch (e: Exception) {
                FileSaveResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            }
        }
    }

    private fun writeBytes(uri: Uri, mode: String, bytes: ByteArray): Boolean {
        val context = AndroidPlatformContext.applicationContext ?: return false
        return try {
            val stream = context.contentResolver.openOutputStream(uri, mode) ?: return false
            stream.use { it.write(bytes) }
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun openText(mimeTypes: List<String>): FileOpenResult {
        val context = AndroidPlatformContext.applicationContext
            ?: return FileOpenResult.Failed("no context")
        val uri: Uri = try {
            AndroidPlatformContext.openDocument(mimeTypes.toTypedArray()) ?: return FileOpenResult.Cancelled
        } catch (e: ActivityNotFoundException) {
            return FileOpenResult.Failed(e.message ?: "no document picker")
        } catch (e: Exception) {
            return FileOpenResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        }
        return withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext FileOpenResult.Failed("could not open input stream")
                val buffer = ByteArray(8 * 1024)
                val out = ByteArrayOutputStream()
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_IMPORT_BYTES) return@withContext FileOpenResult.Failed("too large")
                    }
                }
                FileOpenResult.Opened(out.toByteArray().decodeToString())
            } catch (e: Exception) {
                FileOpenResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            }
        }
    }
}

actual fun createPlatformProfileFilePicker(): ProfileFilePicker = AndroidProfileFilePicker()
