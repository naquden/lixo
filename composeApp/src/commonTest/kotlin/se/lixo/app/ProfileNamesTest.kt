package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.isProfileNameTaken
import se.lixo.app.domain.model.normalizeProfileName
import se.lixo.app.domain.model.uniqueProfileName

class ProfileNamesTest {

    private fun profile(id: String, name: String) = ChildProfile(id = id, displayName = name)

    @Test
    fun isProfileNameTaken_ignoresCaseAndWhitespace() {
        val profiles = listOf(profile("a", "Ella"), profile("b", "Olle"))
        assertTrue(isProfileNameTaken(profiles, "ella"))
        assertTrue(isProfileNameTaken(profiles, "  ELLA "))
        assertFalse(isProfileNameTaken(profiles, "Elsa"))
    }

    @Test
    fun isProfileNameTaken_skipsExcludedProfile() {
        val profiles = listOf(profile("a", "Ella"), profile("b", "Olle"))
        assertFalse(isProfileNameTaken(profiles, "ella", excludingProfileId = "a"))
        assertTrue(isProfileNameTaken(profiles, "olle", excludingProfileId = "a"))
    }

    @Test
    fun uniqueProfileName_freeNameUnchanged() {
        assertEquals("Elsa", uniqueProfileName(listOf(profile("a", "Ella")), "Elsa"))
    }

    @Test
    fun uniqueProfileName_addsNumberSuffix() {
        val profiles = listOf(profile("a", "Ella"))
        assertEquals("Ella 2", uniqueProfileName(profiles, "Ella"))
        assertEquals("ella 2", uniqueProfileName(profiles, " ella "))
        assertEquals("Ella 3", uniqueProfileName(profiles + profile("b", "Ella 2"), "Ella"))
    }

    @Test
    fun uniqueProfileName_longNameStillFitsMaxLength() {
        val name = "a".repeat(BalanceConfig.PROFILE_NAME_MAX_LENGTH)
        val result = uniqueProfileName(listOf(profile("a", name)), name)
        assertEquals(BalanceConfig.PROFILE_NAME_MAX_LENGTH, result.length)
        assertTrue(result.endsWith(" 2"))
    }

    @Test
    fun normalizeProfileName_trimsAndCaps() {
        assertEquals("Elsa", normalizeProfileName("  Elsa  "))
        val long = "abcdefghij".repeat(3)
        assertEquals(long.take(BalanceConfig.PROFILE_NAME_MAX_LENGTH), normalizeProfileName(long))
    }
}
