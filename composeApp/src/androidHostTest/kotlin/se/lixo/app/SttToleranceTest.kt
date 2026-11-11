package se.lixo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.ReadingMode
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.buildBiasStrings
import se.lixo.app.platform.parseSpeechBundle

/**
 * Verifies doc/plans/036-stt-false-struggled-words.md's B (swallowed short words), C (aliases /
 * proper-noun leniency), A (N-best alternatives & real confidence) changes to
 * [ForcedAlignmentEngine], plus the pure helpers in `SttRecognitionHelpers.kt`.
 */
class SttToleranceTest {

    private val alignmentEngine = ForcedAlignmentEngine()

    @Test
    fun shortWordSwallowedByStt_isCompletedNotFlagged() {
        var session = alignmentEngine.startSession(
            storyText = "Bo bor i en gul stuga",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("bo", "bo", 0.95f, 100L),
                RecognizedSpeechToken("bor", "bor", 0.95f, 200L),
                RecognizedSpeechToken("gul", "gul", 0.95f, 300L),
                RecognizedSpeechToken("stuga", "stuga", 0.95f, 400L)
            )
        )

        assertTrue(session.words[2].isCompleted, "'i' silently swallowed but credited")
        assertFalse(session.words[2].needsPractice)
        assertTrue(session.words[3].isCompleted, "'en' silently swallowed but credited")
        assertFalse(session.words[3].needsPractice)
        assertTrue(session.isCompleted)

