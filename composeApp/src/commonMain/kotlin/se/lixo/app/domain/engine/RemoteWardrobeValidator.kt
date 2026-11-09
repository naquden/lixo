package se.lixo.app.domain.engine

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import se.lixo.app.domain.model.WardrobeItem

/**
 * Validates wardrobe items published remotely to Firestore without an app release
 * (`doc/plans/015-remote-wardrobe-items.md`). Mirrors `tools/item_pipeline/upload_wardrobe_items.py`,
 * which must enforce the same rules before anything is uploaded.
 *
 * Remote items only ever ADD to the catalog: they can never clash with a bundled item, join the
 * star curve, replace an achievement reward, or become the weekly-streak set piece, so balance
 * and level/achievement rewards stay entirely app-owned. A single malformed string or item never
 * drops the rest of the batch: each is validated independently and the bad one is logged and
 * skipped.
 */
object RemoteWardrobeValidator {

    /** The only mesh asset shared by every item today; [se.lixo.app.ui.components.avatar3d.Avatar3DController] renders from it. */
    const val REQUIRED_MESH_ASSET = "3d_sample.glb"

    private val ID_PATTERN = Regex("^item_[a-z0-9_]+$")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Decodes each raw JSON string separately (never as a batch), so one undecodable item never
     * fails the whole chunk, then runs [acceptItems] on whatever decoded successfully.
     */
    fun accept(rawItems: List<String>, bundledIds: Set<String>, appVersionCode: Int): List<WardrobeItem> {
        val decoded = rawItems.mapIndexedNotNull { index, raw ->
            try {
                json.decodeFromString<WardrobeItem>(raw)
            } catch (t: Throwable) {
                println("LixoWardrobeSync: skip #$index: undecodable: ${t.message?.take(200)}")
                null
            }
        }
        return acceptItems(decoded, bundledIds, appVersionCode)
    }

    /**
     * Validates already-decoded items: rejects duplicates (first valid item with a given id wins)
     * and anything [rejectReason] flags, logging the reason for each rejection.
     */
    fun acceptItems(items: List<WardrobeItem>, bundledIds: Set<String>, appVersionCode: Int): List<WardrobeItem> {
        val seen = HashSet<String>()
        val accepted = ArrayList<WardrobeItem>()
        for (item in items) {
            val reason = rejectReason(item, bundledIds, appVersionCode)
                ?: if (!seen.add(item.id)) "duplicate id in batch" else null
            if (reason != null) {
                println("LixoWardrobeSync: skip ${item.id}: $reason")
            } else {
                accepted.add(item)
            }
        }
        return accepted
    }

    /** Returns a human-readable rejection reason, or null if [item] is acceptable. */
    fun rejectReason(item: WardrobeItem, bundledIds: Set<String>, appVersionCode: Int): String? {
        if (!ID_PATTERN.matches(item.id)) return "id does not match ${ID_PATTERN.pattern}"
        if (item.id in bundledIds) return "clashes with bundled item"
        if (item.nameSv.isBlank()) return "nameSv is blank"
        if (item.styleIndex !in 0..7) return "styleIndex out of range"
        if (item.meshAssetPath != REQUIRED_MESH_ASSET) return "unsupported meshAssetPath"
        if (item.scaleMultiplier < 0.5f || item.scaleMultiplier > 2f) return "scaleMultiplier out of range"
        if (item.unlockAchievementId != null) return "unlockAchievementId must be null"
        if (item.unlockStarRequirement != 0) return "unlockStarRequirement must be 0"
        if (item.isWeeklyStreakSetPiece) return "isWeeklyStreakSetPiece must be false"
        if (item.primaryColorHex !in 0L..0xFFFFFFFFL) return "primaryColorHex out of range"
        if (item.secondaryColorHex !in 0L..0xFFFFFFFFL) return "secondaryColorHex out of range"
        if (item.minAppVersionCode > appVersionCode) return "needs app version ${item.minAppVersionCode}"
        return null
    }
}
