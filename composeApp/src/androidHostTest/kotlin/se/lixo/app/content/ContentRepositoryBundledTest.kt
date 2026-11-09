package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import se.lixo.app.BundledTestContent
import se.lixo.app.data.repository.ContentRepository

class ContentRepositoryBundledTest {

    @Test
    fun fromJson_bundledPack_buildsExpectedActiveContent() {
        val active = ContentRepository.fromJson(BundledTestContent.json, isDebugBuild = true).active.value

        assertEquals(
            listOf("avatar_cat", "avatar_dog", "avatar_knight", "avatar_princess", "avatar_wizard", "avatar_witch"),
            active.avatarsOrdered.map { it.id }
        )
        assertTrue(active.dropRules.tutorialRewardItemId in active.itemsById)
        assertEquals(setOf("avatar_cat"), active.defaultAvatarIds)
        assertEquals("Katt", active.avatarName("avatar_cat"))
        assertNotNull(active.levelRewardItemId(1))
        assertNull(active.levelRewardItemId(161))
    }

    @Test
    fun fromJson_invalidJson_debugThrowsReleaseFallsBackToSample() {
        assertFailsWith<IllegalStateException> {
            ContentRepository.fromJson("{}", isDebugBuild = true)
        }

        val fallback = ContentRepository.fromJson("{}", isDebugBuild = false).active.value
        assertEquals(42, fallback.version)
    }
}
