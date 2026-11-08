package se.lixo.app.ui.screens.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.components.avatar3d.WardrobeItemPreview
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
    allItems: List<WardrobeItem>,
    unlockedItemIds: Set<String>
): List<RarityCollectionCount> = Rarity.entries.map { r ->
    val ofRarity = allItems.filter { it.rarity == r }
    RarityCollectionCount(
        rarity = r,
        owned = ofRarity.count { it.id in unlockedItemIds },
        total = ofRarity.size
    )
}

const val WARDROBE_RARITY_COUNT_TAG_PREFIX = "wardrobe_rarity_count_"

fun wardrobeRarityCountTag(rarity: Rarity): String = WARDROBE_RARITY_COUNT_TAG_PREFIX + rarity.id

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
 */
@Composable
fun WardrobeScreen(
    profile: ChildProfile,
    allWardrobeItems: List<WardrobeItem>,
    unlockedItemIds: Set<String>,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectAvatarType: (AvatarType) -> Unit,
    onEquipSlotItem: (EquipmentSlot, String?) -> Unit,
    onCustomizeBaseTraits: (Long, Int, Int) -> Unit,
    onBack: () -> Unit
) {
    var selectedSlot by remember { mutableStateOf(EquipmentSlot.HAT) }
    val catalogById = remember(allWardrobeItems) { allWardrobeItems.associateBy { it.id } }
    val rarityCounts = remember(allWardrobeItems, unlockedItemIds) {
        rarityCollectionCounts(allWardrobeItems, unlockedItemIds)
    }

    val unlockedItemsForSlot = remember(allWardrobeItems, unlockedItemIds, selectedSlot) {
        allWardrobeItems
            .filter { it.slot == selectedSlot && unlockedItemIds.contains(it.id) }
            .sortedWith(
                compareByDescending<WardrobeItem> { it.rarity.ordinal }
                    .thenBy { it.styleIndex }
                    .thenBy { it.id }
            )
    }

    val tintChoices = listOf(0xFFF5A623L, 0xFF3498DBL, 0xFF2ECC71L, 0xFF9B59B6L, 0xFFE74C3CL)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
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

        Spacer(Modifier.height(12.dp))
        RarityCollectionRow(rarityCounts)

        Spacer(Modifier.height(16.dp))

        // 3D Avatar Viewport Stage
        LixoCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            cornerRadius = 24.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            Avatar3DViewport(
                appearance = profile.appearance,
                catalogById = catalogById,
                modifier = Modifier.fillMaxSize(),
                interactiveRotation = true
            )
        }

        Spacer(Modifier.height(14.dp))

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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tintChoices.forEach { hex ->
                    val isSelected = profile.appearance.primaryTintHex == hex
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
                                    (profile.appearance.eyeExpressionIndex + 1) % 2,
                                    profile.appearance.smileExpressionIndex
                                )
                            }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 6 Avatars Character Picker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AvatarType.entries.forEach { type ->
                val isUnlocked = profile.unlockedAvatars.contains(type)
                val isActive = profile.appearance.avatarType == type
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
                            onSelectAvatarType(type)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isUnlocked) {
                            LixoIcon(icon = LixoIcons.Lock, tint = LixoColors.TextMuted, size = 16.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isUnlocked) LixoStrings.avatarName(type)
                            else "${LixoStrings.avatarName(type)} (${type.unlockStarsRequired}⭐)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (isActive) LixoColors.Blue else if (isUnlocked) LixoColors.TextPrimary else LixoColors.TextMuted
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

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

        Spacer(Modifier.height(14.dp))

        // Unlocked Item Cards
        if (unlockedItemsForSlot.isEmpty()) {
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
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
            val displayItems: List<WardrobeItem?> = remember(unlockedItemsForSlot) {
                listOf(null) + unlockedItemsForSlot
            }
            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(420.dp)
            ) {
                items(displayItems, key = { it?.id ?: "__none__" }) { item ->
                    if (item == null) {
                        // "Ingen" slot-empty card.
                        val isSlotEmpty = !profile.appearance.equippedItems.containsKey(selectedSlot)
                        LixoCard(
                            modifier = Modifier.width(104.dp),
                            selected = isSlotEmpty,
                            cornerRadius = 16.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
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
                        val isEquipped = profile.appearance.equippedItems[selectedSlot] == item.id
                        val rarityColor = LixoColors.rarityColor(item.rarity)

                        LixoCard(
                            modifier = Modifier.width(104.dp),
                            selected = isEquipped,
                            borderColor = if (isEquipped) LixoColors.Blue else rarityColor,
                            backgroundColor = if (isEquipped) LixoColors.BlueTint else rarityColor.copy(alpha = 0.10f),
                            cornerRadius = 16.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
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
                                    WardrobeItemPreview(item = item, modifier = Modifier.size(64.dp))
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
                                    text = item.nameSv,
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
