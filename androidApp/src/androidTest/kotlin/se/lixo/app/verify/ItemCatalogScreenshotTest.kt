package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoTheme

private const val COLUMNS = 5
private const val ROWS = 6
private const val SHEET_WIDTH_PX = 1080
private const val SHEET_HEIGHT_PX = 1600
private const val SHEET_TAG = "item_sheet"

/**
 * Verifies feature 009 (item catalog screenshots, doc/plans/009-item-catalog-screenshots.md):
 * every item in the real wardrobe catalog renders without crashing in [ItemPreview],
 * grouped per [EquipmentSlot] into fixed-size grid sheets, and each sheet is captured as a PNG
 * for manual visual review.
 */
class ItemCatalogScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
        outDir = File(baseDir, "lixo_screenshots/items").apply { mkdirs() }
        outDir.listFiles()?.filter { it.extension == "png" || it.name == INDEX_FILE }?.forEach { it.delete() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(SHEET_TAG).captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun screenshotAllItems() {
        val repo = LixoRepository(FakeCloudGateway(), lexiconCache = null, isDebugBuild = false, profileStore = null, content = TestContent.repository)
        val content = repo.content.active.value
        val catalog = content.items

        assertTrue("catalog empty", catalog.size > 500)
        assertEquals("duplicate ids", catalog.size, catalog.map { it.id }.toSet().size)

        val sheets = buildSheets(catalog)
        assertEquals(
            EquipmentSlot.entries.sumOf { s -> (catalog.count { it.slot == s } + ITEMS_PER_SHEET - 1) / ITEMS_PER_SHEET },
            sheets.size
        )

        compose.mainClock.autoAdvance = false

        val sheetIndex = mutableIntStateOf(0)
        compose.setContent {
            LixoTheme {
                val page = sheets[sheetIndex.intValue]
                key(sheetIndex.intValue) {
                    ItemSheet(content, page.title, page.items)
                }
            }
        }

        val rendered = mutableListOf<String>()
        val index = StringBuilder("total=${catalog.size} sheets=${sheets.size}\n")

        sheets.forEachIndexed { i, page ->
            compose.runOnUiThread { sheetIndex.intValue = i }
            compose.mainClock.advanceTimeBy(500)

            compose.onNodeWithText(page.title).assertExists()
            compose.onNodeWithText(page.items.first().id).assertExists()
            compose.onNodeWithText(page.items.last().id).assertExists()

            val name = fileName(i, page)
            shot(name)

            rendered += page.items.map { it.id }
            index.append("$name.png: ").append(page.items.joinToString(", ") { it.id }).append('\n')
        }

        File(outDir, INDEX_FILE).writeText(index.toString())

        assertEquals(catalog.size, rendered.size)
        assertEquals(catalog.map { it.id }.toSet(), rendered.toSet())
    }

    companion object {
        private const val ITEMS_PER_SHEET = COLUMNS * ROWS
        private const val INDEX_FILE = "items_index.txt"

        private fun buildSheets(catalog: List<ContentItem>): List<ItemSheetPage> {
            val bySlot = catalog.groupBy { it.slot }
            return EquipmentSlot.entries.flatMap { slot ->
                val sorted = bySlot[slot].orEmpty().sortedWith(compareBy({ it.rarity.ordinal }, { it.id }))
                val chunks = sorted.chunked(ITEMS_PER_SHEET)
                chunks.mapIndexed { i, c -> ItemSheetPage(slot, i + 1, chunks.size, c) }
            }
        }

        private fun fileName(sheetIndex: Int, page: ItemSheetPage): String =
            "items_%02d_%s_p%d".format(sheetIndex + 1, page.slot.id, page.page)
    }
}

private data class ItemSheetPage(
    val slot: EquipmentSlot,
    val page: Int,
    val pageCount: Int,
    val items: List<ContentItem>
) {
    val title: String
        get() = "${slot.name} — sida $page/$pageCount (${items.size} föremål)"
}

@Composable
private fun ItemSheet(content: ActiveContent, title: String, items: List<ContentItem>) {
    val density = LocalDensity.current
    val w = with(density) { SHEET_WIDTH_PX.toDp() }
    val h = with(density) { SHEET_HEIGHT_PX.toDp() }

    Column(
        Modifier
            .testTag(SHEET_TAG)
            .requiredSize(w, h)
            .background(Color.White)
            .padding(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = LixoColors.TextPrimary,
            maxLines = 1
        )
        Spacer(Modifier.padding(top = 4.dp))

        for (r in 0 until ROWS) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until COLUMNS) {
                    val idx = r * COLUMNS + c
                    if (idx < items.size) {
                        ItemCell(content, items[idx], Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCell(content: ActiveContent, item: ContentItem, modifier: Modifier = Modifier) {
    val rarity = LixoColors.rarityColor(item.rarity)
    Column(modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .sizeIn(maxWidth = 72.dp, maxHeight = 72.dp)
                .border(2.dp, rarity, RoundedCornerShape(8.dp))
                .background(rarity.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            ItemPreview(content, item, Modifier.fillMaxSize())
        }
        Text(
            content.itemName(item),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
            fontWeight = FontWeight.Medium,
            color = LixoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.id,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 6.sp, lineHeight = 7.sp),
            color = LixoColors.TextMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
