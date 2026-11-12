package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.ui.screens.map.mapScrollTargetIndex

class MapScrollTargetIndexTest {

    private fun level(order: Int, id: String = "level_$order") = CampaignLevel(
        id = id,
        courseId = "course",
        order = order,
        title = "Level $order",
        biomeNameSv = "Skogen",
        subject = Subject.SWEDISH,
        difficulty = 1,
        timeRequirementSeconds = 60,
        referenceWpm = 20,
        taskId = "task_$order"
    )

    @Test
    fun emptyList_returnsZero() {
        assertEquals(0, mapScrollTargetIndex(emptyList(), "level_1"))
    }

    @Test
    fun nullCurrentLevelId_returnsZero() {
        val levels = listOf(level(1), level(2), level(3))
        assertEquals(0, mapScrollTargetIndex(levels, null))
    }

    @Test
    fun idNotInList_returnsZero() {
        val levels = listOf(level(1), level(2), level(3))
        assertEquals(0, mapScrollTargetIndex(levels, "level_999"))
    }

    @Test
    fun idOfThirdLevel_returnsIndexTwo() {
        val levels = listOf(level(1), level(2), level(3), level(4))
        assertEquals(2, mapScrollTargetIndex(levels, "level_3"))
    }

    @Test
    fun lastLevelId_returnsLastIndex() {
        val levels = listOf(level(1), level(2), level(3), level(4))
        assertEquals(levels.lastIndex, mapScrollTargetIndex(levels, "level_4"))
    }

    @Test
    fun allLevelsStarred_targetsLastIndexViaEngine() {
        val levels = listOf(level(1), level(2), level(3), level(4))
        val progressByLevelId = levels.associate {
            it.id to LevelProgress(
                profileId = "profile",
                levelId = it.id,
                levelOrder = it.order,
                starsEarned = 3,
                completedInAiMode = false,
                bestTimeSeconds = 30,
                bestWpm = 0
            )
        }
        val engine = ProgressionAndGiftEngine()
        val current = engine.currentLevelForSubject(
            subject = Subject.SWEDISH,
            levels = levels,
            progressByLevelId = progressByLevelId
        )
        assertEquals(levels.lastIndex, mapScrollTargetIndex(levels, current?.id))
    }
}
