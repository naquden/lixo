package se.lixo.app.verify

import kotlinx.coroutines.runBlocking
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.domain.content.ActiveContent

/**
 * The real bundled content pack, loaded through [ContentRepository.loadBundled] exactly like the
 * app does (through the real Compose `Res` and the real validator), for instrumented tests.
 */
object TestContent {
    val repository: ContentRepository by lazy {
        runBlocking { ContentRepository.loadBundled(isDebugBuild = true) }
    }

    val active: ActiveContent get() = repository.active.value
}
