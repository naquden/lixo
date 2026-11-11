package se.lixo.app.geography

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.lixo.app.domain.content.FlagRegion
import se.lixo.app.domain.content.GeographyCountries

class GeographyCountriesTest {

    @Test
    fun ranked_has194UniqueLowercaseCodes() {
        val codes = GeographyCountries.RANKED.map { it.iso2 }
        assertEquals(194, codes.size)
        assertEquals(194, codes.toSet().size)
        codes.forEach { code ->
            assertEquals(code.lowercase(), code, "code '$code' must be lowercase")
            assertEquals(2, code.length, "code '$code' must be 2 letters")
        }
    }

    @Test
    fun firstEightRanks_containAllFiveNordicCountries() {
        val firstEight = GeographyCountries.RANKED.take(8)
        val nordic = firstEight.filter { it.isNordic }
        assertEquals(5, nordic.size)
        assertEquals(setOf("se", "no", "dk", "fi", "is"), nordic.map { it.iso2 }.toSet())
    }

    @Test
    fun everyCountry_hasNonBlankNameAndValidColors() {
        GeographyCountries.RANKED.forEach { country ->
            assertTrue(country.nameSv.isNotBlank(), "nameSv blank for ${country.iso2}")
            assertTrue(country.colorsSv.isNotEmpty(), "colorsSv empty for ${country.iso2}")
            assertTrue(country.colorsSv.size <= 4, "colorsSv too long for ${country.iso2}: ${country.colorsSv}")
            country.colorsSv.forEach { color ->
                assertTrue(
                    color in GeographyCountries.COLOR_VOCAB,
                    "color '$color' for ${country.iso2} not in COLOR_VOCAB"
                )
            }
        }
    }

    @Test
    fun lookalikeGroups_allCodesExistInRanked_andGroupsHaveAtLeastTwoMembers() {
        val ranked = GeographyCountries.RANKED.map { it.iso2 }.toSet()
        GeographyCountries.LOOKALIKE_GROUPS.forEach { group ->
            assertTrue(group.size >= 2, "group $group must have >= 2 members")
            group.forEach { code ->
                assertTrue(code in ranked, "lookalike code '$code' not in RANKED")
            }
        }
    }

    @Test
    fun lookalikeGroups_noCodeInMoreThanOneGroup() {
        val seen = mutableMapOf<String, Int>()
        GeographyCountries.LOOKALIKE_GROUPS.forEach { group ->
            group.forEach { code -> seen[code] = (seen[code] ?: 0) + 1 }
        }
        val dupes = seen.filterValues { it > 1 }
        assertTrue(dupes.isEmpty(), "codes in more than one lookalike group: $dupes")
    }

    @Test
    fun regionCounts_matchExpected() {
        val counts = GeographyCountries.RANKED.groupingBy { it.region }.eachCount()
        assertEquals(5, counts[FlagRegion.NORDEN])
        assertEquals(44, counts[FlagRegion.EUROPA])
        assertEquals(42, counts[FlagRegion.ASIEN])
        assertEquals(54, counts[FlagRegion.AFRIKA])
        assertEquals(23, counts[FlagRegion.NORDAMERIKA])
        assertEquals(12, counts[FlagRegion.SYDAMERIKA])
        assertEquals(14, counts[FlagRegion.OCEANIEN])
    }

    @Test
    fun byIso_roundTrips() {
        GeographyCountries.RANKED.forEach { country ->
            assertEquals(country, GeographyCountries.byIso(country.iso2))
            assertEquals(country, GeographyCountries.byIso(country.iso2.uppercase()))
        }
    }
}
