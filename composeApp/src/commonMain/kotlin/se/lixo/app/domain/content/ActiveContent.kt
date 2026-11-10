package se.lixo.app.domain.content

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.toPath
import se.lixo.app.domain.model.Rarity

/**
 * The currently-active, already-[ContentPackValidator]-validated content pack, indexed for fast
 * lookup by the renderer and the drop engine (020+). Assumes [pack] has already been validated:
 * it does not re-check caps, hex colours, path validity, etc.
 *
 * Not thread-safe: the caches below are plain `HashMap`s, which is fine because this is only
 * ever read from UI/engine code on a single thread, never shared across threads concurrently.
 */
class ActiveContent private constructor(val pack: ContentPack) {

    val version: Int get() = pack.version

    /** Items by id, in pack order. */
    val itemsById: Map<String, ContentItem> = pack.items.associateBy { it.id }

    val avatarsById: Map<String, ContentAvatar> = pack.avatars.associateBy { it.id }

    /** Avatars sorted by their declared `order`, then by id for a stable tie-break. */
    val avatarsOrdered: List<ContentAvatar> =
        pack.avatars.sortedWith(compareBy({ it.order }, { it.id }))

    /** The first avatar in [avatarsOrdered] whose unlock is [UnlockType.DEFAULT]. */
    val defaultAvatar: ContentAvatar =
        avatarsOrdered.first { it.unlock.type == UnlockType.DEFAULT }

    /** All items in the pack, in pack order. */
    val items: List<ContentItem> get() = pack.items

    /** Ids of every avatar whose unlock is [UnlockType.DEFAULT]. */
    val defaultAvatarIds: Set<String> =
        avatarsOrdered.filter { it.unlock.type == UnlockType.DEFAULT }.map { it.id }.toSet()

    /** Minimum star requirement per item id, from `pack.dropRules.starUnlocks`. */
    val starRequirementByItemId: Map<String, Int> =
        pack.dropRules.starUnlocks
            .groupBy { it.itemId }
            .mapValues { (_, unlocks) -> unlocks.minOf { it.stars } }

    /** Every item id handed out as an achievement reward. */
    val achievementRewardItemIds: Set<String> = pack.dropRules.achievementRewards.values.toSet()

    private val levelRewardByLevel: Map<Int, String> =
        pack.dropRules.levelRewards.associate { it.level to it.itemId }

    /** The curated reward item id for campaign level [levelOrder], or null if there isn't one. */
    fun levelRewardItemId(levelOrder: Int): String? = levelRewardByLevel[levelOrder]

    /** Localized display name for [item]. */
    fun itemName(item: ContentItem): String = string(item.nameKey)

    /** Localized display name for the avatar with [avatarId], falling back to [defaultAvatar]. */
    fun avatarName(avatarId: String): String = string((avatarsById[avatarId] ?: defaultAvatar).nameKey)

    /**
     * [profileUnlocked] (a profile's persisted unlocked avatar ids) unioned with
     * [defaultAvatarIds], filtered to avatar ids that still exist in this pack.
     */
    fun unlockedAvatarIds(profileUnlocked: Set<String>): Set<String> =
        defaultAvatarIds + profileUnlocked.filter { it in avatarsById }

    /**
     * Items available to drop normally, by rarity: `pool == STANDARD && !retired`. Every
     * [Rarity] is present as a key, even if its list is empty.
     */
    val standardPoolByRarity: Map<Rarity, List<ContentItem>> = Rarity.entries.associateWith { rarity ->
        pack.items.filter { it.rarity == rarity && it.pool == ItemPool.STANDARD && !it.retired }
    }

    val eventsById: Map<String, ContentEvent> = pack.events.associateBy { it.id }

    val dropRules: DropRules = pack.dropRules

    /** Word lists for `se.lixo.app.domain.social.Nickname.render`/`randomNickname`. */
    val nicknameWords: NicknameWords = pack.nicknameWords

    /** Looks up a localized string, falling back to Swedish, then to [key] itself. */
    fun string(key: String, lang: String = "sv"): String =
        pack.strings[lang]?.get(key) ?: pack.strings["sv"]?.get(key) ?: key

    // Caches keyed by the layer's `d` string, which is equivalent to layer identity for drawing
    // purposes (two layers with the same `d` always parse/draw identically).
    private val nodesByD = HashMap<String, List<PathNode>>()
    private val boundsByD = HashMap<String, Rect>()
    private val pathByD = HashMap<String, Path>()
    private val itemBoundsCache = HashMap<String, Rect?>()

    /** The parsed [PathNode]s for [layer]'s `d` string, cached by `d`. */
    fun nodes(layer: ContentLayer): List<PathNode> =
        nodesByD.getOrPut(layer.d) { ContentPaths.parseNodes(layer.d) }

    /**
     * A lazily-built, cached `androidx.compose.ui.graphics.Path` for [layer]. This is the ONLY
     * place in this file that creates a `Path`: callers must not mutate the returned instance,
     * since it is shared via [pathByD]. Never call this from `commonTest`: on the host JVM,
     * `Path()` is backed by `AndroidPath`, which throws against the android.jar stubs.
     */
    fun parsedPath(layer: ContentLayer): Path =
        pathByD.getOrPut(layer.d) { nodes(layer).toPath() }

    /** [layer]'s bone-local bounding box (au), from [ContentPaths.bounds]. */
    fun layerBounds(layer: ContentLayer): Rect =
        boundsByD.getOrPut(layer.d) {
            ContentPaths.bounds(nodes(layer)) ?: Rect.Zero
        }

    /**
     * The bone-local union of bounding boxes over every layer of item [itemId], including a
     * mirrored copy (via [ContentPaths.mirrorX]) for each layer with `mirror == Mirror.X`. This
     * is a union across different bones' local spaces: 020's renderer is responsible for
     * placing each bone before using these bounds for e.g. hit-testing or camera framing. Null
     * if [itemId] is unknown. Computed once and cached.
     */
    fun itemBounds(itemId: String): Rect? = itemBoundsCache.getOrPut(itemId) {
        val item = itemsById[itemId] ?: return@getOrPut null
        var union: Rect? = null
        for (layer in item.layers) {
            val bounds = layerBounds(layer)
            union = if (union == null) bounds else ContentPaths.union(union, bounds)
            if (layer.mirror == Mirror.X) {
                val mirrored = ContentPaths.mirrorX(bounds, STANDARD_BONE_BOX[layer.bone]!!.width)
                union = ContentPaths.union(union!!, mirrored)
            }
        }
        union
    }

    companion object {
        /** Builds an [ActiveContent] from an already-validated [pack]. Never creates a [Path]. */
        fun from(pack: ContentPack): ActiveContent = ActiveContent(pack)
    }
}
