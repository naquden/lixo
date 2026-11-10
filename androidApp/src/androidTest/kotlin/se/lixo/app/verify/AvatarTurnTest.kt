package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.theme.LixoTheme

private const val CAPE = "item_shoulders_back_sv_cape_1"
private const val QUIVER = "item_shoulders_back_pilkoger_isbla"
private const val BALLOON = "item_shoulders_back_ballong_kungabla"

/**
 * Verifies 033 (rotate avatar up to ±90° so SHOULDERS_BACK gear peeks out) against the real
 * [AvatarViewport] composable and the real bundled content pack ([TestContent.active]).
 */
class AvatarTurnTest {

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
        outDir = File(baseDir, "lixo_screenshots/verify033").apply { mkdirs() }
    }

    private fun yawSuffix(deg: Int): String = if (deg < 0) "m${-deg}" else "$deg"

    @Test
    fun rendersTurnAngles() {
        val content = TestContent.active
        val avatars = listOf("avatar_cat", "avatar_knight")
        val items = listOf(CAPE, QUIVER, BALLOON)
        val yaws = listOf(-90, -45, 0, 45, 90)
        val cases = avatars.flatMap { avatar -> items.flatMap { item -> yaws.map { yaw -> Triple(avatar, item, yaw) } } }
        val idx = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LixoTheme {
                key(idx.intValue) {
                    val (avatar, item, yaw) = cases[idx.intValue]
                    Box(Modifier.background(Color.White)) {
                        AvatarViewport(
                            content,
                            AvatarAppearance(avatar, equipped = mapOf(EquipmentSlot.SHOULDERS_BACK to item)),
                            Modifier.size(340.dp),
                            initialYawDeg = yaw.toFloat()
                        )
                    }
                }
            }
        }

        val bitmapsByAvatarItem = HashMap<Pair<String, String>, MutableMap<Int, Bitmap>>()

        cases.forEachIndexed { i, (avatar, item, yaw) ->
            compose.runOnUiThread { idx.intValue = i }
            compose.mainClock.advanceTimeBy(500)

            val expected = content.avatarName(avatar) + ": " + content.itemName(content.itemsById.getValue(item))
            compose.onNodeWithContentDescription(expected).assertExists()

            val avatarShort = avatar.removePrefix("avatar_")
            val itemShort = when (item) {
                CAPE -> "cape"
                QUIVER -> "quiver"
                BALLOON -> "balloon"
                else -> item
            }
            val name = "033_${avatarShort}_${itemShort}_yaw${yawSuffix(yaw)}"

            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            if (yaw == 0 || yaw == 90) {
                bitmapsByAvatarItem.getOrPut(avatar to item) { mutableMapOf() }[yaw] = bitmap
            }
        }

        for ((key, byYaw) in bitmapsByAvatarItem) {
            val zero = byYaw[0] ?: continue
            val ninety = byYaw[90] ?: continue
            val diffFrac = diffFraction(zero, ninety, threshold = 16)
            assertTrue(
                "${key.first}/${key.second}: yaw 0 and yaw 90 look identical (diff fraction $diffFrac)",
                diffFrac > 0.01f
            )
        }
    }

    @Test
    fun dragIsClampedAt90() {
        val content = TestContent.active
        val appearance = AvatarAppearance("avatar_cat", equipped = mapOf(EquipmentSlot.SHOULDERS_BACK to CAPE))
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LixoTheme {
                Column(Modifier.background(Color.White)) {
                    AvatarViewport(content, appearance, Modifier.size(200.dp).testTag("turn_drag"))
                    Row {
                        AvatarViewport(
                            content,
                            appearance,
                            Modifier.size(200.dp).testTag("turn_ref_p90"),
                            interactiveRotation = false,
                            initialYawDeg = 90f
                        )
                        AvatarViewport(
                            content,
                            appearance,
                            Modifier.size(200.dp).testTag("turn_ref_m90"),
                            interactiveRotation = false,
                            initialYawDeg = -90f
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithTag("turn_drag").performTouchInput {
            repeat(4) { swipeRight() }
        }
        compose.mainClock.advanceTimeByFrame()

        val dragP90 = compose.onNodeWithTag("turn_drag").captureToImage().asAndroidBitmap()
        val refP90 = compose.onNodeWithTag("turn_ref_p90").captureToImage().asAndroidBitmap()
        File(outDir, "033_drag_p90.png").outputStream().use { dragP90.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(outDir, "033_ref_p90.png").outputStream().use { refP90.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val diffP90 = diffFraction(dragP90, refP90)
        assertTrue("drag-right clamp: diff fraction $diffP90 exceeds 0.5%", diffP90 <= 0.005f)

        compose.onNodeWithTag("turn_drag").performTouchInput {
            repeat(8) { swipeLeft() }
        }
        compose.mainClock.advanceTimeByFrame()

        val dragM90 = compose.onNodeWithTag("turn_drag").captureToImage().asAndroidBitmap()
        val refM90 = compose.onNodeWithTag("turn_ref_m90").captureToImage().asAndroidBitmap()
        File(outDir, "033_drag_m90.png").outputStream().use { dragM90.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(outDir, "033_ref_m90.png").outputStream().use { refM90.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val diffM90 = diffFraction(dragM90, refM90)
        assertTrue("drag-left clamp: diff fraction $diffM90 exceeds 0.5%", diffM90 <= 0.005f)
    }

    /** The share of pixels differing by more than [threshold] in any channel between [a] and [b]. */
    private fun diffFraction(a: Bitmap, b: Bitmap, threshold: Int = 24): Float {
        require(a.width == b.width && a.height == b.height) {
            "size mismatch: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var differing = 0
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val dr = kotlin.math.abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
            val dg = kotlin.math.abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
            val db = kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
            val da = kotlin.math.abs(((ca shr 24) and 0xFF) - ((cb shr 24) and 0xFF))
            if (dr > threshold || dg > threshold || db > threshold || da > threshold) differing++
        }
        return differing.toFloat() / pa.size
    }
}
