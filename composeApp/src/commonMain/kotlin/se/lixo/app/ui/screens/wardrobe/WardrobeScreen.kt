package se.lixo.app.ui.screens.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.accepts
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoCircleIconButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/** Owned/total collection count for one [rarity], against the whole catalog passed in. */
data class RarityCollectionCount(val rarity: Rarity, val owned: Int, val total: Int)

/**
 * Per-rarity owned/total counts across [allItems] (the whole catalog, not just chest-eligible
 * items), always in [Rarity.entries] order, even when a rarity's total is 0. [owned] only counts
 * catalog items whose id is in [unlockedItemIds], so stale/unknown unlocked ids (e.g. from an
 * older install) can never push a rarity's owned count above its total.
 */
fun rarityCollectionCounts(
    allItems: List<ContentItem>,
    unlockedItemIds: Set<String>
): List<RarityCollectionCount> = Rarity.entries.map { r ->
    // Retired items (doc/plans/015) only count once owned, so the collection total never
    // shows items the player can no longer obtain.
    val ofRarity = allItems.filter { it.rarity == r && (!it.retired || it.id in unlockedItemIds) }
    RarityCollectionCount(
        rarity = r,
        owned = ofRarity.count { it.id in unlockedItemIds },
        total = ofRarity.size
    )
}

const val WARDROBE_RARITY_COUNT_TAG_PREFIX = "wardrobe_rarity_count_"

fun wardrobeRarityCountTag(rarity: Rarity): String = WARDROBE_RARITY_COUNT_TAG_PREFIX + rarity.id

/** Test tag on the unlocked-item [LazyHorizontalGrid], public so tests can import it. */
const val WARDROBE_ITEM_GRID_TAG = "wardrobe_item_grid"

/** Test tag on the avatar viewport [LixoCard], public so tests can measure the stage. */
const val WARDROBE_AVATAR_STAGE_TAG = "wardrobe_avatar_stage"

/**
 * Share of the leftover height (after the header, chips, swatches, picker and pills are measured)
 * given to the avatar stage. Chosen, rather than the 034 plan's 0.42, from measuring the 031
 * screenshots at density 2.625: the unweighted rows take about 262-280dp, so 0.42 would shrink the
 * 360x640 stage below today's 182dp and fail the tall-screen 0.30-height check (doc/plans/034).
 */
private const val WARDROBE_STAGE_WEIGHT = 0.5f

/** Share of the leftover height given to the item area. See [WARDROBE_STAGE_WEIGHT]. */
private const val WARDROBE_ITEMS_WEIGHT = 0.5f

/**
 * Fallback stage height on very short screens or very large fonts, and the minimum weighted stage
 * height below which the fallback kicks in instead.
 */
private val WARDROBE_MIN_STAGE_HEIGHT = 150.dp

/** Gap between item cards, in both grid directions. */
private val WARDROBE_GRID_GAP = 12.dp

/** Most rows the item grid will ever lay out, regardless of available height. */
private const val WARDROBE_GRID_MAX_ROWS = 3

/** Cards never stretch past this multiple of the font-scaled minimum card height. */
private const val WARDROBE_CARD_MAX_STRETCH = 1.4f

/**
 * Estimated dp height of the rows above and below the stage/items split, excluding the text line
 * heights (see [WARDROBE_FIXED_ROWS_TEXT_SP]): spacers 8 + 5x10 = 58dp, swatches 32dp, picker/pill/chip
 * vertical padding 38dp, plus slack for the rarity chips wrapping to a second row on narrow screens.
 */
private val WARDROBE_FIXED_ROWS_DP = 140.dp

/**
 * Line heights of the fixed-row text in [LixoTheme][se.lixo.app.ui.theme.LixoTheme]'s typography,
 * each converted to dp separately (not summed in sp first, then converted once) so Android 14+'s
 * nonlinear `FontScaleConverter` — which maps sp sizes at or above 100sp almost 1:1 regardless of
 * font scale — still grows the estimate at large font scales, the same way [minCard] (in
 * [WardrobeScreen]) converts its own text lines separately: title 40sp (displayLarge), subtitle
 * 22sp (bodyMedium), rarity chip 14sp (labelSmall), avatar picker 24sp (titleMedium), slot pill
 * 16sp (labelMedium).
 */
