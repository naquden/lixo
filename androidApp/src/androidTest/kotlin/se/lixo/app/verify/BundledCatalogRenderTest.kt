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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies 021's bundled content pack (the real `files/content_pack.json`, loaded through
 * [BundledContentPack.readJson]/[BundledContentPack.validate]) renders through the real
 * [AvatarViewport]/[ItemPreview] composables, the same way the app does.
 *
 * The plan's old-vs-new renderer side-by-side comparison (`temp/verify021/old/`) is not
 * possible: `Avatar3DViewport`/`Avatar3DController` were deleted in feature 022
 * (`AvatarViewport.kt` only mentions the old name in a KDoc comment), so there is no old
 * renderer left to render into that directory.
 */
class BundledCatalogRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outDir: File
    private lateinit var content: ActiveContent

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
        outDir = File(baseDir, "lixo_screenshots/verify021").apply { mkdirs() }

        val json = runBlocking { BundledContentPack.readJson() }
        val result = BundledContentPack.validate(json)
        assertTrue("rejected: $result", result is ValidationResult.Accepted)
        val accepted = result as ValidationResult.Accepted
        assertTrue("skipped entries: ${accepted.skipped}", accepted.skipped.isEmpty())
        content = ActiveContent.from(accepted.pack)
        assertEquals(6, content.avatarsOrdered.size)
        assertTrue("expected >= 580 items, got ${content.items.size}", content.items.size >= 580)
    }

    /** Each test clears only its own screenshots; @Before runs per test, so a dir-wide wipe here deleted the other test's output. */
    private fun clearShots(prefix: String) {
        outDir.listFiles()?.filter { it.extension == "png" && it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun everyItem_rendersInItemPreview() {
        clearShots("items_")
        val batches = content.items.chunked(30)
        val batch = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LixoTheme {
                key(batch.intValue) {
                    Column(Modifier.background(Color.White)) {
                        batches[batch.intValue].chunked(5).forEach { row ->
                            Row {
                                row.forEach { item -> ItemPreview(content, item, Modifier.size(64.dp)) }
                            }
                        }
                    }
                }
            }
        }

        val rendered = mutableListOf<String>()
        batches.forEachIndexed { i, items ->
            compose.runOnUiThread { batch.intValue = i }
            compose.mainClock.advanceTimeBy(500)

            items.forEach { item ->
                compose.onAllNodesWithContentDescription(content.itemName(item)).onFirst().assertExists()
            }

            shot("items_%02d".format(i + 1))
            rendered += items.map { it.id }
        }

        assertEquals(content.items.map { it.id }.toSet(), rendered.toSet())
    }

    @Test
    fun everyAvatar_wearsStyles0to7_inEverySlot() {
        clearShots("avatar_")
        assertEquals(EquipmentSlot.entries.toSet(), STYLE_REPRESENTATIVES.keys)
        for ((slot, ids) in STYLE_REPRESENTATIVES) {
            assertEquals(8, ids.size)
            for (id in ids) {
                val item = content.itemsById[id]
                assertTrue("missing item $id for slot $slot", item != null)
                assertEquals("item $id has wrong slot", slot, item!!.slot)
            }
        }

        val cases = content.avatarsOrdered.flatMap { avatar -> (0..7).map { style -> avatar to style } }
        val idx = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LixoTheme {
                key(idx.intValue) {
                    val (avatar, style) = cases[idx.intValue]
                    Box(Modifier.background(Color.White)) {
                        AvatarViewport(
                            content,
                            appearanceFor(avatar.id, style),
                            Modifier.size(340.dp),
                            interactiveRotation = false
                        )
                    }
                }
            }
        }

        cases.forEachIndexed { i, (avatar, style) ->
            compose.runOnUiThread { idx.intValue = i }
            compose.mainClock.advanceTimeBy(500)

            val expected = content.avatarName(avatar.id) + ": " + EquipmentSlot.entries.joinToString(", ") { slot ->
                content.itemName(content.itemsById.getValue(STYLE_REPRESENTATIVES.getValue(slot)[style]))
            }
            compose.onNodeWithContentDescription(expected).assertExists()

            shot("avatar_${avatar.id}_style$style")
        }
    }

    /**
     * Fully equipped avatars look alike (the hat covers the ears, an equipped tail replaces the
     * default one), so render every avatar bare to check its own parts: ears, default tail, skin.
     */
    @Test
    fun everyAvatar_rendersBare() {
        clearShots("bare_")
        val avatars = content.avatarsOrdered
        val idx = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LixoTheme {
                key(idx.intValue) {
                    Box(Modifier.background(Color.White)) {
                        AvatarViewport(
                            content,
                            AvatarAppearance(avatars[idx.intValue].id),
                            Modifier.size(340.dp),
                            interactiveRotation = false
                        )
                    }
                }
            }
        }

        avatars.forEachIndexed { i, avatar ->
            compose.runOnUiThread { idx.intValue = i }
            compose.mainClock.advanceTimeBy(500)
            shot("bare_${avatar.id}")
        }
    }

    private fun appearanceFor(avatarId: String, style: Int) = AvatarAppearance(
        avatarId,
        equipped = EquipmentSlot.entries.associateWith { slot -> STYLE_REPRESENTATIVES.getValue(slot)[style] }
    )

    companion object {
        /**
         * One id per (slot, style) pair, index = style index 0..7. The first `pool == "standard"`
         * row per style template in `tools/content_pipeline/pack/catalog/generated.json`. These
         * ids are frozen by the pipeline's stability diff, so hard-coding them here is safe.
         */
        private val STYLE_REPRESENTATIVES: Map<EquipmentSlot, List<String>> = mapOf(
            EquipmentSlot.HAT to listOf(
                "item_hat_trollhatt_nattsvart",
                "item_hat_krona_djuphavsbla",
                "item_hat_riddarhjalm_korpsvart",
                "item_hat_pompomsmossa_korallrod",
                "item_hat_hoghatt_korallrod",
                "item_hat_keps_djuphavsbla",
                "item_hat_huva_nattsvart",
                "item_hat_hornhjalm_lavendellila"
            ),
            EquipmentSlot.SHOULDERS_BACK to listOf(
                "item_shoulders_back_cape_kungabla",
                "item_shoulders_back_vingar_nattsvart",
                "item_shoulders_back_ryggsack_solgyllene",
                "item_shoulders_back_halsduk_nattsvart",
                "item_shoulders_back_fjadermantel_korpsvart",
                "item_shoulders_back_snackskal_silverglans",
                "item_shoulders_back_pilkoger_isbla",
                "item_shoulders_back_ballong_nattsvart"
            ),
            EquipmentSlot.ARMS_GLOVES to listOf(
                "item_arms_gloves_handskar_skogsgron",
                "item_arms_gloves_pansarhandskar_myllergron",
                "item_arms_gloves_armband_nattsvart",
                "item_arms_gloves_vantar_barnstensgul",
                "item_arms_gloves_klor_rosenrosa",
                "item_arms_gloves_handledslindor_myllergron",
                "item_arms_gloves_ringar_rostbrun",
                "item_arms_gloves_taggarmskydd_djuphavsbla"
            ),
            EquipmentSlot.ARMOR_SHIRT to listOf(
                "item_armor_shirt_troja_vintervit",
                "item_armor_shirt_plattrustning_glodande",
                "item_armor_shirt_kapa_isbla",
                "item_armor_shirt_tunikamedbalte_solgyllene",
                "item_armor_shirt_vast_silverglans",
                "item_armor_shirt_luvtroja_barnstensgul",
                "item_armor_shirt_klanningsoverdel_glodande",
                "item_armor_shirt_ringbrynja_kungabla"
            ),
            EquipmentSlot.PANTS to listOf(
                "item_pants_byxor_barnstensgul",
                "item_pants_shorts_silverglans",
                "item_pants_benskenor_rostbrun",
                "item_pants_kjol_glodande",
                "item_pants_randigaleggings_isbla",
                "item_pants_cargobyxor_lavendellila",
                "item_pants_kilt_silverglans",
                "item_pants_posigabyxor_barnstensgul"
            ),
            EquipmentSlot.SHOES to listOf(
                "item_shoes_stovlar_solgyllene",
                "item_shoes_sneakers_korpsvart",
                "item_shoes_tofflor_skogsgron",
                "item_shoes_hogastovlar_glodande",
                "item_shoes_sandaler_silverglans",
                "item_shoes_raketskor_nattsvart",
                "item_shoes_traskor_galax",
                "item_shoes_taspetsskor_vintervit"
            ),
            EquipmentSlot.HAND_RIGHT to listOf(
                "item_hand_right_svard_vintervit",
                "item_hand_right_trollstav_rostbrun",
                "item_hand_right_stav_korpsvart",
                "item_hand_right_yxa_korallrod",
                "item_hand_right_hammare_silverglans",
                "item_hand_right_spjut_djuphavsbla",
                "item_hand_right_pilbage_silverglans",
                "item_hand_right_magisksfar_nattsvart"
            ),
            EquipmentSlot.HAND_LEFT to listOf(
                "item_hand_left_rundskold_isbla",
                "item_hand_left_kiteskold_myllergron",
                "item_hand_left_bok_glodande",
                "item_hand_left_lykta_vintervit",
                "item_hand_left_kompass_barnstensgul",
                "item_hand_left_timglas_skogsgron",
                "item_hand_left_fackla_vintervit",
                "item_hand_left_trolldryck_djuphavsbla"
            ),
            EquipmentSlot.TAIL to listOf(
                "item_tail_kattsvans_vintervit",
                "item_tail_hundsvans_korpsvart",
                "item_tail_ravsvans_skogsgron",
                "item_tail_drakssvans_nattsvart",
                "item_tail_odlesvans_barnstensgul",
                "item_tail_regnbagssvans_skogsgron",
                "item_tail_kometsvans_skogsgron",
                "item_tail_sjojungfrufena_glodande"
            )
        )
    }
}
