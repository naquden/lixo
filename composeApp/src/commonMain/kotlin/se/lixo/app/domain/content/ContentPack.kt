package se.lixo.app.domain.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/**
 * Lenient JSON for decoding content packs (`doc/content/content-pack.md`): unknown keys are
 * ignored so older/newer app builds can read a pack written by a different pipeline version.
 *
 * Deliberately NEVER sets `coerceInputValues`: that would silently turn an unrecognised enum
 * value (an unknown `pattern`, `ornament`, `slot`, ...) into its default value instead of making
 * the surrounding entry fail to decode, which [ContentPackValidator] relies on to skip just that
 * entry.
 */
val ContentJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val HEX_COLOR_REGEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

/**
 * Parses a `#RRGGBB` or `#AARRGGBB` hex colour into an ARGB [Long]. A 6-digit value gets full
 * (`0xFF`) alpha. Returns null if [hex] doesn't match the expected shape.
 */
fun parseContentHex(hex: String): Long? {
    val match = HEX_COLOR_REGEX.matchEntire(hex) ?: return null
    val digits = match.groupValues[1]
    val rgb = digits.toLong(16)
    return if (digits.length == 6) 0xFF000000L or rgb else rgb
}

/** One versioned content pack: every avatar, wardrobe item, event and drop rule (schema v1). */
@Serializable
data class ContentPack(
    val schemaVersion: Int,
    val version: Int,
    val publishedAt: String = "",
    val strings: Map<String, Map<String, String>> = emptyMap(),
    val avatars: List<ContentAvatar> = emptyList(),
    val items: List<ContentItem> = emptyList(),
    val events: List<ContentEvent> = emptyList(),
    val dropRules: DropRules,
    /** doc/plans/030: word lists used to render a friend's generated nickname (`Nickname.render`). */
    val nicknameWords: NicknameWords = NicknameWords()
)

/**
 * Swedish adjective/animal word lists (doc/plans/030, doc/content/content-pack.md) used by
 * `se.lixo.app.domain.social.Nickname` to render a fallback display name (`adj + animal + num`,
 * e.g. "ModigRäv42") for a friend card, so no free text is ever stored in the cloud. Defaults to
 * empty lists so a pack from before this field existed still parses.
 */
@Serializable
data class NicknameWords(
    val adjectives: List<String> = emptyList(),
    val animals: List<String> = emptyList()
)

@Serializable
data class ContentAvatar(
    val id: String,
    val nameKey: String,
    val order: Int = 0,
    val humanoid: Boolean = false,
    val skinHex: String,
    val defaultTintHex: String,
    val unlock: AvatarUnlock,
    val bones: Map<Bone, BoneOverride> = emptyMap(),
    val pose: AvatarPose = AvatarPose(),
    val parts: List<AvatarPart> = emptyList(),
    val face: AvatarFace = AvatarFace(),
    /** Colours for PRIMARY/SECONDARY/ACCENT roles on this avatar's own part layers (hair, beard, muzzle). */
    val palette: ContentPalette? = null
)

@Serializable
data class AvatarUnlock(
    val type: UnlockType,
    val stars: Int = 0
)

@Serializable
enum class UnlockType {
    @SerialName("default") DEFAULT,
    @SerialName("stars") STARS
}

@Serializable
data class BoneOverride(
    val size: List<Float>? = null,
    val offset: List<Float>? = null
)

@Serializable
data class AvatarPose(
    val headBob: Float = 1f,
    val breath: Float = 1f,
    val armSwing: Float = 1f,
    val tailWag: Float = 1f
)

@Serializable
data class AvatarPart(
    val part: String,
    val layers: List<ContentLayer>
)

@Serializable
data class AvatarFace(
    val eyeStyles: Int = 4,
    val smileStyles: Int = 4
)

