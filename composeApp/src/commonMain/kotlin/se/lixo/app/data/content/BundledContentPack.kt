package se.lixo.app.data.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lixo.composeapp.generated.resources.Res
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.platform.readDebugContentPreviewFile

/**
 * The bundled content pack (021): the one the app always has, loaded from the compose resource
 * written by `tools/content_pipeline/pack/build_pack.py`. `Res` is internal to the `composeApp`
 * module, which is why androidTest/androidHostTest need this wrapper instead of calling `Res`
 * directly.
 */
object BundledContentPack {

    /** Path of the bundled pack inside `composeResources` (see `Res.readBytes`). */
    const val RESOURCE_PATH = "files/content_pack.json"

    /** File name of the debug preview override under the app's `filesDir`. */
    const val PREVIEW_FILE_NAME = "content_pack_preview.json"

    /** The bundled pack's raw JSON text. */
    suspend fun readJson(): String = Res.readBytes(RESOURCE_PATH).decodeToString()

    /** Every achievement id the app build knows about, for [ContentPackValidator.validate]. */
    fun appAchievementIds(): Set<String> = LixoRepository.seedAchievementIds()

    /** Validates [json] (no `previous` pack, so a same/older `version` is allowed). */
    fun validate(json: String, ids: Set<String> = appAchievementIds()): ValidationResult =
        ContentPackValidator.validate(json, ids, previous = null, requireNewerVersion = false)

    /** Loads and validates the bundled pack, off the main thread. Throws if it is rejected. */
    suspend fun load(): ActiveContent = withContext(Dispatchers.Default) {
        val json = readJson()
        when (val result = validate(json)) {
            is ValidationResult.Accepted -> ActiveContent.from(result.pack)
            is ValidationResult.Rejected -> error("Bundled content pack rejected: ${result.reason}")
        }
    }

    /** Where [loadForPreview] got its content from. */
    sealed interface PreviewSource {
        data object Preview : PreviewSource
        data object Bundled : PreviewSource
        data object Sample : PreviewSource
    }

    /**
     * Debug-screen content + a human-readable source label: tries the on-device preview file
     * first, then the bundled pack, then [SampleContentPack] as a last resort.
     */
    suspend fun loadForPreview(): Pair<ActiveContent, String> = withContext(Dispatchers.Default) {
        val previewJson = readDebugContentPreviewFile()
        if (previewJson != null) {
            when (val result = validate(previewJson)) {
                is ValidationResult.Accepted ->
                    return@withContext ActiveContent.from(result.pack) to "förhandsfil v${result.pack.version}"
                is ValidationResult.Rejected ->
                    println("LixoContent: preview file rejected: ${result.reason}")
            }
        }

        runCatching { load() }.getOrNull()?.let { content ->
            return@withContext content to "inbyggt paket v${content.version}"
        }

        SampleContentPack.activeContent() to "testpaket"
    }
}
