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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.content.parseContentHex
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

/**
 * Fixed height of an item/"Ingen" card in the wardrobe grid: 3dp [LixoCard] lip, 2x10dp content
 * padding, 64dp preview, 4dp spacer, about 16dp rarity badge (labelSmall lineHeight 14sp plus 2dp
 * vertical padding), 3dp spacer and 14dp name line.
 */
private val WARDROBE_CARD_HEIGHT = 124.dp

/** Test tag on the unlocked-item [LazyHorizontalGrid], public so tests can import it. */
const val WARDROBE_ITEM_GRID_TAG = "wardrobe_item_grid"

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
 * Layout (doc/plans/031): the avatar viewport stage is pinned at the top and never scrolls away.
 * Its height is 30% of the available height, clamped to 150-240dp. Swatches, avatar picker and
 * slot pills sit below it. The unlocked-item [LazyHorizontalGrid] fills the remaining height: its
 * row count adapts (1-3 rows of fixed [WARDROBE_CARD_HEIGHT] cards) and it scrolls only
 * horizontally, so the avatar and controls above it stay visible while browsing. Switching slots
 * resets it to the "Ingen" card. The HAND_RIGHT and HAND_LEFT pills list the same shared hand
 * items (doc/plans/032).
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

    val tintChoices = listOf(0xFFF5A623L, 0xFF3498DBL, 0xFF2ECC71L, 0xFF9B59B6L, 0xFFE74C3CL)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        val avatarStageHeight = (maxHeight * 0.30f).coerceIn(150.dp, 240.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            // 3D Avatar Viewport Stage
            LixoCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(avatarStageHeight),
                cornerRadius = 24.dp,
                contentPadding = PaddingValues(8.dp)
            ) {
                AvatarViewport(
                    content = content,
                    appearance = profile.appearance,
                    modifier = Modifier.fillMaxSize(),
                    interactiveRotation = true
                )
            }

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
                            .clickable { selectedSlot = slot }
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

            // Unlocked Item Cards
            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val rows = ((maxHeight + 12.dp) / (WARDROBE_CARD_HEIGHT + 12.dp)).toInt().coerceIn(1, 3)
                        LazyHorizontalGrid(
                            rows = GridCells.Fixed(rows),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                                .height(WARDROBE_CARD_HEIGHT * rows + 12.dp * (rows - 1))
                                .testTag(WARDROBE_ITEM_GRID_TAG)
                        ) {
                            // LazyHorizontalGrid forces each item to its cell height, and
                            // GridCells.Fixed hands remainder pixels to the first rows, so each
                            // card is decoupled from its cell (wrapContentHeight unbounded + fixed
                            // height) to keep all cards pixel-identical; a card may overflow its
                            // cell by at most 1px, into the 12dp spacing.
                            items(displayItems, key = { it?.id ?: "__none__" }) { item ->
                                if (item == null) {
                                    // "Ingen" slot-empty card.
                                    val isSlotEmpty = !profile.appearance.equipped.containsKey(selectedSlot)
                                    LixoCard(
                                        modifier = Modifier.width(104.dp)
                                            .wrapContentHeight(Alignment.Top, unbounded = true)
                                            .height(WARDROBE_CARD_HEIGHT)
                                            .semantics { selected = isSlotEmpty },
                                        selected = isSlotEmpty,
                                        cornerRadius = 16.dp,
                                        contentPadding = PaddingValues(10.dp),
                                        onClick = { if (!isSlotEmpty) onEquipSlotItem(selectedSlot, null) }
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier.size(64.dp),
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
                                } else {
                                    val isEquipped = profile.appearance.equipped[selectedSlot] == item.id
                                    val rarityColor = LixoColors.rarityColor(item.rarity)

                                    LixoCard(
                                        modifier = Modifier.width(104.dp)
                                            .wrapContentHeight(Alignment.Top, unbounded = true)
                                            .height(WARDROBE_CARD_HEIGHT)
                                            .semantics { selected = isEquipped },
                                        selected = isEquipped,
                                        borderColor = if (isEquipped) LixoColors.Blue else rarityColor,
                                        backgroundColor = if (isEquipped) LixoColors.BlueTint else rarityColor.copy(alpha = 0.10f),
                                        cornerRadius = 16.dp,
                                        contentPadding = PaddingValues(10.dp),
                                        onClick = {
                                            if (isEquipped) {
                                                onEquipSlotItem(selectedSlot, null)
                                            } else {
                                                onEquipSlotItem(selectedSlot, item.id)
                                            }
                                        }
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier.size(64.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                ItemPreview(content, item, Modifier.size(64.dp))
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
                            }
                        }
                    }
                }
            }
        }
    }
}
