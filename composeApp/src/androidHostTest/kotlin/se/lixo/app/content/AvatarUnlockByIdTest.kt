package se.lixo.app.content

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import se.lixo.app.BundledTestContent
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.DailyActivityRecord

/**
 * Avatar unlocking against the real bundled pack (022): both profile creation's default
 * `unlockedAvatarIds` and the engine's star-threshold avatar unlocks.
 */
class AvatarUnlockByIdTest {

    @Test
    fun createProfile_startsWithOnlyTheDefaultAvatar() {
        val repo = LixoRepository(
            FirebaseSyncService(),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = null,
            content = BundledTestContent.repository()
        )
        val profile = repo.createProfile("Test", 8)
        assertEquals(setOf("avatar_cat"), profile.unlockedAvatarIds)
        assertEquals("avatar_cat", profile.appearance.avatarId)
    }

    private fun baseProfile(totalStars: Int, unlockedAvatarIds: Set<String>): ChildProfile = ChildProfile(
        id = "p1",
        displayName = "Test",
        totalStars = totalStars,
        unlockedAvatarIds = unlockedAvatarIds
    )

    private val engine = ProgressionAndGiftEngine()

    private fun complete(profile: ChildProfile, starsThisRun: Int) =
        engine.recordLevelCompletion(
            profile = profile,
            level = se.lixo.app.domain.model.CampaignLevel(
                id = "level_new", courseId = "course_sv_1", order = 999, title = "t",
                biomeNameSv = "b", subject = se.lixo.app.domain.model.Subject.SWEDISH,
                difficulty = 1, timeRequirementSeconds = 60, referenceWpm = 60, taskId = "task_new"
            ),
            previousProgress = null,
            earnedStarsThisRun = starsThisRun,
            completedInAiMode = true,
            elapsedSeconds = 60,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 500,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = DailyActivityRecord(profile.id, "2026-01-01"),
            nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        )

    @Test
    fun recordLevelCompletion_9plus3Stars_unlocksDog() {
        val profile = baseProfile(totalStars = 9, unlockedAvatarIds = setOf("avatar_cat"))
        val outcome = complete(profile, 3)
        assertEquals(listOf("avatar_dog"), outcome.newlyUnlockedAvatarIds)
        assertEquals(setOf("avatar_cat", "avatar_dog"), outcome.updatedProfile.unlockedAvatarIds)
    }

    @Test
    fun recordLevelCompletion_28plus3Stars_unlocksDogThenKnight() {
        val profile = baseProfile(totalStars = 28, unlockedAvatarIds = setOf("avatar_cat"))
        val outcome = complete(profile, 3)
        assertEquals(listOf("avatar_dog", "avatar_knight"), outcome.newlyUnlockedAvatarIds)
    }

    @Test
    fun recordLevelCompletion_replayWithNoNetNewStars_unlocksNothing() {
        val profile = baseProfile(totalStars = 11, unlockedAvatarIds = setOf("avatar_cat"))
        val outcome = engine.recordLevelCompletion(
            profile = profile,
            level = se.lixo.app.domain.model.CampaignLevel(
                id = "level_1", courseId = "course_sv_1", order = 1, title = "t",
                biomeNameSv = "b", subject = se.lixo.app.domain.model.Subject.SWEDISH,
                difficulty = 1, timeRequirementSeconds = 60, referenceWpm = 60, taskId = "task_1"
            ),
            previousProgress = se.lixo.app.domain.model.LevelProgress(
                profileId = profile.id, levelId = "level_1", levelOrder = 1, starsEarned = 3,
                completedInAiMode = true, bestTimeSeconds = 60, bestWpm = 60
            ),
            earnedStarsThisRun = 3,
            completedInAiMode = true,
            elapsedSeconds = 60,
            achievedWpm = 60,
            completedTimedChallenge = false,
            eligibleForRepetitionGift = false,
            clearedLevelsCountBeforeRun = 500,
            content = BundledTestContent.content,
            currentlyUnlockedItemIds = emptySet(),
            todayActivityBeforeRun = DailyActivityRecord(profile.id, "2026-01-01"),
            nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        )
        assertEquals(emptyList(), outcome.newlyUnlockedAvatarIds)
    }
}
