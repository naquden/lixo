package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import se.lixo.app.BundledTestContent
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.AfterEnd
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.UnlockType
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity

/**
 * `androidHostTest` (JVM, `withHostTest {}`): reads the bundled `content_pack.json` snapshot via
 * `java.io.File` (Compose resources/`Res` are not on this classpath) through
 * [BundledTestContent], validates it with [ContentPackValidator] exactly like the app would, and
 * asserts the pack-level invariants the app depends on (022): avatar unlock thresholds, the
 * level reward schedule, the achievement reward links, and the onboarding/rarity-weight config.
 */
class BundledPackValidTest {

    @Test
    fun bundledPackIsAcceptedWithZeroSkips() {
        val json = BundledTestContent.json
        val achievementIds = LixoRepository.seedAchievementIds()
        val result = ContentPackValidator.validate(json, achievementIds, previous = null, requireNewerVersion = false)

        val accepted = when (result) {
            is ValidationResult.Accepted -> result
            is ValidationResult.Rejected -> fail("bundled pack rejected: ${result.reason}")
        }

        if (accepted.skipped.isNotEmpty()) {
            accepted.skipped.forEach { println("LixoContent skip: ${it.kind} ${it.id}: ${it.reason}") }
            fail("bundled pack has ${accepted.skipped.size} skips, expected 0")
        }

        val pack = accepted.pack
        assertEquals(6, pack.avatars.size)
        assertTrue(pack.items.size >= 580, "expected >= 580 items, got ${pack.items.size}")

        assertEquals("item_hat_starter_explorer", pack.dropRules.tutorialRewardItemId)

        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 18, 20, 23, 26, 29, 32, 35, 38),
            pack.dropRules.onboarding.levels
        )

        assertEquals(
            mapOf(
                Rarity.COMMON to 40,
                Rarity.UNCOMMON to 28,
                Rarity.RARE to 18,
                Rarity.EPIC to 10,
                Rarity.LEGENDARY to 4
            ),
            pack.dropRules.rarityWeights
        )

        assertEquals(20, pack.dropRules.chestCostHalfPoints)
        assertEquals(2, pack.dropRules.progressRunHalfPoints)
        assertEquals(1, pack.dropRules.replayRunHalfPoints)
        assertEquals(
            mapOf(Rarity.RARE to 4, Rarity.EPIC to 10, Rarity.LEGENDARY to 20),
            pack.dropRules.pity
        )

        val itemIds = pack.items.map { it.id }.toSet()

        // Achievement reward ids match the app's seeded ladder ids exactly, and every reward
        // item actually exists in the pack.
        assertEquals(achievementIds, pack.dropRules.achievementRewards.keys)
        for ((achId, itemId) in pack.dropRules.achievementRewards) {
            assertTrue(itemId in itemIds, "achievement $achId rewards unknown item $itemId")
        }

        // Avatar unlock map matches the documented star curve exactly.
        val expectedUnlocks = mapOf(
            "avatar_cat" to null,
            "avatar_dog" to 12,
            "avatar_knight" to 30,
            "avatar_princess" to 55,
            "avatar_wizard" to 90,
            "avatar_witch" to 135
        )
        val avatarsById = pack.avatars.associateBy { it.id }
        assertEquals(expectedUnlocks.keys, avatarsById.keys)
        for ((avatarId, stars) in expectedUnlocks) {
            val avatar = avatarsById.getValue(avatarId)
            if (stars == null) {
                assertEquals(UnlockType.DEFAULT, avatar.unlock.type, "$avatarId should be the default avatar")
            } else {
                assertEquals(UnlockType.STARS, avatar.unlock.type, "$avatarId should unlock by stars")
                assertEquals(stars, avatar.unlock.stars, "$avatarId unlock stars mismatch")
            }
        }

        // Level rewards cover every campaign level 1..160 with an id present in the pack.
        val levelRewardsByLevel = pack.dropRules.levelRewards.associate { it.level to it.itemId }
        for (level in 1..160) {
            val itemId = levelRewardsByLevel[level]
            assertTrue(itemId != null, "no levelReward for level $level")
            assertTrue(itemId in itemIds, "levelReward for level $level points at unknown item $itemId")
        }

        // Sanity: every palette hex actually parses.
        for (item in pack.items) {
            assertTrue(parseContentHex(item.palette.primary) != null)
            assertTrue(parseContentHex(item.palette.secondary) != null)
            assertTrue(parseContentHex(item.palette.accent) != null)
        }

        // Jul 2026 holiday special (025): one event, 9 items covering every slot once.
        val julEvent = pack.events.single { it.id == "event_jul_2026" }
        assertEquals("2026-12-01", julEvent.start)
        assertEquals("2026-12-27", julEvent.endExclusive)
        assertEquals(AfterEnd.RETIRE, julEvent.afterEnd)
        assertEquals(0.5f, julEvent.dropBoost.eventItemChance)
        assertEquals(0.5f, julEvent.dropBoost.presentGiftChance)
        assertTrue(julEvent.chestTheme != null)
        val banner = julEvent.banner
        assertTrue(banner != null)
        assertEquals("God Jul!", pack.strings.getValue("sv").getValue(banner!!.titleKey))

        assertEquals(9, julEvent.itemIds.size)
        val julItems = julEvent.itemIds.map { pack.items.first { item -> item.id == it } }
        for (item in julItems) {
            assertEquals(ItemPool.EVENT, item.pool)
            assertEquals("event_jul_2026", item.eventId)
        }
        assertEquals(EquipmentSlot.entries.toSet(), julItems.map { it.slot }.toSet())
        assertEquals(EquipmentSlot.entries.size, julItems.map { it.slot }.distinct().size)
    }
}
