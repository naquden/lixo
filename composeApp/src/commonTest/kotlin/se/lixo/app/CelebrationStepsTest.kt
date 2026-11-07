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
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.UnlockedReward
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.components.celebration.CelebrationStep
import se.lixo.app.ui.components.celebration.buildCelebrationSteps
import se.lixo.app.ui.theme.LixoStrings

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
        rewards: List<UnlockedReward> = emptyList(),
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
        newlyUnlockedRewards = rewards,
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
        val steps = buildCelebrationSteps(
            outcome(rewards = listOf(UnlockedReward(a, RewardSource.LEVEL), UnlockedReward(b, RewardSource.LEVEL)))
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a, RewardSource.LEVEL),
                CelebrationStep.EquipPrompt(a),
                CelebrationStep.Chest(b, RewardSource.LEVEL),
                CelebrationStep.EquipPrompt(b)
            ),
            steps
        )
    }

    @Test
    fun avatarsAndItem_avatarsSortedByUnlockStarsRequired() {
        val a = item("a")
        val steps = buildCelebrationSteps(
            outcome(
                rewards = listOf(UnlockedReward(a, RewardSource.LEVEL)),
                avatars = setOf(AvatarType.KNIGHT, AvatarType.DOG)
            )
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a, RewardSource.LEVEL),
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
                rewards = listOf(UnlockedReward(reward, RewardSource.LEVEL)),
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
                CelebrationStep.Chest(reward, RewardSource.LEVEL),
                CelebrationStep.EquipPrompt(reward)
            ),
            steps
        )
    }

    @Test
    fun stackedSources_oneChestPerRewardWithMatchingSourceInOrder() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val steps = buildCelebrationSteps(
            outcome(
                rewards = listOf(
                    UnlockedReward(a, RewardSource.LEVEL),
                    UnlockedReward(b, RewardSource.FLITKISTA),
                    UnlockedReward(c, RewardSource.WEEKLY_STREAK)
                )
            )
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a, RewardSource.LEVEL),
                CelebrationStep.EquipPrompt(a),
                CelebrationStep.Chest(b, RewardSource.FLITKISTA),
                CelebrationStep.EquipPrompt(b),
                CelebrationStep.Chest(c, RewardSource.WEEKLY_STREAK),
                CelebrationStep.EquipPrompt(c)
            ),
            steps
        )
    }

    @Test
    fun achievementReward_carriesLabelDetailSvOntoChestStep() {
        val a = item("a")
        val steps = buildCelebrationSteps(
            outcome(rewards = listOf(UnlockedReward(a, RewardSource.ACHIEVEMENT, "Blixtläsaren")))
        )
        assertEquals(
            listOf(
                CelebrationStep.Stars,
                CelebrationStep.Chest(a, RewardSource.ACHIEVEMENT, "Blixtläsaren"),
                CelebrationStep.EquipPrompt(a)
            ),
            steps
        )
    }

    @Test
    fun rewardSourceLabel_achievementWithDetail_appendsTitle() {
        assertEquals(
            "Prestation: Blixtläsaren",
            LixoStrings.rewardSourceLabel(RewardSource.ACHIEVEMENT, "Blixtläsaren")
        )
        assertEquals("Prestation", LixoStrings.rewardSourceLabel(RewardSource.ACHIEVEMENT, null))
        assertEquals("Prestation", LixoStrings.rewardSourceLabel(RewardSource.ACHIEVEMENT, ""))
    }
}