private val WARDROBE_FIXED_ROWS_TEXT_SP = listOf(40.sp, 22.sp, 14.sp, 24.sp, 16.sp)

/** Row/cell size math for the item grid, all in whole pixels so rows never carry a 1px remainder. */
data class WardrobeGridMetrics(val rows: Int, val cellPx: Int, val gridHeightPx: Int)

/**
 * Works out how many rows of item cards fit in [areaHeightPx], and the whole-pixel cell height each
 * row gets. [minCardPx] is the font-scaled minimum card height; cells never grow past
 * [WARDROBE_CARD_MAX_STRETCH] times it, and never shrink below it either, with one exception: when
 * [areaHeightPx] itself is smaller than [minCardPx], this still returns rows=1 and
 * cellPx=[areaHeightPx] (a cell shorter than [minCardPx]). Callers that need every card to be at
 * least [minCardPx] tall must guard for that case themselves (see `WardrobeItemsArea`). Always
 * returns at least 1 row and at most [WARDROBE_GRID_MAX_ROWS] rows.
 */
fun wardrobeGridMetrics(areaHeightPx: Int, gapPx: Int, minCardPx: Int): WardrobeGridMetrics {
    val area = areaHeightPx.coerceAtLeast(0)
    val rows = ((area + gapPx) / (minCardPx + gapPx).coerceAtLeast(1)).coerceIn(1, WARDROBE_GRID_MAX_ROWS)
    val maxCellPx = (minCardPx * WARDROBE_CARD_MAX_STRETCH).toInt()
    val cellPx = ((area - gapPx * (rows - 1)) / rows).coerceAtMost(maxCellPx).coerceAtLeast(0)
    return WardrobeGridMetrics(rows, cellPx, cellPx * rows + gapPx * (rows - 1))
}

/**
 * True when [maxHeight] minus the estimated [fixedRowsHeight] leaves less than
 * [WARDROBE_MIN_STAGE_HEIGHT] for the weighted stage, or less than one [minCard]-tall row for the
 * item area. When true, the screen falls back to a scrolling column with fixed heights instead of
 * the weighted split.
 */
fun wardrobeUsesScrollFallback(maxHeight: Dp, fixedRowsHeight: Dp, minCard: Dp): Boolean {
    val remaining = maxHeight - fixedRowsHeight
    return remaining * WARDROBE_STAGE_WEIGHT < WARDROBE_MIN_STAGE_HEIGHT || remaining * WARDROBE_ITEMS_WEIGHT < minCard
}

/** Row of per-rarity collection count chips, shown at the top of the wardrobe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RarityCollectionRow(counts: List<RarityCollectionCount>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        counts.forEach { c ->
            val rarityColor = LixoColors.rarityColor(c.rarity)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(rarityColor.copy(alpha = 0.18f))
                    .border(1.dp, rarityColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = LixoStrings.wardrobeRarityCount(c.rarity, c.owned, c.total),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = rarityColor,
                    maxLines = 1,
                    modifier = Modifier.testTag(wardrobeRarityCountTag(c.rarity))
                )
            }
        }
    }
}

/**
 * Duolingo-styled 3D Wardrobe Screen:
 * - Clean 3D avatar viewport stage.
 * - Simple color swatches for base avatar customization.
 * - Horizontal avatar character picker with locked star costs.
 * - Equipment slot category pills.
 * - Fortnite rarity-accented item cards with clear equip/unequip states.
 *
 * Layout (doc/plans/031, 034): the avatar stage and the unlocked-item grid share, by
 * [WARDROBE_STAGE_WEIGHT]/[WARDROBE_ITEMS_WEIGHT] weight, whatever height is left below the
 * header, chips, swatches, picker and slot pills. The grid's row count (1-[WARDROBE_GRID_MAX_ROWS])
 * is chosen from the font-scaled minimum card height, and every cell gets a whole-pixel share of
 * the item area, capped at [WARDROBE_CARD_MAX_STRETCH] times the minimum and centred, so cards
 * fill their row exactly with no leftover band. On very short screens or very large fonts, where
 * the estimated fixed rows would leave less than [WARDROBE_MIN_STAGE_HEIGHT] of stage or less than
 * one card row, the screen falls back to a vertically scrolling column with a fixed
 * [WARDROBE_MIN_STAGE_HEIGHT] stage and one row of cards, so both the avatar and at least one item
 * stay reachable. The grid scrolls only horizontally and switching slots resets it to the "Ingen"
 * card. The HAND_RIGHT and HAND_LEFT pills list the same shared hand items (doc/plans/032).
 */
