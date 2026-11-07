package se.lixo.app.verify

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.Subject
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.DummyData
import se.lixo.app.ui.components.celebration.FlitChestMeter
import se.lixo.app.ui.components.celebration.LevelCompleteCard
import se.lixo.app.ui.components.celebration.chestFillAnimation
import se.lixo.app.ui.screens.map.CampaignMapScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies feature 003 (Flitkista holo chest meter, doc/plans/003-flitkista-holo-meter.md)
 * against the real composables as the app renders them: the real [CampaignMapScreen] HUD with a
 * real [se.lixo.app.data.repository.LixoRepository] seed profile, the real tap-to-open info
 * sheet, the a11y description buckets, and the real [LevelCompleteCard] corner slot.
 */
class FlitChestMeterTest {

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

    /** Recursively collects every text/contentDescription string in the current semantics tree. */
    private fun allTexts(): List<String> {
        val result = mutableListOf<String>()
        fun collect(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { result.add(it.text) }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { result.add(it) }
            node.children.forEach { collect(it) }
        }
        collect(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return result
    }

    private val oldPointsFormatRegex = Regex("""\d+(,5)? */ *10 *p""")

    private fun assertNoOldPointsText() {
        val texts = allTexts()
        assertTrue(
            "Found old points-format text: ${texts.filter { oldPointsFormatRegex.containsMatchIn(it) }}",
            texts.none { oldPointsFormatRegex.containsMatchIn(it) }
        )
        assertTrue(
            "Found 'Flitkista:' text with digits: ${texts.filter { it.contains("Flitkista:") && it.any(Char::isDigit) }}",
            texts.none { it.contains("Flitkista:") && it.any(Char::isDigit) }
        )
    }

    // --- Case 1 + 2: Map HUD meter position and tap-to-open/close info sheet. -----------------

    @Test
    fun mapHud_showsMeterRightOfStreakPill_andNoPointsText() {
        val repo = DummyData.populatedRepository()
        val baseProfile = repo.profiles.value.first()
        val profile = baseProfile.copy(chestProgressHalfPoints = 10)
        val progressByLevelId = repo.levelProgressByProfile.value[profile.id].orEmpty()
        val catalogById = repo.wardrobeCatalog.value.associateBy { it.id }

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progressByLevelId,
                    catalogById = catalogById,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = Subject.SWEDISH,
                    onSelectSubject = {},
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()
        shot("verify_003_map_hud")

        // chestProgressHalfPoints = 10, threshold = 20 -> fraction 0.5 -> "halvfull" bucket.
        val meterBounds = compose.onNodeWithContentDescription(LixoStrings.CHEST_METER_HALF, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val streakBounds = compose.onNode(
            hasText(profile.currentStreakDays.toString(), substring = false),
            useUnmergedTree = true
        ).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Expected the meter (left=${meterBounds.left}) to sit to the right of the streak pill " +
                "(right=${streakBounds.right})",
            meterBounds.left >= streakBounds.right
        )

        assertNoOldPointsText()
    }

    @Test
    fun mapHud_tapOpensInfoSheet_andClosesOnDismiss() {
        val repo = DummyData.populatedRepository()
        val profile = repo.profiles.value.first().copy(chestProgressHalfPoints = 10)
        val progressByLevelId = repo.levelProgressByProfile.value[profile.id].orEmpty()
        val catalogById = repo.wardrobeCatalog.value.associateBy { it.id }

        compose.setContent {
            LixoTheme {
                CampaignMapScreen(
                    profile = profile,
                    levels = repo.levels.value,
                    progressByLevelId = progressByLevelId,
                    catalogById = catalogById,
                    progressionEngine = ProgressionAndGiftEngine(),
                    ttsEngine = createPlatformTextToSpeechEngine(),
                    showSwedishTtsButtons = false,
                    selectedSubject = Subject.SWEDISH,
                    onSelectSubject = {},
                    onSelectLevel = {},
                    onSwitchProfileClick = {}
                )
            }
        }
        compose.waitForIdle()

        // Info sheet is not shown yet.
        assertTrue(compose.onAllNodesWithText(LixoStrings.CHEST_METER_INFO).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription(LixoStrings.CHEST_METER_HALF, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        shot("verify_003_chest_info_sheet")

        compose.onNodeWithText(LixoStrings.CHEST_METER_INFO).assertExists()
        compose.onNodeWithText(LixoStrings.CHEST_METER_INFO_TITLE).assertExists()

        compose.onNodeWithText(LixoStrings.SHEET_CLOSE_BTN).performClick()
        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText(LixoStrings.CHEST_METER_INFO).fetchSemanticsNodes().isEmpty())
    }

    // --- Case 3: a11y description buckets. -----------------------------------------------------

    @Test
    fun chestMeter_descriptionBuckets_matchHalfPointThresholds() {
        var halfPoints by mutableStateOf(0)
        compose.setContent {
            LixoTheme {
                FlitChestMeter(halfPoints = halfPoints, size = 32.dp)
            }
        }

        val expected = listOf(
            0 to LixoStrings.CHEST_METER_EMPTY,
            6 to LixoStrings.CHEST_METER_LOW,
            12 to LixoStrings.CHEST_METER_HALF,
            18 to LixoStrings.CHEST_METER_ALMOST_FULL
        )
        for ((points, description) in expected) {
            compose.runOnIdle { halfPoints = points }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertExists(
                "Expected contentDescription '$description' at halfPoints=$points"
            )
        }

        // Sanity: the four expected descriptions really are different strings.
        assertEquals(4, expected.map { it.second }.distinct().size)
    }

    // --- Case 4: LevelCompleteCard corner slot, same cornerContent the screens pass. -----------

    @Test
    fun levelCompleteCard_cornerMeter_sitsTopRight_andNoPointsText() {
        compose.setContent {
            LixoTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LevelCompleteCard(
                        starsEarned = 3,
                        netNewStars = 3,
                        statsSuffix = null,
                        showDuplicateGiftNote = false,
                        showDuplicateChestNote = false,
                        onContinue = {},
                        modifier = Modifier.width(320.dp).testTag("level_complete_card"),
                        cornerContent = {
                            FlitChestMeter(
                                halfPoints = 14,
                                size = 44.dp,
                                animation = chestFillAnimation(
                                    updatedHalfPoints = 14,
                                    awardedHalf = 4,
                                    chestAwarded = false
                                )
                            )
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
        shot("verify_003_level_complete_card")

        val cardBounds = compose.onNodeWithTag("level_complete_card").fetchSemanticsNode().boundsInRoot
        // halfPoints = 14, threshold = 20 -> fraction 0.7 -> "halvfull" bucket.
        val meterBounds = compose.onNodeWithContentDescription(LixoStrings.CHEST_METER_HALF, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        val cardCenterX = (cardBounds.left + cardBounds.right) / 2f
        val cardCenterY = (cardBounds.top + cardBounds.bottom) / 2f
        val meterCenterX = (meterBounds.left + meterBounds.right) / 2f
        val meterCenterY = (meterBounds.top + meterBounds.bottom) / 2f

        assertTrue(
            "Expected the meter to sit in the right half of the card (meterCenterX=$meterCenterX, " +
                "cardCenterX=$cardCenterX)",
            meterCenterX >= cardCenterX
        )
        assertTrue(
            "Expected the meter to sit in the top half of the card (meterCenterY=$meterCenterY, " +
                "cardCenterY=$cardCenterY)",
            meterCenterY <= cardCenterY
        )

        assertNoOldPointsText()
        assertFalse(
            "cornerContent should not render any duplicate-chest note text by default",
            allTexts().any { it == LixoStrings.CHEST_DUPLICATE_NOTE }
        )
    }
}
