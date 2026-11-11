package se.lixo.app.ios

import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.platform.iosTranscriptionsToTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosSttTokensTest {

    @Test
    fun nBest_buildsAlternativesAndAveragedConfidence() {
        val transcripts = listOf(
            listOf("hej" to 0.9f, "sol" to 0.8f),
            listOf("hej" to 0.5f, "sal" to 0.5f)
        )
        val tokens = iosTranscriptionsToTokens(transcripts, alreadyEmitted = 0, timestampMillis = 0L)

        assertEquals(2, tokens.size)
        assertEquals(listOf("sal"), tokens[1].alternatives)
        assertTrue(tokens[0].confidence in 0.84f..0.86f)
    }

    @Test
    fun allZeroConfidences_usesDefaultTokenConfidence() {
        val transcripts = listOf(listOf("ett" to 0f, "två" to 0f))
        val tokens = iosTranscriptionsToTokens(transcripts, alreadyEmitted = 0, timestampMillis = 0L)

        assertTrue(tokens.isNotEmpty())
        tokens.forEach { assertEquals(BalanceConfig.STT_DEFAULT_TOKEN_CONFIDENCE, it.confidence) }
    }

    @Test
    fun alreadyEmitted_dropsLeadingTokens() {
        val transcripts = listOf(listOf("ett" to 0.9f, "två" to 0.9f, "tre" to 0.9f))
        val tokens = iosTranscriptionsToTokens(transcripts, alreadyEmitted = 1, timestampMillis = 0L)

        assertEquals(listOf("två", "tre"), tokens.map { it.rawWord })
    }

    @Test
    fun onlyFirstMaxResultsTranscriptionsAreUsed() {
        val transcripts = (1..6).map { i ->
            if (i == 6) listOf("ett" to 0.9f, "unikt" to 0.9f) else listOf("ett" to 0.9f, "two$i" to 0.9f)
        }
        val tokens = iosTranscriptionsToTokens(transcripts, alreadyEmitted = 0, timestampMillis = 0L)

        assertTrue(transcripts.size > BalanceConfig.STT_MAX_RESULTS)
        assertTrue(tokens.none { it.alternatives.contains("unikt") })
    }

    @Test
    fun emptyInput_returnsEmptyList() {
        assertEquals(emptyList(), iosTranscriptionsToTokens(emptyList(), alreadyEmitted = 0, timestampMillis = 0L))
    }
}
