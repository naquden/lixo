package se.lixo.app

import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.screens.wardrobe.rarityCollectionCounts
import se.lixo.app.ui.theme.LixoStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class WardrobeRarityCountsTest {

    private fun item(id: String, rarity: Rarity): ContentItem = BundledTestContent.item(id, rarity = rarity)

    @Test
    fun rarityCollectionCounts_coversAllRaritiesInOrder() {
        val items = listOf(
            item("common_1", Rarity.COMMON),
            item("common_2", Rarity.COMMON),
            item("legendary_1", Rarity.LEGENDARY)
        )
        val unlocked = setOf("common_1", "legendary_1", "item_does_not_exist")

        val counts = rarityCollectionCounts(items, unlocked)

        assertEquals(Rarity.entries, counts.map { it.rarity })
        assertEquals(1, counts.first { it.rarity == Rarity.COMMON }.owned)
        assertEquals(2, counts.first { it.rarity == Rarity.COMMON }.total)
        assertEquals(1, counts.first { it.rarity == Rarity.LEGENDARY }.owned)
        assertEquals(1, counts.first { it.rarity == Rarity.LEGENDARY }.total)
        for (r in listOf(Rarity.UNCOMMON, Rarity.RARE, Rarity.EPIC)) {
            val c = counts.first { it.rarity == r }
            assertEquals(0, c.owned)
            assertEquals(0, c.total)
        }
    }

    @Test
    fun rarityCollectionCounts_totalsMatchSeedCatalog() {
        val catalog = BundledTestContent.content.items

        val counts = rarityCollectionCounts(catalog, emptySet())

        assertEquals(catalog.size, counts.sumOf { it.total })
        for (c in counts) {
            assertEquals(catalog.count { it.rarity == c.rarity }, c.total)
            assertEquals(0, c.owned)
        }
    }

    @Test
    fun wardrobeRarityCount_format() {
        assertEquals("Legendariska 2/38", LixoStrings.wardrobeRarityCount(Rarity.LEGENDARY, 2, 38))
    }
}
