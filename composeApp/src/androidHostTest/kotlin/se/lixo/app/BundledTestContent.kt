package se.lixo.app

import java.io.File
import kotlin.test.fail
import se.lixo.app.data.repository.ContentRepository
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentPack
import se.lixo.app.domain.content.ContentPackValidator
import se.lixo.app.domain.content.ContentPalette
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.LevelReward
import se.lixo.app.domain.content.StarUnlock
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/**
 * The real bundled content pack, loaded from `java.io.File` (Compose `Res` is not on the
 * androidHostTest classpath) and validated exactly like the app does. Backs every engine/catalog
 * host test that needs the full bundled catalog, replacing the hand-written Kotlin seed catalog
 * these tests used before 022.
 */
object BundledTestContent {

    fun findBundledPackFile(): File {
        val relative = listOf(
            "src/commonMain/composeResources/files/content_pack.json",
            "composeApp/src/commonMain/composeResources/files/content_pack.json"
        )
        var dir: File? = File(".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (r in relative) {
                val candidate = File(dir, r)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
            depth++
        }
        fail("could not locate the bundled content_pack.json walking up from ${File(".").absolutePath}")
    }

    val json: String by lazy { findBundledPackFile().readText(Charsets.UTF_8) }

    val content: ActiveContent by lazy {
        val result = ContentPackValidator.validate(json, appAchievementIds(), previous = null, requireNewerVersion = false)
        val accepted = when (result) {
            is ValidationResult.Accepted -> result
            is ValidationResult.Rejected -> fail("bundled pack rejected: ${result.reason}")
        }
        ActiveContent.from(accepted.pack)
    }

    private fun appAchievementIds(): Set<String> =
        se.lixo.app.data.repository.LixoRepository.seedAchievementIds()

    fun repository(): ContentRepository = ContentRepository(content)

    /** [content] with its `dropRules` replaced by [transform] (doc/plans/023). */
    fun withDropRules(transform: (se.lixo.app.domain.content.DropRules) -> se.lixo.app.domain.content.DropRules): ActiveContent =
        ActiveContent.from(content.pack.copy(dropRules = transform(content.dropRules)))

    /**
     * Builds a synthetic [ActiveContent] sharing [content]'s strings/avatars but with
     * caller-supplied [items]/drop rules, for engine tests that need a tightly controlled pool.
     */
    fun contentOf(
        items: List<ContentItem>,
        starUnlocks: List<StarUnlock> = emptyList(),
        weeklyStreakSetPieceItemIds: List<String> = emptyList(),
        achievementRewards: Map<String, String> = emptyMap(),
        levelRewards: List<LevelReward> = emptyList()
    ): ActiveContent {
        val pack = ContentPack(
            schemaVersion = 1,
            version = 1,
            strings = content.pack.strings,
            avatars = content.pack.avatars,
            items = items,
            dropRules = content.dropRules.copy(
                starUnlocks = starUnlocks,
                weeklyStreakSetPieceItemIds = weeklyStreakSetPieceItemIds,
                achievementRewards = achievementRewards,
                levelRewards = levelRewards
            )
        )
        return ActiveContent.from(pack)
    }

    fun item(
        id: String,
        slot: EquipmentSlot = EquipmentSlot.HAT,
        rarity: Rarity = Rarity.COMMON,
        subject: Subject = Subject.SWEDISH,
        pool: ItemPool = ItemPool.STANDARD,
        retired: Boolean = false
    ): ContentItem = ContentItem(
        id = id,
        slot = slot,
        rarity = rarity,
        subjectTheme = subject,
        nameKey = "$id.name",
        palette = ContentPalette("#FFFFFF", "#FFFFFF", "#FFFFFF"),
        pattern = ItemPattern.NONE,
        ornament = ItemOrnament.NONE,
        layers = emptyList(),
        pool = pool,
        retired = retired
    )
}
