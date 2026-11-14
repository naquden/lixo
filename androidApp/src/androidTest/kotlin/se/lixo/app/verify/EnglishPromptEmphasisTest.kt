package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MATH_PROMPT_TAG
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies plan 052: English question prompts render in Normal weight with only the English
 * target word in ExtraBold.
 */
class EnglishPromptEmphasisTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots/verify052").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertBoldRangeOnly(prompt: String, name: String) {
        val annotated = compose.onNodeWithTag(MATH_PROMPT_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertEquals(prompt, annotated.text)

        val bold = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.ExtraBold }
        assertEquals(1, bold.size)
        val expectedStart = prompt.indexOf(name)
        assertEquals(expectedStart, bold[0].start)
        assertEquals(expectedStart + name.length, bold[0].end)

        assertTrue(
            annotated.spanStyles.any {
                it.item.fontWeight == FontWeight.Normal && it.start == 0 && it.end == prompt.length
            }
        )
    }

    @Test
    fun englishPrompt_normalWeightWithBoldEnglishWord() {
        val repo = DummyData.populatedRepository()
        val level = repo.levels.value.first { it.id == "level_5" }
        val task = repo.tasksById.value.getValue(level.taskId)

        val item = task.englishItems.first()
        val prompt = "Vad betyder ordet \"${item.englishWord}\"?\n(${item.simpleSentenceEn})"

        compose.setContent {
            val content = repo.content.active.value
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    MathActivityScreen(
                        level = level,
                        task = task,
                        mathEngine = remember { MathEngine() },
                        ttsEngine = createPlatformTextToSpeechEngine(),
                        showSwedishTtsButtons = false,
                        onLogMathError = { _, _, _, _ -> },
                        onFinishMathSession = { _, _ -> error("Not used in verify runs") },
                        content = content,
                        onEquipItem = {},
                        onSelectAvatar = {},
                        onCloseToMap = {},
                        choiceSeed = 52L
                    )
                }
            }
        }

        compose.onNodeWithText(prompt).assertExists()
        assertBoldRangeOnly(prompt, item.englishWord)
        shot("english_prompt")
    }
}