@Composable
fun WardrobeScreen(
    profile: ChildProfile,
    content: ActiveContent,
    unlockedItemIds: Set<String>,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectAvatar: (String) -> Unit,
    onEquipSlotItem: (EquipmentSlot, String?) -> Unit,
    onCustomizeBaseTraits: (Long, Int, Int) -> Unit,
    onBack: () -> Unit
) {
    var selectedSlot by remember { mutableStateOf(EquipmentSlot.HAT) }
    val gridState = remember(selectedSlot) { LazyGridState() }
    val rarityCounts = remember(content, unlockedItemIds) {
        rarityCollectionCounts(content.items, unlockedItemIds)
    }

    val unlockedItemsForSlot = remember(content, unlockedItemIds, selectedSlot) {
        content.items
            .filter { selectedSlot.accepts(it.slot) && unlockedItemIds.contains(it.id) }
            .sortedWith(
                compareByDescending<ContentItem> { it.rarity.ordinal }
                    .thenBy { it.id }
            )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        val density = LocalDensity.current
        // Font-scaled minimum card height: 64dp preview + 3dp lip + 20dp content padding (2x10dp) +
        // 7dp spacers (4+3) + 2dp badge vertical padding = 36dp of fixed chrome, plus the badge
        // (14sp) and name (16sp) text lines converted separately, which matches Android 14
        // nonlinear font scaling (a combined `(14.sp + 16.sp).toDp()` would not even compile, since
        // TextUnit has no `plus` operator).
        val minCard = with(density) { 64.dp + 36.dp + 14.sp.toDp() + 16.sp.toDp() }
        val fixedRows = with(density) {
            WARDROBE_FIXED_ROWS_DP + WARDROBE_FIXED_ROWS_TEXT_SP.fold(0.dp) { acc, sp -> acc + sp.toDp() }
        }

        val useScrollFallback = wardrobeUsesScrollFallback(maxHeight, fixedRows, minCard)

        // One Column/WardrobeColumnContent call site for both the weighted and scroll-fallback
        // layouts (only the modifiers differ): switching between them at a font-scale or
        // orientation change then keeps remembered state (AvatarViewport yaw, picker/pill scroll)
        // instead of recreating the composition.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .let { if (useScrollFallback) it.verticalScroll(rememberScrollState()) else it },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WardrobeColumnContent(
                profile = profile,
                content = content,
                rarityCounts = rarityCounts,
                unlockedItemsForSlot = unlockedItemsForSlot,
                selectedSlot = selectedSlot,
                onSelectSlot = { selectedSlot = it },
                gridState = gridState,
                minCard = minCard,
                ttsEngine = ttsEngine,
                showSwedishTtsButtons = showSwedishTtsButtons,
                onSelectAvatar = onSelectAvatar,
                onEquipSlotItem = onEquipSlotItem,
                onCustomizeBaseTraits = onCustomizeBaseTraits,
                onBack = onBack,
                stageModifier = Modifier.fillMaxWidth().let {
                    if (useScrollFallback) it.height(WARDROBE_MIN_STAGE_HEIGHT) else it.weight(WARDROBE_STAGE_WEIGHT)
                },
                itemsModifier = Modifier.fillMaxWidth().let {
                    if (useScrollFallback) it.height(minCard) else it.weight(WARDROBE_ITEMS_WEIGHT)
                }
            )
        }
    }
}

