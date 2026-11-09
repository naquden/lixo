package se.lixo.app.drops

import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.ContentPalette
import se.lixo.app.domain.content.DropRules
import se.lixo.app.domain.content.ItemPool
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.engine.EventSnapshot
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/**
 * Drop-rules test fixtures (doc/plans/023), built on the mini [SampleContentPack] fixture:
 * commonTest cannot see `androidHostTest`'s `BundledTestContent` (it reads `java.io.File`), so
 * every `se.lixo.app.drops` test builds its [ActiveContent] from here instead. Never creates a
 * `androidx.compose.ui.graphics.Path`.
 */
object DropTestContent {

    val base: ActiveContent = SampleContentPack.activeContent()

    /** [base] with its `dropRules` replaced by [transform]. */
    fun withRules(transform: (DropRules) -> DropRules): ActiveContent =
        ActiveContent.from(base.pack.copy(dropRules = transform(base.dropRules)))

    /** [base] with [extra] items appended and `dropRules` optionally replaced by [transform]. */
    fun withItems(extra: List<ContentItem>, transform: (DropRules) -> DropRules = { it }): ActiveContent =
        ActiveContent.from(base.pack.copy(items = base.pack.items + extra, dropRules = transform(base.dropRules)))

    fun item(
        id: String,
        rarity: Rarity = Rarity.COMMON,
        slot: EquipmentSlot = EquipmentSlot.HAT,
        subject: Subject = Subject.SWEDISH,
        pool: ItemPool = ItemPool.STANDARD,
        retired: Boolean = false,
        eventId: String? = null
    ): ContentItem = ContentItem(
        id = id,
        slot = slot,
        rarity = rarity,
        subjectTheme = subject,
        nameKey = "$id.name",
        palette = ContentPalette("#FFFFFF", "#FFFFFF", "#FFFFFF"),
        layers = emptyList(),
        pool = pool,
        retired = retired,
        eventId = eventId
    )

    /**
     * Runs a first clear (never a replay) of a synthetic level `order` through
     * [ProgressionAndGiftEngine.recordLevelCompletion], matching the `level(order, subject)`
     * helper in `LixoEnginesTest.kt`.
     */
    fun firstClear(
        engine: ProgressionAndGiftEngine,
        content: ActiveContent,
        order: Int,
        clearedBefore: Int,
        unlocked: Set<String> = emptySet(),
        profile: ChildProfile = ChildProfile(id = "p", displayName = "T"),
        stars: Int = 1,
        eligibleForGift: Boolean = false,
        defs: List<Achievement> = emptyList(),
        today: DailyActivityRecord = DailyActivityRecord("p", "2026-11-01"),
        subject: Subject = Subject.SWEDISH,
        events: EventSnapshot = EventSnapshot.NONE
    ): LevelCompletionOutcome {
        val level = CampaignLevel(
            id = "level_$order",
            courseId = "course_$order",
            order = order,
            title = "Bana $order",
            biomeNameSv = "Skogen",
            subject = subject,
            difficulty = 1,
            timeRequirementSeconds = 30,
            referenceWpm = 40,
            taskId = "task_$order"
        )
        return engine.recordLevelCompletion(
            profile = profile,
            level = level,
            previousProgress = null,
            earnedStarsThisRun = stars,
            completedInAiMode = true,
            elapsedSeconds = 20,
            achievedWpm = 0,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = eligibleForGift,
            clearedLevelsCountBeforeRun = clearedBefore,
            content = content,
            currentlyUnlockedItemIds = unlocked,
            todayActivityBeforeRun = today,
            nowEpochMillis = 1000L,
            achievementDefinitions = defs,
            events = events
        )
    }
}
