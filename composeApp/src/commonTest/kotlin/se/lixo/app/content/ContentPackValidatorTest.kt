package se.lixo.app.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    private fun setDogEarsFillRole(mutable: MutableMap<String, JsonElement>, role: String) {
        val parts = (mutable["parts"] as JsonArray).map { el ->
            val part = el.jsonObject
            if ((part["part"] as? JsonPrimitive)?.content == "ears") {
                val layers = (part["layers"] as JsonArray).map { layerEl ->
                    val layer = LinkedHashMap((layerEl as JsonObject).toMap())
                    val fill = LinkedHashMap((layer["fill"] as JsonObject).toMap())
                    fill["role"] = JsonPrimitive(role)
                    layer["fill"] = JsonObject(fill)
                    JsonObject(layer)
                }
                val mutablePart = LinkedHashMap(part.toMap())
                mutablePart["layers"] = JsonArray(layers)
                JsonObject(mutablePart)
            } else {
                el
            }
        }
        mutable["parts"] = JsonArray(parts)
    }

    @Test
    fun skipsAvatarWithPaletteRoleButNoPalette() {
        val json = TestPackFixture.editAvatar("avatar_dog") {
            setDogEarsFillRole(this, "primary")
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals(SkipKind.AVATAR, accepted.skipped[0].kind)
        assertEquals("avatar_dog", accepted.skipped[0].id)
        assertEquals(1, accepted.pack.avatars.size)
    }

    @Test
    fun acceptsAvatarPaletteRoleWithPalette() {
        val json = TestPackFixture.editAvatar("avatar_dog") {
            setDogEarsFillRole(this, "primary")
            this["palette"] = JsonObject(
                linkedMapOf(
                    "primary" to JsonPrimitive("#112233"),
                    "secondary" to JsonPrimitive("#445566"),
                    "accent" to JsonPrimitive("#778899")
                )
            )
        }
        val accepted = accept(json)
        assertEquals(0, accepted.skipped.size)
        assertEquals(2, accepted.pack.avatars.size)
        assertEquals("#112233", accepted.pack.avatars[1].palette!!.primary)
    }

    @Test
    fun skipsAvatarWithBadPaletteHex() {
        val json = TestPackFixture.editAvatar("avatar_dog") {
            this["palette"] = JsonObject(
                linkedMapOf(
                    "primary" to JsonPrimitive("nope"),
                    "secondary" to JsonPrimitive("#445566"),
                    "accent" to JsonPrimitive("#778899")
                )
            )
        }
        val accepted = accept(json)
        assertEquals(1, accepted.skipped.size)
        assertEquals(SkipKind.AVATAR, accepted.skipped[0].kind)
        assertTrue(accepted.skipped[0].reason.contains("palette"), accepted.skipped[0].reason)
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

    // --- nicknameWords (doc/plans/030 §3) ---

    private fun wordsJson(adjectives: List<String>, animals: List<String>): JsonObject = JsonObject(
        linkedMapOf(
            "adjectives" to JsonArray(adjectives.map { JsonPrimitive(it) }),
            "animals" to JsonArray(animals.map { JsonPrimitive(it) })
        )
    )

    @Test
    fun missingNicknameWordsDefaultsToEmpty() {
        val accepted = accept(TestPackFixture.JSON)
        assertEquals(emptyList(), accepted.pack.nicknameWords.adjectives)
        assertEquals(emptyList(), accepted.pack.nicknameWords.animals)
    }

    @Test
    fun acceptsValidNicknameWords() {
        val json = TestPackFixture.edit {
            this["nicknameWords"] = wordsJson(listOf("Modig", "Glad"), listOf("Räv", "Uggla"))
        }
        val accepted = accept(json)
        assertEquals(listOf("Modig", "Glad"), accepted.pack.nicknameWords.adjectives)
        assertEquals(listOf("Räv", "Uggla"), accepted.pack.nicknameWords.animals)
    }

    @Test
    fun rejectsDuplicateNicknameWord() {
        val json = TestPackFixture.edit {
            this["nicknameWords"] = wordsJson(listOf("Modig", "Modig"), listOf("Räv"))
        }
        val rejected = assertIs<ValidationResult.Rejected>(validate(json))
        assertTrue(rejected.reason.contains("nicknameWords"), rejected.reason)
    }

    @Test
    fun rejectsBlankNicknameWord() {
        val json = TestPackFixture.edit {
            this["nicknameWords"] = wordsJson(listOf("Modig", ""), listOf("Räv"))
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsNicknameWordWithWhitespace() {
        val json = TestPackFixture.edit {
            this["nicknameWords"] = wordsJson(listOf("Modig Modig"), listOf("Räv"))
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }

    @Test
    fun rejectsNicknameWordListOverMax() {
        val tooMany = (0..256).map { "Ord$it" }
        val json = TestPackFixture.edit {
            this["nicknameWords"] = wordsJson(tooMany, listOf("Räv"))
        }
        assertIs<ValidationResult.Rejected>(validate(json))
    }
}
