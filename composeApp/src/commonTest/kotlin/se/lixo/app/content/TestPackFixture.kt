package se.lixo.app.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import se.lixo.app.domain.content.ContentJson
import se.lixo.app.domain.content.SampleContentPack

/**
 * A hand-written content pack fixture shared by every test in this package (and reused by
 * 020's renderer tests). The JSON itself lives in `commonMain` as [SampleContentPack] so
 * `androidTest` (which cannot see `commonTest`) can reuse the exact same pack; this object just
 * delegates to it and adds the mutation helpers ([edit]/[editItem]/[editDropRules]) used by the
 * validator tests in this package.
 *
 * Covers: 2 avatars (1 default, 1 star-unlock with a bone-size override), 10 items spanning all
 * 9 equipment slots plus one retired item, 1 event, and a full `dropRules` block. Only the hat
 * cone, cape, tee, shorts and wand are referenced by `dropRules` — mutating any OTHER item
 * (boot, mitt, shield, retired hat, bow) in a skip test, rather than one of those five, keeps
 * the pack `Accepted` instead of flipping it to `Rejected`.
 */
object TestPackFixture {

    val ACHIEVEMENT_IDS = SampleContentPack.ACHIEVEMENT_IDS

    val JSON: String = SampleContentPack.JSON

    /** Parses [JSON], applies [block] to a mutable copy of its top-level fields, and re-serializes. */
    fun edit(block: MutableMap<String, JsonElement>.() -> Unit): String {
        val root = ContentJson.parseToJsonElement(JSON).jsonObject
        val mutable = LinkedHashMap<String, JsonElement>(root)
        mutable.block()
        return JsonObject(mutable).toString()
    }

    /** Like [edit], but [block] mutates the fields of the item with id [id] (the item is left untouched if not found). */
    fun editItem(id: String, block: MutableMap<String, JsonElement>.() -> Unit): String = edit {
        val items = (this["items"] as JsonArray).map { element ->
            val obj = element.jsonObject
            if ((obj["id"] as? JsonPrimitive)?.content == id) {
                val mutableItem = LinkedHashMap<String, JsonElement>(obj)
                mutableItem.block()
                JsonObject(mutableItem)
            } else {
                element
            }
        }
        this["items"] = JsonArray(items)
    }

    /** Like [edit], but [block] mutates the fields of the top-level `dropRules` object. */
    fun editDropRules(block: MutableMap<String, JsonElement>.() -> Unit): String = edit {
        val mutableDropRules = LinkedHashMap<String, JsonElement>(this["dropRules"]!!.jsonObject)
        mutableDropRules.block()
        this["dropRules"] = JsonObject(mutableDropRules)
    }
}
