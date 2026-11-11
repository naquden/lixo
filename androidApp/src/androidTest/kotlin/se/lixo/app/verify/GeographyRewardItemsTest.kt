package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.content.BundledContentPack
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.content.ValidationResult
import se.lixo.app.domain.model.Rarity
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies the 8 geography achievement-reward items (plan 037) render through the real bundled
 * content pack, the same [ItemPreview]/[AvatarViewport] composables the app uses (pattern:
 * [BundledCatalogRenderTest]).
 */
class GeographyRewardItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outDir: File
    private lateinit var content: ActiveContent

    private val expectedRarities = mapOf(
        "item_hand_right_geo_sverigeflagga" to Rarity.COMMON,
        "item_hand_left_geo_norgeflagga" to Rarity.COMMON,
        "item_hat_geo_upptackarhatt" to Rarity.UNCOMMON,
        "item_shoulders_back_geo_resryggsack" to Rarity.UNCOMMON,
        "item_shoulders_back_geo_nordenmantel" to Rarity.RARE,
        "item_hand_left_geo_kompass" to Rarity.RARE,
        "item_armor_shirt_geo_flaggtroja" to Rarity.EPIC,
        "item_hand_right_geo_jordglob" to Rarity.LEGENDARY
    )

    private val expectedRewards = mapOf(
        "ach_explore_world_1" to "item_hand_right_geo_sverigeflagga",
        "ach_explore_world_3" to "item_hand_left_geo_norgeflagga",
        "ach_explore_world_6" to "item_hat_geo_upptackarhatt",
        "ach_explore_world_10" to "item_shoulders_back_geo_resryggsack",
        "ach_explore_world_15" to "item_shoulders_back_geo_nordenmantel",
        "ach_explore_world_20" to "item_hand_left_geo_kompass",
        "ach_explore_world_25" to "item_armor_shirt_geo_flaggtroja",
        "ach_explore_world_30" to "item_hand_right_geo_jordglob"
    )

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {}
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots/verify037").apply { mkdirs() }

        val json = runBlocking { BundledContentPack.readJson() }
        val result = BundledContentPack.validate(json)
        assertTrue("rejected: $result", result is ValidationResult.Accepted)
        val accepted = result as ValidationResult.Accepted
        assertTrue("skipped entries: ${accepted.skipped}", accepted.skipped.isEmpty())
        content = ActiveContent.from(accepted.pack)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun geographyItems_existWithLadderRaritiesAndRewardMapping() {
        for ((itemId, rarity) in expectedRarities) {
            val item = content.itemsById[itemId]
            assertTrue("missing item $itemId", item != null)
            assertEquals("wrong rarity for $itemId", rarity, item!!.rarity)
        }
        for ((achievementId, itemId) in expectedRewards) {
            assertEquals(itemId, content.dropRules.achievementRewards[achievementId])
        }
    }

    @Test
    fun geographyItems_renderInItemPreviewAndOnEveryAvatar() {
        compose.mainClock.autoAdvance = false
        val items = expectedRarities.keys.map { content.itemsById.getValue(it) }
        val idx = mutableIntStateOf(0)

        compose.setContent {
            LixoTheme {
                key(idx.intValue) {
                    val item = items[idx.intValue]
                    Box(Modifier.background(Color.White)) {
                        Row {
                            ItemPreview(content, item, Modifier.size(96.dp))
                            content.avatarsOrdered.forEach { avatar ->
                                AvatarViewport(
                                    content,
                                    AvatarAppearance(avatar.id, equipped = mapOf(item.slot to item.id)),
                                    Modifier.size(160.dp),
                                    interactiveRotation = false
                                )
                            }
                        }
                    }
                }
            }
        }

        items.forEachIndexed { i, item ->
            compose.runOnUiThread { idx.intValue = i }
            compose.mainClock.advanceTimeBy(500)

            val nodes = compose.onAllNodesWithContentDescription(content.itemName(item)).fetchSemanticsNodes()
            assertTrue("expected >= 1 node for ${item.id}, got ${nodes.size}", nodes.isNotEmpty())

            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "geo_item_${item.id}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("rendered ${item.id} looks blank", nonWhitePixelFraction(bitmap) > 0.01)
        }
    }

    private fun nonWhitePixelFraction(bitmap: Bitmap): Double {
        var nonWhite = 0
        val stepX = (bitmap.width / 100).coerceAtLeast(1)
        val stepY = (bitmap.height / 100).coerceAtLeast(1)
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total++
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < 250 || g < 250 || b < 250) nonWhite++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 0.0 else nonWhite.toDouble() / total
    }
}
