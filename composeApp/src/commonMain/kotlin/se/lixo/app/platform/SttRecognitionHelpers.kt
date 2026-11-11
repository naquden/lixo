package se.lixo.app.platform

import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig

/**
 * Pure speech-recognition helpers shared by the Android platform layer and its tests. Public
 * (not `internal`) because androidApp's `androidTest` is a separate Gradle module and cannot see
 * `internal` declarations from `composeApp`.
 */

/** Splits [transcript] on whitespace, dropping blank tokens. */
fun tokenizeSttTranscript(transcript: String): List<String> =
    transcript.split(Regex("\\s+")).filter { it.isNotBlank() }

/**
 * Builds [RecognizedSpeechToken]s from a `RecognizerIntent` results bundle's N-best [matches]
 * transcripts and (optionally null) [scores], dropping the first [alreadyEmitted] tokens of the
 * top transcript (partial results are cumulative for the utterance).
 */
fun parseSpeechBundle(
    matches: List<String>,
    scores: FloatArray?,
    alreadyEmitted: Int,
    timestampMillis: Long = 0L
): List<RecognizedSpeechToken> {
    val top = matches.firstOrNull() ?: return emptyList()
    val transcriptTokens = matches.map { tokenizeSttTranscript(it) }
    val topTokens = transcriptTokens.first()

    val conf = scores?.firstOrNull()?.takeIf { it >= 0f } ?: BalanceConfig.STT_DEFAULT_TOKEN_CONFIDENCE

    val tokens = topTokens.mapIndexed { i, raw ->
        val normalizedWord = ForcedAlignmentEngine.normalizeForAlignment(raw)
        val alternatives = transcriptTokens.drop(1)
            .filter { it.size == topTokens.size }
            .mapNotNull { it.getOrNull(i) }
            .map { ForcedAlignmentEngine.normalizeForAlignment(it) }
            .filter { it.isNotEmpty() && it != normalizedWord }
            .distinct()

        RecognizedSpeechToken(
            rawWord = raw,
            normalizedWord = normalizedWord,
            confidence = conf,
            timestampMillis = timestampMillis,
            alternatives = alternatives
        )
    }

    return tokens.drop(alreadyEmitted)
}

/**
 * Builds the recognizer's `EXTRA_BIASING_STRINGS` list: the upcoming phrase (as one string) first,
 * then the distinct upcoming words, then the rest of the story's expected words, capped at
 * [BalanceConfig.STT_BIAS_MAX_STRINGS] entries with no duplicates.
 */
fun buildBiasStrings(expectedWords: List<String>, upcomingWords: List<String>): List<String> {
    val up = upcomingWords.filter { it.isNotBlank() }
    val phrase = up.take(BalanceConfig.STT_BIAS_PHRASE_MAX_WORDS).joinToString(" ")

    val result = mutableListOf<String>()
    if (phrase.isNotBlank()) result.add(phrase)
    result.addAll(up)
    result.addAll(expectedWords.filter { it.isNotBlank() })

    return result.distinct().take(BalanceConfig.STT_BIAS_MAX_STRINGS)
}
