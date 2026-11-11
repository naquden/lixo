package se.lixo.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import se.lixo.app.domain.content.GeographyCountries

/**
 * Verifies the geography flag drawables (plan 037) committed alongside the bundled content pack:
 * every `GeographyCountries.RANKED` code has a `flag_<iso>.webp`, the drawable directory has
 * exactly 194 such files under the 1MB budget, and `tools/flag_pipeline/countries.txt` matches
 * [GeographyCountries.RANKED]'s order exactly.
 */
class GeographyFlagAssetsTest {

    private fun findRepoDir(relative: String): File {
        var dir: File? = File(".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory || candidate.isFile) return candidate
            dir = dir.parentFile
            depth++
        }
        fail("could not locate $relative walking up from ${File(".").absolutePath}")
    }

    private fun drawableDir(): File = findRepoDir("composeApp/src/commonMain/composeResources/drawable")

    @Test
    fun everyRankedCountry_hasAValidFlagWebp() {
        val dir = drawableDir()
        GeographyCountries.RANKED.forEach { country ->
            val file = File(dir, "flag_${country.iso2}.webp")
            assertTrue(file.isFile, "missing ${file.name}")
            assertTrue(file.length() > 100, "${file.name} too small: ${file.length()} bytes")
            val header = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
            assertEquals("RIFF", header, "${file.name} is not a RIFF/WebP file")
        }
    }

    @Test
    fun drawableDir_hasExactly194FlagFilesUnderOneMegabyte() {
        val dir = drawableDir()
        val flagFiles = dir.listFiles { f -> f.name.startsWith("flag_") && f.name.endsWith(".webp") }.orEmpty()
        assertEquals(194, flagFiles.size)
        val totalBytes = flagFiles.sumOf { it.length() }
        assertTrue(totalBytes < 1_000_000, "total flag bytes $totalBytes exceeds 1MB")
    }

    @Test
    fun countriesTxt_matchesRankedOrderExactly() {
        val file = findRepoDir("tools/flag_pipeline/countries.txt")
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(GeographyCountries.RANKED.map { it.iso2 }, lines)
    }
}
