package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.domain.engine.GiftRollOutcome
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.components.celebration.CelebrationStep
import se.lixo.app.ui.components.celebration.buildCelebrationSteps

class CelebrationStepsTest {

    private fun item(id: String) = WardrobeItem(
        id = id,
        nameSv = "Item $id",
        descriptionSv = "Desc $id",
        slot = EquipmentSlot.HAT,
        rarity = Rarity.COMMON,
        subjectTheme = Subject.SWEDISH
    )

    private fun outcome(
        items: List<WardrobeItem> = emptyList(),
        avatars: Set<AvatarType> = emptySet(),
        gift: GiftRollOutcome? = null
    ) = LevelCompletionOutcome(
        updatedProfile = ChildProfile(id = "p", displayName = "T"),
        updatedLevelProgress = LevelProgress(
            profileId = "p",
            levelId = "level_1",
            levelOrder = 1,
            starsEarned = 3,
            completedInAiMode = true,
            bestTimeSeconds = 10,
            bestWpm = 40
        ),
        netNewStarsAwarded = 3,
        newlyUnlockedAvatars = avatars,
        newlyUnlockedItems = items,
        giftOutcome = gift,
        streakIncrementedToday = false
    )

    @Test
    fun noItems_onlyStars() {
        val steps = buildCelebrationSteps(outcome())
        assertEquals(listOf(CelebrationStep.Stars), steps)
    }

    @Test
    fun twoItems_chestAndEquipPerItemInOrder() {
        val a = item("a")
        val b = item("b")
        val steps = buildCelebrationSteps(outcome(items = listOf(a, b)))
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a),
                CelebrationStep.EquipPrompt(a),
                CelebrationStep.Chest(b),
                CelebrationStep.EquipPrompt(b)
            ),
            steps
        )
    }

    @Test
    fun avatarsAndItem_avatarsSortedByUnlockStarsRequired() {
        val a = item("a")
        val steps = buildCelebrationSteps(
            outcome(items = listOf(a), avatars = setOf(AvatarType.KNIGHT, AvatarType.DOG))
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a),
                CelebrationStep.EquipPrompt(a),
                CelebrationStep.AvatarPrompt(AvatarType.DOG),
                CelebrationStep.AvatarPrompt(AvatarType.KNIGHT)
            ),
            steps
        )
    }

    @Test
    fun duplicateGiftWithNoItems_onlyStars() {
        val duplicateRoll = item("dup")
        val steps = buildCelebrationSteps(
            outcome(
                gift = GiftRollOutcome(
                    rolledItem = duplicateRoll,
                    isDuplicate = true,
                    newlyUnlockedItem = null
                )
            )
        )
        assertEquals(listOf(CelebrationStep.Stars), steps)
    }

    @Test
    fun duplicateGiftPlusNewTaskRewardItem_chestForRewardOnly() {
        val reward = item("reward")
        val duplicateRoll = item("dup")
        val steps = buildCelebrationSteps(
            outcome(
                items = listOf(reward),
                gift = GiftRollOutcome(
                    rolledItem = duplicateRoll,
                    isDuplicate = true,
                    newlyUnlockedItem = null
                )
            )
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(reward),
                CelebrationStep.EquipPrompt(reward)
            ),
            steps
        )
    }
}
