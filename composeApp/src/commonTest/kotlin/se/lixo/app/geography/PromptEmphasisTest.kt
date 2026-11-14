package se.lixo.app.geography

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.lixo.app.ui.screens.math.promptWithEmphasis

class PromptEmphasisTest {

    @Test
    fun emphasisFound_boldsOnlyThatRange() {
        val prompt = "Vilken flagga har Finland?"
        val result = promptWithEmphasis(prompt, "Finland")
        assertTrue(result != null)
        assertEquals(prompt, result!!.text)
        assertEquals(2, result.spanStyles.size)

        val normalSpan = result.spanStyles[0]
        assertEquals(FontWeight.Normal, normalSpan.item.fontWeight)
        assertEquals(0, normalSpan.start)
        assertEquals(prompt.length, normalSpan.end)

        val boldSpan = result.spanStyles[1]
        assertEquals(FontWeight.ExtraBold, boldSpan.item.fontWeight)
        val expectedStart = prompt.indexOf("Finland")
        assertEquals(18, expectedStart)
        assertEquals(expectedStart, boldSpan.start)
        assertEquals(expectedStart + 7, boldSpan.end)
        assertEquals(25, boldSpan.end)

        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.ExtraBold }
        assertTrue(boldSpans.all { it.start >= 18 && it.end <= 25 })
    }

    @Test
    fun mathExpression_boldsOnlyExpression() {
        val prompt = "Vad blir 5 + 3?"
        val result = promptWithEmphasis(prompt, "5 + 3")
        assertTrue(result != null)
        assertEquals(prompt, result!!.text)

        val normalSpan = result.spanStyles.single { it.item.fontWeight == FontWeight.Normal }
        assertEquals(0, normalSpan.start)
        assertEquals(prompt.length, normalSpan.end)

        val boldSpan = result.spanStyles.single { it.item.fontWeight == FontWeight.ExtraBold }
        assertEquals(9, boldSpan.start)
        assertEquals(14, boldSpan.end)
        assertEquals("5 + 3", prompt.substring(boldSpan.start, boldSpan.end))
    }

    @Test
    fun englishWord_boldsOnlyQuotedWordNotSentenceOccurrence() {
        val prompt = "Vad betyder ordet \"hat\"?\n(The cat has a blue hat.)"
        val result = promptWithEmphasis(prompt, "hat")
        assertTrue(result != null)
        assertEquals(prompt, result!!.text)

        val normalSpan = result.spanStyles.single { it.item.fontWeight == FontWeight.Normal }
        assertEquals(0, normalSpan.start)
        assertEquals(prompt.length, normalSpan.end)

        val boldSpan = result.spanStyles.single { it.item.fontWeight == FontWeight.ExtraBold }
        assertEquals(prompt.indexOf("hat"), boldSpan.start)
        assertEquals(19, boldSpan.start)
        assertEquals(22, boldSpan.end)
        assertEquals("hat", prompt.substring(boldSpan.start, boldSpan.end))
    }

    @Test
    fun nullEmphasis_returnsNull() {
        assertNull(promptWithEmphasis("Vad blir 1 + 1?", null))
    }

    @Test
    fun emptyEmphasis_returnsNull() {
        assertNull(promptWithEmphasis("Vad blir 1 + 1?", ""))
    }

    @Test
    fun emphasisNotInPrompt_returnsNull() {
        assertNull(promptWithEmphasis("Vad blir 1 + 1?", "Sverige"))
    }
}
