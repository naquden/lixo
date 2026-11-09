package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.SampleContentPack
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 020's generic layer renderer against the real [AvatarViewport]/[ItemPreview]
 * composables and the sample content pack, following the `BottomNavLabelsTest` appops/shot
 * pattern.
 */
class LayerRendererScreenshotTest {

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
    fun avatarViewport_allSlotsAndHelmet_rendersAndDescribes() {
        val content = SampleContentPack.activeContent()
        val equipped = mapOf(
            EquipmentSlot.HAT to "item_hat_test_cone",
            EquipmentSlot.SHOULDERS_BACK to "item_shoulders_test_cape",
            EquipmentSlot.ARMS_GLOVES to "item_gloves_test_mitt",
            EquipmentSlot.ARMOR_SHIRT to "item_shirt_test_tee",
            EquipmentSlot.PANTS to "item_pants_test_shorts",
            EquipmentSlot.SHOES to "item_shoes_test_boot",
            EquipmentSlot.HAND_RIGHT to "item_hand_right_test_wand",
            EquipmentSlot.HAND_LEFT to "item_hand_left_test_shield",
            EquipmentSlot.TAIL to "item_tail_test_bow"
        )
        val appearance = AvatarAppearance("avatar_cat", equipped = equipped)
        val items = equipped.values.map { content.itemsById[it]!! }

        compose.setContent {
            LixoTheme {
                Column(Modifier.background(Color.White)) {
                    AvatarViewport(content, appearance, Modifier.size(340.dp))
                    items.chunked(5).forEach { row ->
                        Row {
                            row.forEach { item -> ItemPreview(content, item, Modifier.size(64.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(
            "Katten Lixo: Testhatt, Testcape, Testvante, Testtröja, Testshorts, Teststövel, Testtrollspö, Testsköld, Testrosett"
        ).assertExists()

        for (item in items) {
            compose.onNodeWithContentDescription(content.string(item.nameKey)).assertExists()
        }

        shot("020_avatar_all_slots")
    }

    @Test
    fun avatarViewport_dogWithUnknownItem_skipsIt() {
        val content = SampleContentPack.activeContent()
        val appearance = AvatarAppearance(
            "avatar_dog",
            equipped = mapOf(
                EquipmentSlot.HAT to "item_hat_test_cone",
                EquipmentSlot.TAIL to "item_does_not_exist"
            )
        )

        compose.setContent {
            LixoTheme {
                AvatarViewport(content, appearance, Modifier.size(340.dp))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Hunden Bosse: Testhatt").assertExists()
        shot("020_dog_unknown_item")
    }
}
