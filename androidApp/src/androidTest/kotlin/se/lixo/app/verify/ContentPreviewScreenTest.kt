package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.screens.debug.CONTENT_PREVIEW_GRID_TAG
import se.lixo.app.ui.screens.debug.ContentPreviewScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 020's debug [ContentPreviewScreen] against the real composable and the sample
 * content pack, following the `BottomNavLabelsTest` appops/shot pattern.
 */
class ContentPreviewScreenTest {

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
        outDir = File(baseDir, "lixo_screenshots/verify020").apply { mkdirs() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun grid_showsEveryItemName() {
        val content = SampleContentPack.activeContent()
        compose.setContent {
            LixoTheme { ContentPreviewScreen(content) }
        }
        compose.waitForIdle()

        for (item in content.pack.items) {
            val name = content.string(item.nameKey)
            compose.onNodeWithTag(CONTENT_PREVIEW_GRID_TAG).performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertExists()
        }
        shot("020_content_preview_grid")
    }

    @Test
    fun tapItem_equipsOnViewport() {
        val content = SampleContentPack.activeContent()
        compose.setContent {
            LixoTheme { ContentPreviewScreen(content) }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Katten Lixo").assertExists()

        val hatName = content.string(content.itemsById["item_hat_test_cone"]!!.nameKey)
        compose.onNodeWithTag(CONTENT_PREVIEW_GRID_TAG).performScrollToNode(hasText(hatName))
        compose.onNodeWithText(hatName).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Katten Lixo: $hatName").assertExists()
        compose.onNodeWithContentDescription("Katten Lixo").assertDoesNotExist()
        shot("020_content_preview_equipped")

        val dogName = content.string(content.avatarsById["avatar_dog"]!!.nameKey)
        compose.onNodeWithText(dogName).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("$dogName: $hatName").assertExists()

        compose.onNodeWithTag(CONTENT_PREVIEW_GRID_TAG).performScrollToNode(hasText(hatName))
        compose.onNodeWithText(hatName).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(dogName).assertExists()
    }
}
