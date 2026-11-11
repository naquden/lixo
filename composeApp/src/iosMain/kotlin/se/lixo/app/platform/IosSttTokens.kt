package se.lixo.app.platform

import platform.Speech.SFTranscription
import se.lixo.app.domain.model.BalanceConfig

/**
 * Pure helper converting SFSpeechRecognizer's N-best [transcripts] (each inner list is one
 * `SFTranscription`'s segments as `(substring, confidence)` pairs) into [RecognizedSpeechToken]s,
 * via the shared [parseSpeechBundle]. Kept pure (no `SFTranscription` types) so it is directly
 * unit-testable from `iosTest`.
 */
fun iosTranscriptionsToTokens(
    transcripts: List<List<Pair<String, Float>>>,
    alreadyEmitted: Int,
    timestampMillis: Long
): List<RecognizedSpeechToken> {
    val used = transcripts.take(BalanceConfig.STT_MAX_RESULTS)
    val matches = used
        .map { segs -> segs.joinToString(" ") { it.first.trim() }.trim() }
        .filter { it.isNotBlank() }
    val topConf = used.firstOrNull()?.map { it.second }.orEmpty()
    val scores = if (topConf.isEmpty() || topConf.all { it <= 0f }) {
        null
    } else {
        floatArrayOf(topConf.average().toFloat())
    }
    return parseSpeechBundle(matches, scores, alreadyEmitted, timestampMillis)
}

/**
 * This [SFTranscription]'s segments as `(substring, confidence)` pairs, or a single
 * `(formattedString, 0f)` pair when there are no segments (e.g. very short partial results).
 */
fun SFTranscription.toSegmentPairs(): List<Pair<String, Float>> {
    val segs = segments
    if (segs.isEmpty()) return listOf(formattedString to 0f)
    return segs.map { seg ->
        val s = seg as platform.Speech.SFTranscriptionSegment
        s.substring to s.confidence
    }
}