/**
 * Emits the wardrobe's content, in order, as direct children of the caller's [Column] (so
 * [stageModifier] and [itemsModifier] can carry a [androidx.compose.foundation.layout.ColumnScope]
 * `weight`, built by the caller): header, rarity chips, avatar stage, base-trait swatches, avatar
 * picker, equipment slot pills, then the item area. Shared with both the weighted and the
 * scroll-fallback branches of [WardrobeScreen] so neither copies the UI.
 */
@Composable
private fun WardrobeColumnContent(
    profile: ChildProfile,
    content: ActiveContent,
    rarityCounts: List<RarityCollectionCount>,
    unlockedItemsForSlot: List<ContentItem>,
    selectedSlot: EquipmentSlot,
    onSelectSlot: (EquipmentSlot) -> Unit,
    gridState: LazyGridState,
    minCard: Dp,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectAvatar: (String) -> Unit,
    onEquipSlotItem: (EquipmentSlot, String?) -> Unit,
    onCustomizeBaseTraits: (Long, Int, Int) -> Unit,
    onBack: () -> Unit,
    stageModifier: Modifier,
    itemsModifier: Modifier
) {
    val tintChoices = listOf(0xFFF5A623L, 0xFF3498DBL, 0xFF2ECC71L, 0xFF9B59B6L, 0xFFE74C3CL)

    // Header
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        LixoCircleIconButton(
            icon = LixoIcons.ArrowBack,
            onClick = onBack
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = LixoStrings.WARDROBE_TITLE,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary
            )
            Text(
                text = LixoStrings.WARDROBE_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )
        }
        if (showSwedishTtsButtons) {
            TtsIconButton(
                textToSpeak = "${LixoStrings.WARDROBE_TITLE}. ${LixoStrings.WARDROBE_SUBTITLE}",
                ttsEngine = ttsEngine
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    RarityCollectionRow(rarityCounts)

    Spacer(Modifier.height(10.dp))

    WardrobeAvatarStage(content, profile.appearance, stageModifier)

    Spacer(Modifier.height(10.dp))

    // Base Trait & Expression Customization Bar
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = LixoStrings.WARDROBE_TRAITS_COLOR_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = LixoColors.TextSecondary
        )
        Spacer(Modifier.width(12.dp))
        val activeAvatar = remember(content, profile.appearance.avatarId) {
            content.avatarsById[profile.appearance.avatarId] ?: content.defaultAvatar
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tintChoices.forEach { hex ->
                val isSelected = (profile.appearance.tintHex ?: parseContentHex(activeAvatar.defaultTintHex)) == hex
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(hex))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) LixoColors.TextPrimary else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable {
                            onCustomizeBaseTraits(
                                hex,
                                (profile.appearance.eyeIndex + 1) % 2,
                                profile.appearance.smileIndex
                            )
                        }
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // 6 Avatars Character Picker
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content.avatarsOrdered.forEach { avatar ->
            val isUnlocked = avatar.id in content.unlockedAvatarIds(profile.unlockedAvatarIds)
            val isActive = (content.avatarsById[profile.appearance.avatarId] ?: content.defaultAvatar).id == avatar.id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            isActive -> LixoColors.BlueTint
                            isUnlocked -> LixoColors.Surface
                            else -> LixoColors.SurfaceElevated
                        }
                    )
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = if (isActive) LixoColors.Blue else LixoColors.Border,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable(enabled = isUnlocked) {
                        onSelectAvatar(avatar.id)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isUnlocked) {
                        LixoIcon(icon = LixoIcons.Lock, tint = LixoColors.TextMuted, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = if (isUnlocked) content.string(avatar.nameKey)
                        else "${content.string(avatar.nameKey)} (${avatar.unlock.stars}⭐)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                        color = if (isActive) LixoColors.Blue else if (isUnlocked) LixoColors.TextPrimary else LixoColors.TextMuted
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // Equipment Slot Category Selector
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EquipmentSlot.entries.forEach { slot ->
            val isSelected = slot == selectedSlot
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) LixoColors.Green else LixoColors.Surface)
                    .then(
                        if (!isSelected) Modifier.border(1.dp, LixoColors.Border, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clickable { onSelectSlot(slot) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = LixoStrings.slotName(slot),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                    color = if (isSelected) Color.White else LixoColors.TextSecondary
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    WardrobeItemsArea(
        modifier = itemsModifier,
        content = content,
        profile = profile,
        selectedSlot = selectedSlot,
        unlockedItemsForSlot = unlockedItemsForSlot,
        gridState = gridState,
        minCard = minCard,
        onEquipSlotItem = onEquipSlotItem
    )
}

/** The pinned 3D avatar viewport stage. */
@Composable
private fun WardrobeAvatarStage(
    content: ActiveContent,
    appearance: AvatarAppearance,
    modifier: Modifier
) {
    LixoCard(
        modifier = modifier.testTag(WARDROBE_AVATAR_STAGE_TAG),
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(8.dp)
    ) {
        AvatarViewport(
            content = content,
            appearance = appearance,
            modifier = Modifier.fillMaxSize(),
            interactiveRotation = true
        )
    }
}

/**
 * The unlocked-item area: either the empty-slot mystery card, or a [LazyHorizontalGrid] of cards
 * whose row count and whole-pixel cell size come from [wardrobeGridMetrics], using the real
 * measured height ([BoxWithConstraints.constraints], in px) rather than the dp [BoxWithConstraints.maxHeight]
 * so the cells round-trip to whole pixels with no remainder.
 */
@Composable
private fun WardrobeItemsArea(
    modifier: Modifier,
    content: ActiveContent,
    profile: ChildProfile,
    selectedSlot: EquipmentSlot,
    unlockedItemsForSlot: List<ContentItem>,
    gridState: LazyGridState,
    minCard: Dp,
    onEquipSlotItem: (EquipmentSlot, String?) -> Unit
) {
    Box(modifier) {
        if (unlockedItemsForSlot.isEmpty()) {
            LixoCard(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.TextMuted, size = 32.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = LixoStrings.WARDROBE_MYSTERY_FOOTER,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            // "Ingen" (null) is always the first choice: selecting it empties the slot. Using a
            // lazy grid (rather than a non-lazy FlowRow/forEach over every unlocked item, which
            // could be 60+ per slot) keeps card composition/measurement bounded to what's
            // actually visible, with a stable key per item.id so equip-state recomposition
            // doesn't rebuild unrelated cards.
            val displayItems: List<ContentItem?> = remember(unlockedItemsForSlot) {
                listOf(null) + unlockedItemsForSlot
            }
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val scrollState = rememberScrollState()
                val gapPx = with(density) { WARDROBE_GRID_GAP.roundToPx() }
                val minCardPx = with(density) { minCard.roundToPx() }
                val m = wardrobeGridMetrics(constraints.maxHeight, gapPx, minCardPx)
                // wardrobeGridMetrics only shrinks a cell below minCardPx when the whole area is
                // smaller than one minCardPx row (rows is then always 1); fall back to a
                // minCardPx-tall row and let the area scroll instead of squeezing cards.
                val needsAreaScroll = m.cellPx < minCardPx
                val rows = if (needsAreaScroll) 1 else m.rows
                val cellPx = if (needsAreaScroll) minCardPx else m.cellPx
                val cellDp = with(density) { cellPx.toDp() }
                val gridHeight = with(density) { (cellPx * rows + gapPx * (rows - 1)).toDp() }
                val cardWidth = (cellDp * 0.84f).coerceIn(96.dp, 132.dp)
                val previewSize = (cellDp - (minCard - 64.dp)).coerceIn(56.dp, 96.dp)

                LazyHorizontalGrid(
                    rows = GridCells.Fixed(rows),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(WARDROBE_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(WARDROBE_GRID_GAP),
                    modifier = Modifier.fillMaxWidth()
                        // Scroll container takes the area's bounded height; the grid inside keeps its
                        // fixed gridHeight (taller than the area only when needsAreaScroll).
                        .let { if (needsAreaScroll) it.verticalScroll(scrollState) else it }
                        .height(gridHeight)
                        .testTag(WARDROBE_ITEM_GRID_TAG)
                ) {
                    items(displayItems, key = { it?.id ?: "__none__" }) { item ->
                        if (item == null) {
                            val isSlotEmpty = !profile.appearance.equipped.containsKey(selectedSlot)
                            WardrobeNoneCard(
                                isSlotEmpty = isSlotEmpty,
                                cardWidth = cardWidth,
                                cardHeight = cellDp,
                                previewSize = previewSize,
                                onClick = { if (!isSlotEmpty) onEquipSlotItem(selectedSlot, null) }
                            )
                        } else {
                            val isEquipped = profile.appearance.equipped[selectedSlot] == item.id
                            WardrobeItemCard(
                                content = content,
                                item = item,
                                isEquipped = isEquipped,
                                cardWidth = cardWidth,
                                cardHeight = cellDp,
                                previewSize = previewSize,
                                onClick = {
                                    if (isEquipped) {
                                        onEquipSlotItem(selectedSlot, null)
                                    } else {
                                        onEquipSlotItem(selectedSlot, item.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The "Ingen" (slot-empty) card in the item grid. */
@Composable
private fun WardrobeNoneCard(
    isSlotEmpty: Boolean,
    cardWidth: Dp,
    cardHeight: Dp,
    previewSize: Dp,
    onClick: () -> Unit
) {
    LixoCard(
        modifier = Modifier.width(cardWidth)
            .height(cardHeight)
            .semantics { selected = isSlotEmpty },
        selected = isSlotEmpty,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(10.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(previewSize),
                contentAlignment = Alignment.Center
            ) {
                LixoIcon(icon = LixoIcons.Close, tint = LixoColors.TextMuted, size = 24.dp)
                if (isSlotEmpty) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(LixoColors.GreenLip),
                        contentAlignment = Alignment.Center
                    ) {
                        LixoIcon(icon = LixoIcons.Check, tint = Color.White, size = 12.dp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = LixoStrings.WARDROBE_NONE_ITEM,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = LixoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One unlocked item's card in the item grid. */
@Composable
private fun WardrobeItemCard(
    content: ActiveContent,
    item: ContentItem,
    isEquipped: Boolean,
    cardWidth: Dp,
    cardHeight: Dp,
    previewSize: Dp,
    onClick: () -> Unit
) {
    val rarityColor = LixoColors.rarityColor(item.rarity)

    LixoCard(
        modifier = Modifier.width(cardWidth)
            .height(cardHeight)
            .semantics { selected = isEquipped },
        selected = isEquipped,
        borderColor = if (isEquipped) LixoColors.Blue else rarityColor,
        backgroundColor = if (isEquipped) LixoColors.BlueTint else rarityColor.copy(alpha = 0.10f),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(10.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(previewSize),
                contentAlignment = Alignment.Center
            ) {
                ItemPreview(content, item, Modifier.size(previewSize))
                if (isEquipped) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(LixoColors.GreenLip),
                        contentAlignment = Alignment.Center
                    ) {
                        LixoIcon(icon = LixoIcons.Check, tint = Color.White, size = 12.dp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(rarityColor.copy(alpha = 0.18f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = LixoStrings.rarityName(item.rarity).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = rarityColor,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 7.sp,
                        maxFontSize = 10.sp,
                        stepSize = 0.5.sp
                    )
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = content.itemName(item),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = LixoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