@Serializable
data class ContentItem(
    val id: String,
    val slot: EquipmentSlot,
    val rarity: Rarity,
    val subjectTheme: Subject,
    val nameKey: String,
    val descKey: String = "",
    val palette: ContentPalette,
    val pattern: ItemPattern = ItemPattern.NONE,
    val ornament: ItemOrnament = ItemOrnament.NONE,
    val scale: Float = 1f,
    val hidesParts: List<String> = emptyList(),
    val layers: List<ContentLayer>,
    val pool: ItemPool = ItemPool.STANDARD,
    val eventId: String? = null,
    val retired: Boolean = false,
    val preview: ItemPreviewHints? = null
)

@Serializable
data class ContentPalette(
    val primary: String,
    val secondary: String,
    val accent: String
)

@Serializable
data class ItemPreviewHints(
    val ghostBody: Boolean = false,
    val yaw: Float = 0.35f
)

@Serializable
enum class ItemPool {
    @SerialName("standard") STANDARD,
    @SerialName("event") EVENT,
    @SerialName("achievement") ACHIEVEMENT,
    @SerialName("level") LEVEL,
    @SerialName("setpiece") SETPIECE,
    @SerialName("none") NONE
}

@Serializable
data class ContentLayer(
    val bone: Bone,
    val z: ZBucket,
    val order: Int = 0,
    val d: String,
    val fill: LayerFill,
    val stroke: LayerStroke? = null,
    val shading: Shading = Shading.GRADIENT,
    val depth: Float? = null,
    val parallax: Float? = null,
    val mirror: Mirror = Mirror.NONE,
    val patternClip: Boolean = false,
    val breath: Boolean = false
)

@Serializable
data class LayerFill(
    val role: PaletteRole,
    val shade: Float = 0f
)

@Serializable
data class LayerStroke(
    val role: PaletteRole,
    val width: Float
)

@Serializable
enum class PaletteRole {
    @SerialName("primary") PRIMARY,
    @SerialName("secondary") SECONDARY,
    @SerialName("accent") ACCENT,
    @SerialName("skin") SKIN,
    @SerialName("tint") TINT,
    @SerialName("eye") EYE,
    @SerialName("outline") OUTLINE
}

@Serializable
enum class Shading {
    @SerialName("flat") FLAT,
    @SerialName("gradient") GRADIENT,
    @SerialName("block") BLOCK
}

@Serializable
enum class Mirror {
    @SerialName("none") NONE,
    @SerialName("x") X
}

@Serializable
data class ContentEvent(
    val id: String,
    val nameKey: String,
    val start: String,
    val endExclusive: String,
    val tz: String = "local",
    val itemIds: List<String>,
    val chestTheme: ChestTheme? = null,
    val banner: EventBanner? = null,
    val dropBoost: DropBoost = DropBoost(),
    val afterEnd: AfterEnd = AfterEnd.RETIRE
)

@Serializable
data class ChestTheme(
    val bodyHex: String,
    val trimHex: String,
    val ornament: ItemOrnament = ItemOrnament.NONE
)

@Serializable
data class EventBanner(
    val titleKey: String,
    val bgHex: String,
    val fgHex: String
)

@Serializable
data class DropBoost(
    val eventItemChance: Float = 0f,
    val presentGiftChance: Float = 0f
)

@Serializable
enum class AfterEnd {
    @SerialName("retire") RETIRE,
    @SerialName("joinStandard") JOIN_STANDARD
}

@Serializable
data class DropRules(
    val rarityWeights: Map<Rarity, Int>,
    val chestCostHalfPoints: Int,
    val progressRunHalfPoints: Int,
    val replayRunHalfPoints: Int,
    val pity: Map<Rarity, Int>,
    val onboarding: OnboardingRules = OnboardingRules(),
    val starUnlocks: List<StarUnlock> = emptyList(),
    val levelRewards: List<LevelReward> = emptyList(),
    val achievementRewards: Map<String, String> = emptyMap(),
    val tutorialRewardItemId: String,
    val weeklyStreakSetPieceItemIds: List<String> = emptyList()
)

@Serializable
data class OnboardingRules(
    val levels: List<Int> = emptyList()
)

@Serializable
data class StarUnlock(
    val stars: Int,
    val itemId: String
)

@Serializable
data class LevelReward(
    val level: Int,
    val itemId: String
)
