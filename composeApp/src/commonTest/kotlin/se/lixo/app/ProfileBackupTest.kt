package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import se.lixo.app.data.local.BackupAppearance
import se.lixo.app.data.local.BackupLevelProgress
import se.lixo.app.data.local.BackupProfile
import se.lixo.app.data.local.BackupUnlockedItem
import se.lixo.app.data.local.ProfileBackup
import se.lixo.app.data.local.ProfileBackupError
import se.lixo.app.data.local.decodeProfileBackup
import se.lixo.app.data.local.encodeProfileBackup
import se.lixo.app.data.local.profileBackupFileName
import se.lixo.app.data.local.toBackupProfile
import se.lixo.app.data.local.toChildProfile
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.social.Nickname
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class ProfileBackupTest {

    private fun sampleProfile(): ChildProfile = ChildProfile(
        id = "profile_ella",
        displayName = "Ella",
        nickname = Nickname(adj = 2, animal = 3, num = 42),
        friendCardId = "card_secret_123",
        ageYears = 8,
        tutorialCompleted = true,
        appearance = AvatarAppearance(
            avatarId = "avatar_cat",
            tintHex = 0xFF112233,
            eyeIndex = 1,
            smileIndex = 2,
            equipped = mapOf(EquipmentSlot.HAT to "item_hat_test_cone", EquipmentSlot.PANTS to "item_pants_test_shorts")
        ),
        unlockedAvatarIds = setOf("avatar_cat", "avatar_dog"),
        currentLevelOrder = 12,
        totalStars = 37,
        currentStreakDays = 4,
        bestStreakDays = 9,
        lastStreakDateIso = "2026-11-10",
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
        chestProgressHalfPoints = 3,
        achievementProgress = mapOf("ach_1" to 2, "ach_2" to 5),
        achievementCountedLevelIds = mapOf("ach_1" to setOf("level_1", "level_2")),
        unlockedAchievementIds = setOf("ach_1"),
        pendingAchievementRewardIds = setOf("ach_2"),
        chestsSinceRarePlus = 1,
        chestsSinceEpicPlus = 2,
        chestsSinceLegendary = 3
    )

    private fun sampleBackup(): ProfileBackup = ProfileBackup(
        exportedAtEpochMillis = 123_456L,
        appVersionCode = 7,
        profile = sampleProfile().toBackupProfile(),
        levelProgress = listOf(
            BackupLevelProgress(levelId = "level_1", levelOrder = 1, starsEarned = 3, bestTimeSeconds = 30, bestWpm = 40),
            BackupLevelProgress(levelId = "level_2", levelOrder = 2, starsEarned = 2)
        ),
        unlockedItems = listOf(
            BackupUnlockedItem("item_hat_test_cone", 10L, "LEVEL_REWARD"),
            BackupUnlockedItem("item_pants_test_shorts", 20L, "STAR_MILESTONE")
        )
    )

    @Test
    fun roundTrip_encodeDecode_preservesAllFields() {
        val backup = sampleBackup()
        val json = encodeProfileBackup(backup)
        val decoded = decodeProfileBackup(json).getOrThrow()

        assertEquals(backup.profile, decoded.profile)
        assertEquals(backup.levelProgress, decoded.levelProgress)
        assertEquals(backup.unlockedItems, decoded.unlockedItems)
        assertEquals(backup.appVersionCode, decoded.appVersionCode)
        assertEquals(backup.exportedAtEpochMillis, decoded.exportedAtEpochMillis)
    }

    @Test
    fun decode_rejectsWrongFormat() {
        val wrongFormat = """{"format":"x","schemaVersion":1,"exportedAtEpochMillis":1,"profile":{"id":"p","displayName":"P"}}"""
        assertIs<ProfileBackupError.NotABackup>(decodeProfileBackup(wrongFormat).exceptionOrNull())
        assertIs<ProfileBackupError.NotABackup>(decodeProfileBackup("{}").exceptionOrNull())
    }

    @Test
    fun decode_rejectsNewerSchemaVersion() {
        val json = """{"format":"lixo-profile-backup","schemaVersion":2,"exportedAtEpochMillis":1,"profile":{"id":"p","displayName":"P"}}"""
        val error = decodeProfileBackup(json).exceptionOrNull()
        assertIs<ProfileBackupError.UnsupportedVersion>(error)
        assertEquals(2, error.version)
    }

    @Test
    fun decode_rejectsGarbage() {
        assertIs<ProfileBackupError.NotABackup>(decodeProfileBackup("not json").exceptionOrNull())

        val missingProfile = """{"format":"lixo-profile-backup","schemaVersion":1,"exportedAtEpochMillis":1}"""
        assertIs<ProfileBackupError.Corrupt>(decodeProfileBackup(missingProfile).exceptionOrNull())

        val blankDisplayName = """{"format":"lixo-profile-backup","schemaVersion":1,"exportedAtEpochMillis":1,"profile":{"id":"p","displayName":""}}"""
        assertIs<ProfileBackupError.Corrupt>(decodeProfileBackup(blankDisplayName).exceptionOrNull())
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        val json = """
            {
              "format":"lixo-profile-backup",
              "schemaVersion":1,
              "exportedAtEpochMillis":1,
              "unknownTopLevelKey":"ignored",
              "profile":{
                "id":"profile_x",
                "displayName":"X",
                "unknownProfileKey":"ignored",
                "appearance":{
                  "avatarId":"avatar_cat",
                  "equipped":{"WINGS":"item_mystery","HAT":"item_hat_test_cone"}
                }
              }
            }
        """.trimIndent()
        val decoded = decodeProfileBackup(json).getOrThrow()
        assertEquals(mapOf("WINGS" to "item_mystery", "HAT" to "item_hat_test_cone"), decoded.profile.appearance.equipped)

        val domain = decoded.profile.toChildProfile()
        assertFalse(domain.appearance.equipped.values.contains("item_mystery"))
        assertEquals("item_hat_test_cone", domain.appearance.equipped[EquipmentSlot.HAT])
    }

    @Test
    fun export_stripsFriendCardIdAndHasNoFriends() {
        val profile = sampleProfile()
        val backup = ProfileBackup(exportedAtEpochMillis = 1L, profile = profile.toBackupProfile())
        val json = encodeProfileBackup(backup)

        assertFalse(json.contains("friendCardId"))
        assertFalse(json.contains("card_secret_123"))
        assertFalse(json.contains("friends"))
    }

    @Test
    fun fileName_sanitizes() {
        val nowMillis = LocalDate(2026, 11, 12)
            .atStartOfDayIn(TimeZone.UTC)
            .toEpochMilliseconds()
        assertEquals("lixo-EllaÅ1-2026-11-12.json", profileBackupFileName("Ella Å/1", nowMillis, TimeZone.UTC))
        assertTrue(profileBackupFileName("///", nowMillis, TimeZone.UTC).startsWith("lixo-profil-"))
    }
}
