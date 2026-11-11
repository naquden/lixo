package se.lixo.app.verify

import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.lixo.app.domain.engine.ForcedAlignmentEngine
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.AndroidSpeechRecognitionEngine
import se.lixo.app.platform.buildBiasStrings
import se.lixo.app.platform.buildRecognizerIntent
import se.lixo.app.platform.parseSpeechBundle

/**
 * Verifies doc/plans/036's D changes (recognizer intent, biasing, on-device recognizer choice) on
 * a real device/emulator. No Compose rule: these exercise the platform layer directly.
 */
class SttRecognizerConfigTest {

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPlatformContext.init(ctx)
    }

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recognizerIntent_hasBiasingAndMaxResults() {
        val list = listOf("hittar", "en", "glittrande")
        val intent = buildRecognizerIntent(ctx(), "sv-SE", list, Build.VERSION.SDK_INT)

        assertEquals(BalanceConfig.STT_MAX_RESULTS, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1))
        assertEquals("sv-SE", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))

        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(list, intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
        } else {
            assertNull(intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
        }
    }

    @Test
    fun recognizerIntent_biasingGatedOnApi33AndPreferOffline() {
        val list = listOf("i", "skogen")

        val below33 = buildRecognizerIntent(ctx(), "sv-SE", list, sdkInt = 32)
        assertNull(below33.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))

        val at33 = buildRecognizerIntent(ctx(), "sv-SE", list, sdkInt = 33)
        assertEquals(list, at33.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))

        val offline = buildRecognizerIntent(ctx(), "sv-SE", list, sdkInt = 33, preferOffline = true)
        assertTrue(offline.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test
    fun biasStrings_tutorialCursorAtHittar() {
        val tokens = ForcedAlignmentEngine().tokenizeStory(BalanceConfig.TUTORIAL_STORY_SV)
        val upcoming = tokens.drop(2).map { it.normalizedText }
        val expected = tokens.flatMap { listOf(it.normalizedText) + it.aliases }.filter { it.isNotBlank() }.distinct()

        val bias = buildBiasStrings(expected, upcoming)
        assertEquals(
            "hittar en glittrande hatt i skogen hatten lyser med varmt guld när",
            bias.first()
        )
        assertEquals(bias.distinct(), bias)
        assertTrue(bias.size <= BalanceConfig.STT_BIAS_MAX_STRINGS)
    }

    @Test
    fun biasingFollowsUpdateExpectedPositionOnRestart() {
        val ctx = ctx()
        val engine = AndroidSpeechRecognitionEngine(ctx)
        engine.updateExpectedPosition(listOf("i", "skogen"))

        assertEquals("i skogen", engine.currentBiasStrings().first())

        if (Build.VERSION.SDK_INT >= 33) {
            val intent = engine.buildCurrentRecognizerIntent(ctx)
            assertEquals("i skogen", intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)?.firstOrNull())
        }
    }

    @Test
    fun parseSpeechBundle_nBestAndPartial() {
        val matches = listOf("en gul stuga", "en gull stuga", "ett gul stugan")
        val scores = floatArrayOf(0.7f, 0.2f, 0.1f)
        val tokens = parseSpeechBundle(matches, scores, alreadyEmitted = 0)
        assertEquals(3, tokens.size)
        assertEquals(listOf("ett"), tokens[0].alternatives)
        assertEquals(listOf("gull"), tokens[1].alternatives)
        assertEquals(listOf("stugan"), tokens[2].alternatives)

        val partial = parseSpeechBundle(listOf("en gul"), null, alreadyEmitted = 1)
        assertEquals(1, partial.size)
        assertEquals("gul", partial[0].normalizedWord)
        assertEquals(BalanceConfig.STT_DEFAULT_TOKEN_CONFIDENCE, partial[0].confidence)
    }

    @Test
    fun chooseRecognizer_logsChoice() {
        val ctx = ctx()
        val engine = AndroidSpeechRecognitionEngine(ctx)

        val supported = try {
            runBlocking { engine.resolveOnDeviceSupport(ctx, "sv-SE") }
        } catch (_: Throwable) {
            false
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (recognizer, onDevice) = engine.chooseRecognizer(ctx, "sv-SE")
            val onDeviceAvailable = if (Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            } else {
                false
            }
            Log.i(
                "LixoStt",
                "test chooseRecognizer onDevice=$onDevice supported=$supported sdk=${Build.VERSION.SDK_INT} " +
                    "onDeviceAvailable=$onDeviceAvailable"
            )
            assertNotNull(recognizer)
            recognizer.destroy()
        }
    }
}
