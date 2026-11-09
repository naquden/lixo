package se.lixo.app.data.local

import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.createPlatformLixoDatabase

/**
 * Local on-device cache of the remote wardrobe items downloaded from Firebase
 * (`doc/plans/015-remote-wardrobe-items.md`). Holds ONLY remote items, never the bundled catalog.
 * Backed by Room when a platform database is available ([RoomWardrobeCatalogCache]), and absent
 * (`null`) otherwise, in which case [se.lixo.app.data.repository.LixoRepository] stays purely
 * in-memory. [upsert] never deletes: taking an item out of drops is done with `retired = true`.
 */
/**
 * The remote catalog version plus the app versionCode whose validator accepted it (doc/plans/018).
 */
data class CachedWardrobeVersion(val version: Int, val appVersionCode: Int)

interface WardrobeCatalogCache {
    suspend fun loadVersion(): CachedWardrobeVersion?
    suspend fun loadItems(): List<WardrobeItem>
    suspend fun upsert(version: Int, appVersionCode: Int, items: List<WardrobeItem>)
}

class RoomWardrobeCatalogCache(private val dao: LixoDao) : WardrobeCatalogCache {
    override suspend fun loadVersion(): CachedWardrobeVersion? =
        dao.getWardrobeCatalogMeta()?.let { CachedWardrobeVersion(it.version, it.appVersionCode) }

    override suspend fun loadItems(): List<WardrobeItem> =
        dao.getAllWardrobeItems().mapNotNull { it.toDomainOrNull() }

    override suspend fun upsert(version: Int, appVersionCode: Int, items: List<WardrobeItem>) {
        dao.upsertRemoteCatalog(version, appVersionCode, items.map { it.toEntity() })
    }
}

private fun WardrobeItemEntity.toDomainOrNull(): WardrobeItem? {
    val slot = EquipmentSlot.entries.firstOrNull { it.name == slotId } ?: return null
    val rarity = Rarity.entries.firstOrNull { it.name == rarityId } ?: return null
    val subject = Subject.entries.firstOrNull { it.name == subjectId } ?: return null
    val itemPattern = ItemPattern.entries.firstOrNull { it.name == pattern } ?: return null
    val itemOrnament = ItemOrnament.entries.firstOrNull { it.name == ornament } ?: return null
    return WardrobeItem(
        id = id,
        nameSv = nameSv,
        descriptionSv = descriptionSv,
        slot = slot,
        rarity = rarity,
        subjectTheme = subject,
        meshAssetPath = meshAssetPath,
        meshNodeName = meshNodeName,
        scaleMultiplier = scaleMultiplier,
        unlockStarRequirement = unlockStarRequirement,
        unlockAchievementId = unlockAchievementId,
        isWeeklyStreakSetPiece = isWeeklyStreakSetPiece,
        primaryColorHex = primaryColorHex,
        styleIndex = styleIndex,
        secondaryColorHex = secondaryColorHex,
        pattern = itemPattern,
        ornament = itemOrnament,
        retired = retired,
        minAppVersionCode = minAppVersionCode
    )
}

private fun WardrobeItem.toEntity(): WardrobeItemEntity = WardrobeItemEntity(
    id = id,
    nameSv = nameSv,
    descriptionSv = descriptionSv,
    slotId = slot.name,
    rarityId = rarity.name,
    subjectId = subjectTheme.name,
    meshAssetPath = meshAssetPath,
    meshNodeName = meshNodeName,
    scaleMultiplier = scaleMultiplier,
    unlockStarRequirement = unlockStarRequirement,
    unlockAchievementId = unlockAchievementId,
    isWeeklyStreakSetPiece = isWeeklyStreakSetPiece,
    primaryColorHex = primaryColorHex,
    styleIndex = styleIndex,
    secondaryColorHex = secondaryColorHex,
    pattern = pattern.name,
    ornament = ornament.name,
    retired = retired,
    minAppVersionCode = minAppVersionCode
)

fun createPlatformWardrobeCatalogCache(): WardrobeCatalogCache? =
    createPlatformLixoDatabase()?.let { RoomWardrobeCatalogCache(it.lixoDao()) }
