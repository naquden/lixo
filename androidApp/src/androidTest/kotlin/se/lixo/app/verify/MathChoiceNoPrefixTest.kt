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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.screens.math.MATH_CHOICE_TAG_PREFIX
import se.lixo.app.ui.screens.math.MathActivityScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies bug 035 (math answer options show only the option text, no 1/2/3/4 prefix,
 * doc/plans/035-math-choice-no-prefix.md) against the real [MathActivityScreen] with a
 * seeded math level/task.
 */
class MathChoiceNoPrefixTest {

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
            instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun mathChoices_showOnlyOptionText_noNumberBadge() {
        val repo = DummyData.populatedRepository()
        val tasks = repo.tasksById.value
        val level = repo.levels.value.sortedBy { it.order }.first {
            tasks[it.taskId]?.let { t -> t.type == TaskType.MATH && t.mathProblems.isNotEmpty() } == true
        }
        val task = tasks.getValue(level.taskId)
        val problem = task.mathProblems.first()

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
                        onCloseToMap = {}
                    )
                }
            }
        }

        compose.waitForIdle()
        assertTrue(problem.choices.isNotEmpty())

        for ((i, choice) in problem.choices.withIndex()) {
            val tag = MATH_CHOICE_TAG_PREFIX + i
            compose.onNodeWithTag(tag).assertExists().assertTextEquals(choice)

            val texts = compose.onAllNodes(
                hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true
            ).fetchSemanticsNodes().flatMap { n ->
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
            assertEquals("choice_$i texts", listOf(choice), texts)
        }

        compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + problem.choices.size).assertDoesNotExist()

        compose.onNodeWithTag(MATH_CHOICE_TAG_PREFIX + problem.choices.lastIndex).performScrollTo()

        shot("035_math_choices")
    }
}