        val score = alignmentEngine.evaluateSessionScore(session, referenceWpm = 40, timeRequirementSeconds = 30)
        assertTrue(score.struggledWordsToLog.isEmpty())
    }

    @Test
    fun shortWordMisheard_isStillFlagged() {
        var session = alignmentEngine.startSession(
            storyText = "Bo bor i en gul stuga",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("bo", "bo", 0.95f, 100L),
                RecognizedSpeechToken("bor", "bor", 0.95f, 200L),
                RecognizedSpeechToken("katt", "katt", 0.95f, 250L),
                RecognizedSpeechToken("en", "en", 0.95f, 300L),
                RecognizedSpeechToken("gul", "gul", 0.95f, 400L),
                RecognizedSpeechToken("stuga", "stuga", 0.95f, 500L)
            )
        )

        assertTrue(session.words[2].needsPractice, "'i' misheard as 'katt', must stay flagged")
        assertEquals("katt", session.words[2].heardText)

        val score = alignmentEngine.evaluateSessionScore(session, referenceWpm = 40, timeRequirementSeconds = 30)
        assertTrue(score.struggledWordsToLog.contains("i"))
    }

    @Test
    fun longWordSkipped_isStillFlagged() {
        var session = alignmentEngine.startSession(
            storyText = "solen regnar fint",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("solen", "solen", 0.95f, 100L),
                RecognizedSpeechToken("fint", "fint", 0.95f, 200L)
            )
        )

        assertTrue(session.words[1].needsPractice, "'regnar' is long, must stay flagged even unheard")
        assertFalse(session.words[1].isCompleted)

        val score = alignmentEngine.evaluateSessionScore(session, referenceWpm = 40, timeRequirementSeconds = 30)
        assertTrue(score.struggledWordsToLog.contains("regnar"))
    }

    @Test
    fun shortWordLenient_onlyAtCursor() {
        var session = alignmentEngine.startSession(
            storyText = "en katt en hund",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("et", "et", 0.95f, 100L))
        )
        assertTrue(session.words[0].isCompleted, "Lenient short-word rule credits 'et' for 'en' at the cursor")
        assertEquals(1, session.currentWordIndex)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("et", "et", 0.95f, 200L))
        )
        assertFalse(session.words[2].isCompleted, "Lenient rule must not credit the later 'en' from the lookahead pass")
        assertFalse(session.words[1].needsPractice, "'katt' at the cursor must not be flagged by an unrelated 'et'")
        assertEquals(1, session.currentWordIndex, "cursor stays on 'katt'")
    }

    @Test
    fun alias_matchesInventedName() {
        val tokenized = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
        val lixoIndices = tokenized.filter { it.normalizedText == "lixo" }.map { it.index }
        assertEquals(2, lixoIndices.size)

        listOf("leckso", "lissa").forEach { aliasSpoken ->
            var session = alignmentEngine.startSession(
                storyText = BalanceConfig.TUTORIAL_STORY_SV,
                mode = ReadingMode.AI_LISTENING,
                isTimedChallengeRequested = false
            )
            val tokens = tokenized.map { token ->
                val spoken = if (token.normalizedText == "lixo") aliasSpoken else token.normalizedText
                RecognizedSpeechToken(spoken, spoken, 0.95f, token.index.toLong())
            }
            session = alignmentEngine.processRecognizedTokens(session, tokens)

            lixoIndices.forEach { idx ->
                assertTrue(session.words[idx].isCompleted, "alias '$aliasSpoken' must complete Lixo at idx $idx")
                assertFalse(session.words[idx].needsPractice, "alias match must not flag Lixo at idx $idx")
            }
        }
    }

    @Test
    fun properNoun_lenientMatch() {
        var session = alignmentEngine.startSession(
            storyText = "Där bor Bo.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        assertTrue(session.words[2].isProperNoun)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("där", "där", 0.95f, 100L),
                RecognizedSpeechToken("bor", "bor", 0.95f, 200L),
                RecognizedSpeechToken("bå", "bå", 0.95f, 300L)
            )
        )
        assertTrue(session.words[2].isCompleted, "'bå' loosely matches proper noun 'Bo'")
    }

    @Test
    fun properNoun_thresholdOnlyForNames() {
        var session = alignmentEngine.startSession(
            storyText = "Här sover Sigrid.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(
                RecognizedSpeechToken("här", "här", 0.95f, 100L),
                RecognizedSpeechToken("sover", "sover", 0.95f, 200L),
                RecognizedSpeechToken("sigge", "sigge", 0.95f, 300L)
            )
        )
        assertTrue(session.words[2].isCompleted, "proper noun threshold applies to 'Sigrid'")

        var session2 = alignmentEngine.startSession(
            storyText = "Sigrid sover.",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        assertFalse(session2.words[0].isProperNoun, "sentence-initial capital is not a name")
        session2 = alignmentEngine.processRecognizedTokens(
            session2,
            listOf(
                RecognizedSpeechToken("sigge", "sigge", 0.95f, 100L),
                RecognizedSpeechToken("sover", "sover", 0.95f, 200L)
            )
        )
        assertTrue(session2.words[0].needsPractice, "sentence-initial 'Sigrid' is not a name, so default threshold applies")
    }

    @Test
    fun sentenceInitialCapital_isNotProperNoun() {
        val tokens = ForcedAlignmentEngine().tokenizeStory("Katten sover. Hunden springer.")
        assertFalse(tokens[0].isProperNoun)
        assertFalse(tokens[2].isProperNoun)

        val tutorialTokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
        assertTrue(tutorialTokens[1].isProperNoun)
        assertEquals(BalanceConfig.STT_WORD_ALIASES.getValue("lixo"), tutorialTokens[1].aliases)
    }

    @Test
    fun nBestAlternative_matchesWhenTopMisses() {
        var session = alignmentEngine.startSession(
            storyText = "en gul stuga",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("en", "en", 0.95f, 0L))
        )
        assertEquals(1, session.currentWordIndex)

        session = alignmentEngine.processRecognizedTokens(
            session,
            listOf(RecognizedSpeechToken("hus", "hus", 0.7f, 0L, alternatives = listOf("gul")))
        )
        assertTrue(session.words[1].isCompleted, "alternative 'gul' must match when the top word 'hus' misses")

        var controlSession = alignmentEngine.startSession(
            storyText = "en gul stuga",
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        controlSession = alignmentEngine.processRecognizedTokens(
            controlSession,
            listOf(RecognizedSpeechToken("en", "en", 0.95f, 0L))
        )
        controlSession = alignmentEngine.processRecognizedTokens(
            controlSession,
            listOf(RecognizedSpeechToken("hus", "hus", 0.7f, 0L))
        )
        assertFalse(controlSession.words[1].isCompleted, "without alternatives, 'hus' alone must not match 'gul'")
        assertEquals(listOf("hus"), controlSession.unmatchedSinceAdvance)
    }

    @Test
    fun parseSpeechBundle_nBestAndScores() {
        val matches = listOf("en gul stuga", "en gull stuga", "ett gul stugan")
        val scores = floatArrayOf(0.7f, 0.2f, 0.1f)
        val tokens = parseSpeechBundle(matches, scores, alreadyEmitted = 0)

        assertEquals(3, tokens.size)
        tokens.forEach { assertEquals(0.7f, it.confidence) }
        assertEquals(listOf("ett"), tokens[0].alternatives)
        assertEquals(listOf("gull"), tokens[1].alternatives)
        assertEquals(listOf("stugan"), tokens[2].alternatives)
    }

    @Test
    fun parseSpeechBundle_partialWithoutScores() {
        val tokens = parseSpeechBundle(listOf("en gul"), null, alreadyEmitted = 1)
        assertEquals(1, tokens.size)
        assertEquals("gul", tokens[0].normalizedWord)
        assertEquals(BalanceConfig.STT_DEFAULT_TOKEN_CONFIDENCE, tokens[0].confidence)
        assertTrue(tokens[0].alternatives.isEmpty())
    }

    @Test
    fun buildBiasStrings_tutorialCursorAtHittar() {
        val tokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
        val upcoming = tokens.drop(2).map { it.normalizedText } // cursor at "hittar"
        val expected = tokens.flatMap { listOf(it.normalizedText) + it.aliases }.filter { it.isNotBlank() }.distinct()

        val bias = buildBiasStrings(expected, upcoming)
        assertEquals("hittar en glittrande hatt i skogen hatten lyser med varmt guld när", bias.first())
        assertEquals(bias.distinct(), bias)
        assertTrue(bias.size <= 100)
        BalanceConfig.STT_WORD_ALIASES.getValue("lixo").forEach { alias ->
            assertTrue(bias.contains(alias), "bias must contain alias '$alias'")
        }
        val kattenIndex = bias.indexOf("katten")
        assertTrue(kattenIndex != -1)
        assertTrue(upcoming.all { bias.indexOf(it) < kattenIndex })
    }

    @Test
    fun upcomingWordsForStt_fallsBackToUnreadYellow() {
        var session = alignmentEngine.startSession(
            storyText = BalanceConfig.TUTORIAL_STORY_SV,
            mode = ReadingMode.AI_LISTENING,
            isTimedChallengeRequested = false
        )
        val tokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
            .filter { it.normalizedText != "lixo" }
            .map { RecognizedSpeechToken(it.displayText, it.normalizedText, 0.95f, 0L) }
        session = alignmentEngine.processRecognizedTokens(session, tokens)

        assertTrue(session.isAwaitingUnreadWords)
        assertEquals(listOf("lixo"), session.upcomingWordsForStt)
    }
}
