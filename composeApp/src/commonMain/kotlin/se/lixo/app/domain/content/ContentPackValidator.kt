package se.lixo.app.domain.content

import androidx.compose.ui.geometry.Size
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import se.lixo.app.domain.model.Rarity
import kotlin.math.PI
import kotlin.math.abs

/** Result of [ContentPackValidator.validate]. */
sealed interface ValidationResult {
    /** The whole pack is unusable; the caller should keep whatever pack it already has. */
    data class Rejected(val reason: String) : ValidationResult

    /** The pack (or a subset of it) is usable. [skipped] lists every entry that was dropped. */
    data class Accepted(val pack: ContentPack, val skipped: List<Skip>) : ValidationResult
}

enum class SkipKind { ITEM, AVATAR, EVENT }

/** One entry ([kind] `id`) that was dropped from an otherwise-[ValidationResult.Accepted] pack, and why. */
data class Skip(val kind: SkipKind, val id: String, val reason: String)

/**
 * Validates a content pack JSON string (`doc/content/content-pack.md`) before it becomes the
 * app's [ActiveContent]. Mirrors the style of the old remote-wardrobe validator:
 * every avatar/item/event is decoded and checked one at a time, from a [kotlinx.serialization.json.JsonElement],
 * so a single malformed entry is skipped and logged instead of failing the whole pack.
 */
object ContentPackValidator {

    const val MAX_BYTES = 6 * 1024 * 1024
    const val MAX_ITEMS = 3000
    const val MAX_AVATARS = 64
    const val MAX_EVENTS = 64
    const val MAX_LAYERS = 24
    const val MAX_HIDES_PARTS = 8
    const val MAX_PARTS = 16

    private val ID_REGEX = Regex("^[a-z][a-z0-9_]{2,63}$")
    private val PART_REGEX = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val AVATAR_PART_ROLES = setOf(PaletteRole.SKIN, PaletteRole.TINT, PaletteRole.EYE, PaletteRole.OUTLINE)
    private val AVATAR_PALETTE_ROLES = setOf(PaletteRole.PRIMARY, PaletteRole.SECONDARY, PaletteRole.ACCENT)

