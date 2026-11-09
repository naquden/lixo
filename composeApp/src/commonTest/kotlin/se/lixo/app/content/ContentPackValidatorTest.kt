package se.lixo.app.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.SkipKind
import se.lixo.app.domain.content.ValidationResult

class ContentPackValidatorTest {

    private fun validate(json: String, requireNewerVersion: Boolean = true, previous: ActiveContent? = null) =
        ContentPackValidator.validate(json, TestPackFixture.ACHIEVEMENT_IDS, previous, requireNewerVersion)

    private fun accept(json: String): ValidationResult.Accepted =
        assertIs<ValidationResult.Accepted>(validate(json))

    // --- Skips ---

    @Test
    fun skipsItemWithUnknownSlot() {
        val json = TestPackFixture.editItem("item_shoes_test_boot") {
            this["slot"] = JsonPrimitive("FACE")
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals(SkipKind.ITEM, accepted.skipped[0].kind)
        assertEquals(9, accepted.pack.items.size)
    }

    @Test
    fun skipsItemWithBadPath() {
        val json = TestPackFixture.editItem("item_shoes_test_boot") {
            val layers = (this["layers"] as JsonArray).map { el ->
                val layer = LinkedHashMap((el as JsonObject).toMap())
                layer["d"] = JsonPrimitive("M 0 0 L")
                JsonObject(layer)
            }
            this["layers"] = JsonArray(layers)
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals(SkipKind.ITEM, accepted.skipped[0].kind)
        assertEquals("item_shoes_test_boot", accepted.skipped[0].id)
        assertEquals(9, accepted.pack.items.size)
    }

    @Test
    fun skipsItemOutOfBoneBox() {
        val json = TestPackFixture.editItem("item_hat_test_retired") {
            val layers = (this["layers"] as JsonArray).map { el ->
                val layer = LinkedHashMap((el as JsonObject).toMap())
                layer["d"] = JsonPrimitive("M 0 0 L 200 0 L 0 10 Z")
                JsonObject(layer)
            }
            this["layers"] = JsonArray(layers)
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals("item_hat_test_retired", accepted.skipped[0].id)
        assertEquals(9, accepted.pack.items.size)
    }

    @Test
    fun skipsItemWith25Layers() {
        val json = TestPackFixture.editItem("item_hand_left_test_shield") {
            val layer = (this["layers"] as JsonArray)[0]
            this["layers"] = JsonArray(List(25) { layer })
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals("item_hand_left_test_shield", accepted.skipped[0].id)
        assertEquals(9, accepted.pack.items.size)
    }

    @Test
    fun skipsItemWithBadHex() {
        val json = TestPackFixture.editItem("item_gloves_test_mitt") {
            val palette = LinkedHashMap((this["palette"] as JsonObject).toMap())
            palette["primary"] = JsonPrimitive("#GG0000")
            this["palette"] = JsonObject(palette)
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals("item_gloves_test_mitt", accepted.skipped[0].id)
        assertEquals(9, accepted.pack.items.size)
    }

    @Test
    fun skipsItemAndEventWhenItemBecomesInvalid() {
        val json = TestPackFixture.editItem("item_tail_test_bow") {
            this["slot"] = JsonPrimitive("BOGUS")
        }
        val accepted = accept(json)
        assertEquals(2, accepted.skipped.size)
        assertEquals(0, accepted.pack.events.size)
    }

    @Test
    fun requireNewerVersionFalseAcceptsSameVersion() {
        val previous = ActiveContent.from(accept(TestPackFixture.JSON).pack)
        val result = validate(TestPackFixture.JSON, requireNewerVersion = false, previous = previous)
        assertIs<ValidationResult.Accepted>(result)
    }

    // --- Rejections ---

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val json = TestPackFixture.edit { this["schemaVersion"] = JsonPrimitive(2) }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsZeroRarityWeight() {
        val json = TestPackFixture.editDropRules {
            val weights = LinkedHashMap((this["rarityWeights"] as JsonObject).toMap())
            weights["COMMON"] = JsonPrimitive(0)
            this["rarityWeights"] = JsonObject(weights)
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsNonIncreasingPity() {
        val json = TestPackFixture.editDropRules {
            val pity = LinkedHashMap((this["pity"] as JsonObject).toMap())
            pity["EPIC"] = JsonPrimitive(3)
            this["pity"] = JsonObject(pity)
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsMissingAchievementReward() {
        val result = ContentPackValidator.validate(
            TestPackFixture.JSON,
            TestPackFixture.ACHIEVEMENT_IDS + "ach_missing",
            previous = null
        )
        assertIs<ValidationResult.Rejected>(result)
    }

    @Test
    fun rejectsWhenLevelRewardPointsAtASkippedItem() {
        val json = TestPackFixture.editItem("item_shirt_test_tee") {
            val layers = (this["layers"] as JsonArray).map { el ->
                val layer = LinkedHashMap((el as JsonObject).toMap())
                layer["d"] = JsonPrimitive("M 0 0 L")
                JsonObject(layer)
            }
            this["layers"] = JsonArray(layers)
        }
        val result = validate(json)
        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertTrue(rejected.reason.contains("levelRewards"), rejected.reason)
    }

    @Test
    fun rejectsWhenNoDefaultAvatarIsLeft() {
        val json = TestPackFixture.edit {
            val avatars = (this["avatars"] as JsonArray).map { el ->
                val avatar = el as JsonObject
                if ((avatar["id"] as? JsonPrimitive)?.content == "avatar_cat") {
                    val mutable = LinkedHashMap(avatar.toMap())
                    mutable["unlock"] = JsonObject(
                        linkedMapOf("type" to JsonPrimitive("stars"), "stars" to JsonPrimitive(1))
                    )
                    JsonObject(mutable)
                } else {
                    el
                }
            }
            this["avatars"] = JsonArray(avatars)
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsWhenAnItemFromPreviousIsMissing() {
        val previous = ActiveContent.from(accept(TestPackFixture.JSON).pack)
        val newPack = TestPackFixture.edit {
            this["version"] = JsonPrimitive(43)
            val items = (this["items"] as JsonArray).filterNot {
                ((it as JsonObject)["id"] as? JsonPrimitive)?.content == "item_hat_test_retired"
            }
            this["items"] = JsonArray(items)
        }
        val result = validate(newPack, previous = previous)
        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertTrue(rejected.reason.contains("missing"), rejected.reason)
    }

    @Test
    fun rejectsSameVersionAsPreviousByDefault() {
        val previous = ActiveContent.from(accept(TestPackFixture.JSON).pack)
        val result = validate(TestPackFixture.JSON, previous = previous)
        assertIs<ValidationResult.Rejected>(result)
    }
}
