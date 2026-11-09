package se.lixo.app.content

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.model.Rarity

class ActiveContentIndexTest {

    private fun activeContent(): ActiveContent {
        val result = ContentPackValidator.validate(TestPackFixture.JSON, TestPackFixture.ACHIEVEMENT_IDS, previous = null)
        val accepted = assertIs<ValidationResult.Accepted>(result)
        return ActiveContent.from(accepted.pack)
    }

    @Test
    fun standardPoolByRarityExcludesRetiredAchievementAndEventItems() {
        val content = activeContent()
        assertEquals(Rarity.entries.toSet(), content.standardPoolByRarity.keys)

        val commonIds = content.standardPoolByRarity[Rarity.COMMON]!!.map { it.id }.toSet()
        assertEquals(setOf("item_hat_test_cone", "item_shirt_test_tee", "item_pants_test_shorts"), commonIds)
    }

    @Test
    fun itemBoundsIsKnownForAKnownPathAndNullForAnUnknownId() {
        val content = activeContent()
        assertEquals(Rect(8f, -40f, 48f, 0f), content.itemBounds("item_hat_test_cone"))
        assertNull(content.itemBounds("nope"))
    }

    @Test
    fun stringFallsBackToTheKeyItself() {
        val content = activeContent()
        assertEquals("Testhatt", content.string("item_hat_test_cone.name"))
        assertEquals("missing.key", content.string("missing.key"))
    }

    @Test
    fun avatarsOrderedAndDefaultAvatar() {
        val content = activeContent()
        assertEquals(listOf("avatar_cat", "avatar_dog"), content.avatarsOrdered.map { it.id })
        assertEquals("avatar_cat", content.defaultAvatar.id)
    }
}
