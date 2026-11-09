package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.lixo.app.domain.content.AfterEnd
import se.lixo.app.domain.content.Bone
import se.lixo.app.domain.content.ContentJson
import se.lixo.app.domain.content.ContentLayer
import se.lixo.app.domain.content.ContentPack
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.PaletteRole
import se.lixo.app.domain.content.Shading
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.content.Mirror
import se.lixo.app.domain.content.ZBucket

class ContentPackParseTest {

    @Test
    fun fixtureRoundTrips() {
        val pack = ContentJson.decodeFromString<ContentPack>(TestPackFixture.JSON)
        val encoded = ContentJson.encodeToString(pack)
        val decodedAgain = ContentJson.decodeFromString<ContentPack>(encoded)
        assertEquals(pack, decodedAgain)
    }

    @Test
    fun unknownKeysAreIgnored() {
        // The fixture has a top-level "futureField" and an item-level "futureItemField"; this
        // just needs to decode without throwing.
        val pack = ContentJson.decodeFromString<ContentPack>(TestPackFixture.JSON)
        assertEquals(42, pack.version)
    }

    @Test
    fun layerDecodesEnumsByNameOrSerialName() {
        val json = """
            {"bone":"ARM_L","z":"TORSO_OVER","d":"M0 0 L1 1","fill":{"role":"tint"},"shading":"block","depth":4,"mirror":"x"}
        """.trimIndent()
        val layer = ContentJson.decodeFromString<ContentLayer>(json)
        assertEquals(Bone.ARM_L, layer.bone)
        assertEquals(ZBucket.TORSO_OVER, layer.z)
        assertEquals(PaletteRole.TINT, layer.fill.role)
        assertEquals(Shading.BLOCK, layer.shading)
        assertEquals(Mirror.X, layer.mirror)
    }

    @Test
    fun itemPoolAchievementAndAfterEndJoinStandardDecode() {
        val json = TestPackFixture.editItem("item_shoulders_test_cape") {}
        val pack = ContentJson.decodeFromString<ContentPack>(json)
        val cape = pack.items.first { it.id == "item_shoulders_test_cape" }
        assertEquals(ItemPool.ACHIEVEMENT, cape.pool)

        val eventJson = TestPackFixture.edit {
            val events = (this["events"] as JsonArray).map { el ->
                val obj = el as JsonObject
                val mutable = LinkedHashMap(obj.toMap())
                mutable["afterEnd"] = JsonPrimitive("joinStandard")
                JsonObject(mutable)
            }
            this["events"] = JsonArray(events)
        }
        val eventPack = ContentJson.decodeFromString<ContentPack>(eventJson)
        assertEquals(AfterEnd.JOIN_STANDARD, eventPack.events.first().afterEnd)
    }

    @Test
    fun fixtureValidatesAsAcceptedWithNoSkips() {
        val result = ContentPackValidator.validate(TestPackFixture.JSON, TestPackFixture.ACHIEVEMENT_IDS, previous = null)
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(emptyList(), accepted.skipped)
        assertEquals(10, accepted.pack.items.size)
        assertEquals(2, accepted.pack.avatars.size)
        assertEquals(1, accepted.pack.events.size)
    }
}