    /**
     * Validates [json] against [appAchievementIds] (every id must have an achievement reward
     * item) and, if given, [previous] (every item id it already has must still be present, and
     * unless [requireNewerVersion] is false, [json]'s `version` must be strictly greater).
     */
    fun validate(
        json: String,
        appAchievementIds: Set<String>,
        previous: ActiveContent?,
        requireNewerVersion: Boolean = true
    ): ValidationResult {
        if (json.encodeToByteArray().size > MAX_BYTES) {
            return ValidationResult.Rejected("pack exceeds $MAX_BYTES bytes")
        }

        val root = try {
            ContentJson.parseToJsonElement(json)
        } catch (t: Throwable) {
            return ValidationResult.Rejected("invalid JSON: ${t.message?.take(200)}")
        }
        if (root !is JsonObject) {
            return ValidationResult.Rejected("invalid JSON: root is not an object")
        }

        val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return ValidationResult.Rejected(
                "schemaVersion $schemaVersion unsupported (need $SUPPORTED_SCHEMA_VERSION)"
            )
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: return ValidationResult.Rejected("version is missing or not an int")

        val rawItems = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val rawAvatars = root["avatars"] as? JsonArray ?: JsonArray(emptyList())
        val rawEvents = root["events"] as? JsonArray ?: JsonArray(emptyList())
        if (rawItems.size > MAX_ITEMS) return ValidationResult.Rejected("too many items (> $MAX_ITEMS)")
        if (rawAvatars.size > MAX_AVATARS) return ValidationResult.Rejected("too many avatars (> $MAX_AVATARS)")
        if (rawEvents.size > MAX_EVENTS) return ValidationResult.Rejected("too many events (> $MAX_EVENTS)")

        if (previous != null && requireNewerVersion && version <= previous.version) {
            return ValidationResult.Rejected("version $version not greater than previous ${previous.version}")
        }

        val strings: Map<String, Map<String, String>> = try {
            root["strings"]?.let { ContentJson.decodeFromJsonElement<Map<String, Map<String, String>>>(it) }
                ?: emptyMap()
        } catch (t: Throwable) {
            return ValidationResult.Rejected("strings invalid: ${t.message?.take(200)}")
        }

        val rawDropRules = root["dropRules"]
            ?: return ValidationResult.Rejected("dropRules invalid: missing")
        val dropRules: DropRules = try {
            ContentJson.decodeFromJsonElement<DropRules>(rawDropRules)
        } catch (t: Throwable) {
            return ValidationResult.Rejected("dropRules invalid: ${t.message?.take(200)}")
        }
        dropRulesError(dropRules)?.let { return ValidationResult.Rejected("dropRules invalid: $it") }

        val declaredEventIds: Set<String> = rawEvents.mapNotNull { element ->
            ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        }.filter { ID_REGEX.matches(it) }.toSet()

        val skipped = ArrayList<Skip>()

        // --- Items ---
        val acceptedItems = LinkedHashMap<String, ContentItem>()
        for ((index, element) in rawItems.withIndex()) {
            val rawId = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: "#$index"
            val item = try {
                ContentJson.decodeFromJsonElement<ContentItem>(element)
            } catch (t: Throwable) {
                skip(skipped, SkipKind.ITEM, rawId, "undecodable: ${t.message?.take(200)}")
                continue
            }
            val reason = itemError(item, declaredEventIds)
            if (reason != null) {
                skip(skipped, SkipKind.ITEM, item.id, reason)
                continue
            }
            if (acceptedItems.containsKey(item.id)) {
                skip(skipped, SkipKind.ITEM, item.id, "duplicate id")
                continue
            }
            acceptedItems[item.id] = item
        }

        // --- Avatars ---
        val acceptedAvatars = LinkedHashMap<String, ContentAvatar>()
        for ((index, element) in rawAvatars.withIndex()) {
            val obj = element as? JsonObject
            val rawId = (obj?.get("id") as? JsonPrimitive)?.content ?: "#$index"
            val isDefault = runCatching {
                ((obj?.get("unlock") as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "default"
            }.getOrDefault(false)

            val avatar = try {
                ContentJson.decodeFromJsonElement<ContentAvatar>(element)
            } catch (t: Throwable) {
                val reason = "undecodable: ${t.message?.take(200)}"
                if (isDefault) return ValidationResult.Rejected("default avatar $rawId invalid: $reason")
                skip(skipped, SkipKind.AVATAR, rawId, reason)
                continue
            }
            val reason = avatarError(avatar)
            if (reason != null) {
                if (isDefault) return ValidationResult.Rejected("default avatar ${avatar.id} invalid: $reason")
                skip(skipped, SkipKind.AVATAR, avatar.id, reason)
                continue
            }
            if (acceptedAvatars.containsKey(avatar.id)) {
                if (isDefault) {
                    return ValidationResult.Rejected("default avatar ${avatar.id} invalid: duplicate id")
                }
                skip(skipped, SkipKind.AVATAR, avatar.id, "duplicate id")
                continue
            }
            acceptedAvatars[avatar.id] = avatar
        }

        // --- Events ---
        val acceptedEvents = LinkedHashMap<String, ContentEvent>()
        for ((index, element) in rawEvents.withIndex()) {
            val rawId = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: "#$index"
            val event = try {
                ContentJson.decodeFromJsonElement<ContentEvent>(element)
            } catch (t: Throwable) {
                skip(skipped, SkipKind.EVENT, rawId, "undecodable: ${t.message?.take(200)}")
                continue
            }
            if (acceptedEvents.containsKey(event.id)) {
                skip(skipped, SkipKind.EVENT, event.id, "duplicate id")
                continue
            }
            val reason = eventError(event)
            if (reason != null) {
                skip(skipped, SkipKind.EVENT, event.id, reason)
                continue
            }
            val filteredItemIds = event.itemIds.filter { acceptedItems.containsKey(it) }
            if (filteredItemIds.isEmpty()) {
                skip(skipped, SkipKind.EVENT, event.id, "no valid items")
                continue
            }
            acceptedEvents[event.id] = event.copy(itemIds = filteredItemIds)
        }

        // --- Reference checks against accepted items ---
        for (achievementId in appAchievementIds) {
            val itemId = dropRules.achievementRewards[achievementId]
                ?: return ValidationResult.Rejected("achievementRewards missing entry for $achievementId")
            if (!acceptedItems.containsKey(itemId)) {
                return ValidationResult.Rejected("achievementRewards[$achievementId] references unknown item $itemId")
            }
        }
        for (levelReward in dropRules.levelRewards) {
            if (!acceptedItems.containsKey(levelReward.itemId)) {
                return ValidationResult.Rejected("levelRewards references unknown item ${levelReward.itemId}")
            }
        }
        for (starUnlock in dropRules.starUnlocks) {
            if (!acceptedItems.containsKey(starUnlock.itemId)) {
                return ValidationResult.Rejected("starUnlocks references unknown item ${starUnlock.itemId}")
            }
        }
        if (!acceptedItems.containsKey(dropRules.tutorialRewardItemId)) {
            return ValidationResult.Rejected(
                "tutorialRewardItemId references unknown item ${dropRules.tutorialRewardItemId}"
            )
        }
        for (itemId in dropRules.weeklyStreakSetPieceItemIds) {
            if (!acceptedItems.containsKey(itemId)) {
                return ValidationResult.Rejected("weeklyStreakSetPieceItemIds references unknown item $itemId")
            }
        }

        // --- At least one default avatar ---
        if (acceptedAvatars.values.none { it.unlock.type == UnlockType.DEFAULT }) {
            return ValidationResult.Rejected("no default avatar")
        }

        // --- Owned items must stay renderable forever ---
        if (previous != null) {
            val missing = previous.itemsById.keys - acceptedItems.keys
            if (missing.isNotEmpty()) {
                return ValidationResult.Rejected("items from previous missing: ${missing.take(5).joinToString()}")
            }
        }

        val pack = ContentPack(
            schemaVersion = schemaVersion,
            version = version,
            publishedAt = (root["publishedAt"] as? JsonPrimitive)?.content ?: "",
            strings = strings,
            avatars = acceptedAvatars.values.toList(),
            items = acceptedItems.values.toList(),
            events = acceptedEvents.values.toList(),
            dropRules = dropRules
        )
        return ValidationResult.Accepted(pack, skipped)
    }

    private fun skip(into: MutableList<Skip>, kind: SkipKind, id: String, reason: String) {
        println("LixoContent: skip ${kind.name.lowercase()} $id: $reason")
        into.add(Skip(kind, id, reason))
    }

    private fun dropRulesError(rules: DropRules): String? {
        for (rarity in Rarity.entries) {
            val weight = rules.rarityWeights[rarity]
            if (weight == null || weight <= 0) return "rarityWeights missing or <= 0 for $rarity"
        }
        val rare = rules.pity[Rarity.RARE]
        val epic = rules.pity[Rarity.EPIC]
        val legendary = rules.pity[Rarity.LEGENDARY]
        if (rare == null || epic == null || legendary == null) return "pity must have RARE, EPIC and LEGENDARY"
        if (rare <= 0 || epic <= 0 || legendary <= 0) return "pity values must be > 0"
        if (!(rare < epic && epic < legendary)) return "pity must be strictly increasing RARE < EPIC < LEGENDARY"
        if (rules.chestCostHalfPoints <= 0) return "chestCostHalfPoints must be > 0"
        if (rules.progressRunHalfPoints <= 0) return "progressRunHalfPoints must be > 0"
        if (rules.replayRunHalfPoints <= 0) return "replayRunHalfPoints must be > 0"
        val levels = rules.onboarding.levels
        if (levels.any { it < 1 }) return "onboarding.levels must be >= 1"
        for (i in 1 until levels.size) {
            if (levels[i] <= levels[i - 1]) return "onboarding.levels must be strictly increasing"
        }
        if (rules.starUnlocks.any { it.stars < 1 }) return "starUnlocks.stars must be >= 1"
        if (rules.levelRewards.any { it.level < 1 }) return "levelRewards.level must be >= 1"
        val seenLevels = HashSet<Int>()
        for (levelReward in rules.levelRewards) {
            if (!seenLevels.add(levelReward.level)) return "levelRewards has duplicate level ${levelReward.level}"
        }
        return null
    }

    private fun itemError(item: ContentItem, declaredEventIds: Set<String>): String? {
        if (!ID_REGEX.matches(item.id)) return "id does not match ${ID_REGEX.pattern}"
        if (item.nameKey.isBlank()) return "nameKey is blank"
        if (parseContentHex(item.palette.primary) == null) return "palette.primary is not a valid hex colour"
        if (parseContentHex(item.palette.secondary) == null) return "palette.secondary is not a valid hex colour"
        if (parseContentHex(item.palette.accent) == null) return "palette.accent is not a valid hex colour"
        if (item.scale < 0.5f || item.scale > 2f) return "scale out of range"
        if (item.hidesParts.size > MAX_HIDES_PARTS) return "too many hidesParts"
        if (item.hidesParts.any { !PART_REGEX.matches(it) }) return "hidesParts has an invalid part name"
        if (item.layers.isEmpty() || item.layers.size > MAX_LAYERS) return "layers count out of range"
        for (layer in item.layers) {
            val box = STANDARD_BONE_BOX[layer.bone] ?: return "no bone box for ${layer.bone}"
            val reason = layerError(layer, box, allowedRoles = null)
            if (reason != null) return "layer invalid: $reason"
        }
        if (item.pool == ItemPool.EVENT && item.eventId == null) return "pool EVENT requires eventId"
        if (item.eventId != null && item.eventId !in declaredEventIds) return "bad eventId"
        item.preview?.let {
            if (it.yaw < -PI.toFloat() || it.yaw > PI.toFloat()) return "preview.yaw out of range"
        }
        return null
    }

    private fun avatarError(avatar: ContentAvatar): String? {
        if (!ID_REGEX.matches(avatar.id)) return "id does not match ${ID_REGEX.pattern}"
        if (avatar.nameKey.isBlank()) return "nameKey is blank"
        if (parseContentHex(avatar.skinHex) == null) return "skinHex is not a valid hex colour"
        if (parseContentHex(avatar.defaultTintHex) == null) return "defaultTintHex is not a valid hex colour"
        if (avatar.unlock.type == UnlockType.STARS && avatar.unlock.stars < 1) return "unlock.stars must be >= 1"

        for ((bone, override) in avatar.bones) {
            val standard = STANDARD_BONE_BOX[bone] ?: return "no bone box for $bone"
            override.size?.let { size ->
                if (size.size != 2) return "bones.$bone.size must have 2 values"
                if (size[0] < standard.width * 0.25f || size[0] > standard.width * 4f) {
                    return "bones.$bone.size[0] out of range"
                }
                if (size[1] < standard.height * 0.25f || size[1] > standard.height * 4f) {
                    return "bones.$bone.size[1] out of range"
                }
            }
            override.offset?.let { offset ->
                if (offset.size != 2) return "bones.$bone.offset must have 2 values"
                if (abs(offset[0]) > 200f || abs(offset[1]) > 200f) return "bones.$bone.offset out of range"
            }
        }

        listOf(avatar.pose.headBob, avatar.pose.breath, avatar.pose.armSwing, avatar.pose.tailWag).forEach {
            if (it < 0f || it > 3f) return "pose value out of range"
        }
        if (avatar.face.eyeStyles !in 1..8) return "face.eyeStyles out of range"
        if (avatar.face.smileStyles !in 1..8) return "face.smileStyles out of range"

        avatar.palette?.let { palette ->
            if (parseContentHex(palette.primary) == null) return "palette.primary is not a valid hex colour"
            if (parseContentHex(palette.secondary) == null) return "palette.secondary is not a valid hex colour"
            if (parseContentHex(palette.accent) == null) return "palette.accent is not a valid hex colour"
        }
        val allowedRoles = if (avatar.palette != null) AVATAR_PART_ROLES + AVATAR_PALETTE_ROLES else AVATAR_PART_ROLES

        if (avatar.parts.size > MAX_PARTS) return "too many parts"
        val partNames = HashSet<String>()
        for (part in avatar.parts) {
            if (!PART_REGEX.matches(part.part)) return "part name '${part.part}' invalid"
            if (!partNames.add(part.part)) return "duplicate part name '${part.part}'"
            if (part.layers.isEmpty() || part.layers.size > MAX_LAYERS) {
                return "part '${part.part}' layers count out of range"
            }
            for (layer in part.layers) {
                val standard = STANDARD_BONE_BOX[layer.bone] ?: return "no bone box for ${layer.bone}"
                val overrideSize = avatar.bones[layer.bone]?.size
                val effective = if (overrideSize != null && overrideSize.size == 2) {
                    Size(overrideSize[0], overrideSize[1])
                } else {
                    standard
                }
                val reason = layerError(layer, effective, allowedRoles)
                if (reason != null) return "part '${part.part}' layer invalid: $reason"
            }
        }
        return null
    }

    private fun eventError(event: ContentEvent): String? {
        if (!ID_REGEX.matches(event.id)) return "id does not match ${ID_REGEX.pattern}"
        if (event.tz != "local") return "tz must be 'local'"
        val start = try {
            LocalDate.parse(event.start)
        } catch (t: Exception) {
            return "start is not a valid date"
        }
        val end = try {
            LocalDate.parse(event.endExclusive)
        } catch (t: Exception) {
            return "endExclusive is not a valid date"
        }
        if (start >= end) return "start must be before endExclusive"
        event.chestTheme?.let {
            if (parseContentHex(it.bodyHex) == null) return "chestTheme.bodyHex is not a valid hex colour"
            if (parseContentHex(it.trimHex) == null) return "chestTheme.trimHex is not a valid hex colour"
        }
        event.banner?.let {
            if (parseContentHex(it.bgHex) == null) return "banner.bgHex is not a valid hex colour"
            if (parseContentHex(it.fgHex) == null) return "banner.fgHex is not a valid hex colour"
        }
        if (event.dropBoost.eventItemChance < 0f || event.dropBoost.eventItemChance > 1f) {
            return "dropBoost.eventItemChance out of range"
        }
        if (event.dropBoost.presentGiftChance < 0f || event.dropBoost.presentGiftChance > 1f) {
            return "dropBoost.presentGiftChance out of range"
        }
        return null
    }

    private fun layerError(layer: ContentLayer, box: Size, allowedRoles: Set<PaletteRole>?): String? {
        ContentPaths.strictError(layer.d)?.let { return it }
        val nodes = ContentPaths.parseNodes(layer.d)
        val bounds = ContentPaths.bounds(nodes) ?: return "empty path"
        if (!bounds.isInside(expandedBoneBox(box))) return "out of bone box"
        if (layer.fill.shade < -1f || layer.fill.shade > 1f) return "fill.shade out of range"
        layer.stroke?.let {
            if (it.width <= 0f || it.width > 16f) return "stroke.width out of range"
        }
        if (layer.shading == Shading.BLOCK) {
            val depth = layer.depth
            if (depth == null || depth <= 0f || depth > 40f) return "shading BLOCK requires depth in (0,40]"
        }
        layer.parallax?.let {
            if (it < -2f || it > 2f) return "parallax out of range"
        }
        if (layer.mirror == Mirror.X && layer.bone.mirrorOf() == null) return "mirror x requires a mirrorable bone"
        if (allowedRoles != null) {
            if (layer.fill.role !in allowedRoles) return "fill.role not allowed here"
            layer.stroke?.let { if (it.role !in allowedRoles) return "stroke.role not allowed here" }
        }
        return null
    }
}
