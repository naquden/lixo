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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Universal 3D Wardrobe Screen (`project.md` §2 "Avatarsystem & Anpassning"):
 * - 6 MVP Avatars: Katt, Hund, Riddare, Prinsessa, Trollkarl, Häxa.
 * - 9 Equipment Slots: Hatt, Axlar/rygg, Armar/handskar, Rustning/tröja, Byxor, Skor,
 *   Handhållet höger, Handhållet vänster, Svans.
 * - Universal Wardrobe: All unlocked items are in a shared pool usable by any avatar.
 * - Mystery Rule: "Ej upplåsta föremål visas inte (mysterium)" — only unlocked items are shown!
 * - Fortnite 2D Rarity background color scale (Gray, Green, Blue, Purple, Gold-Orange).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WardrobeScreen(
    profile: ChildProfile,
    allWardrobeItems: List<WardrobeItem>,
    unlockedItemIds: Set<String>,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onSelectAvatarType: (AvatarType) -> Unit,
    onEquipSlotItem: (EquipmentSlot, String?) -> Unit,
    onCustomizeBaseTraits: (Long, Int, Int) -> Unit
) {
    var selectedSlot by remember { mutableStateOf(EquipmentSlot.HAT) }
    val catalogById = remember(allWardrobeItems) { allWardrobeItems.associateBy { it.id } }

    // Filter strictly to unlocked items for the selected slot ("Ej upplåsta föremål visas inte (mysterium)")
    val unlockedItemsForSlot = remember(allWardrobeItems, unlockedItemIds, selectedSlot) {
        allWardrobeItems.filter { it.slot == selectedSlot && unlockedItemIds.contains(it.id) }
    }

    val tintChoices = listOf(0xFFF5A623L, 0xFF3498DBL, 0xFF2ECC71L, 0xFF9B59B6L, 0xFFE74C3CL)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.SkyBackgroundBottom)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LixoStrings.WARDROBE_TITLE,
                style = MaterialTheme.typography.headlineMedium,
                color = LixoColors.SoftTextWhite
            )
            if (showSwedishTtsButtons) {
                Spacer(Modifier.width(10.dp))
                TtsIconButton(
                    textToSpeak = "${LixoStrings.WARDROBE_TITLE}. ${LixoStrings.WARDROBE_SUBTITLE}",
                    ttsEngine = ttsEngine
                )
            }
        }
        Text(
            text = LixoStrings.WARDROBE_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = LixoColors.MutedText
        )

        Spacer(Modifier.height(10.dp))

        // Interactive 3D Avatar Stage (rotatable by horizontal drag)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(235.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(LixoColors.CardSurface)
                .border(2.dp, Color(0xFF334155), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Avatar3DViewport(
                appearance = profile.appearance,
                catalogById = catalogById,
                modifier = Modifier.fillMaxSize(),
                interactiveRotation = true
            )
        }

        Spacer(Modifier.height(12.dp))

        // Base Trait & Expression Customization Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = LixoStrings.WARDROBE_TRAITS_COLOR_LABEL,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.SoftTextWhite
            )
            tintChoices.forEach { hex ->
                val isSelected = profile.appearance.primaryTintHex == hex
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(hex))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else Color.Transparent,
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

        Spacer(Modifier.height(14.dp))

        // 6 MVP Avatars Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AvatarType.entries.forEach { type ->
                val isUnlocked = profile.unlockedAvatars.contains(type)
                val isActive = profile.appearance.avatarType == type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            when {
                                isActive -> LixoColors.PrimaryActionGreen
                                isUnlocked -> LixoColors.CardSurface
                                else -> Color(0xFF162032)
                            }
                        )
                        .border(
                            width = if (isActive) 2.dp else 1.dp,
                            color = if (isActive) LixoColors.StarGold else Color(0xFF334155),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = isUnlocked) {
                            onSelectAvatarType(type)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (isUnlocked) LixoStrings.avatarName(type)
                        else "🔒 ${LixoStrings.avatarName(type)} (${type.unlockStarsRequired}⭐)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isUnlocked) Color.White else LixoColors.MutedText
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 9 Equipment Slots Selector
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) LixoColors.SecondaryBlue else LixoColors.CardSurfaceElevated)
                        .clickable { selectedSlot = slot }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = LixoStrings.slotName(slot),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Equipped item unequip button
        if (profile.appearance.equippedItems.containsKey(selectedSlot)) {
            BouncyButton(
                onClick = { onEquipSlotItem(selectedSlot, null) },
                containerColor = LixoColors.CardSurfaceElevated,
                lipColor = LixoColors.CardSurface
            ) {
                Text(LixoStrings.WARDROBE_UNEQUIP_BTN, color = LixoColors.SoftTextWhite)
            }
            Spacer(Modifier.height(10.dp))
        }

        // Unlocked Item Cards styled with Fortnite 2D Rarity Colors
        if (unlockedItemsForSlot.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(LixoColors.CardSurface)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✨ ${LixoStrings.WARDROBE_MYSTERY_FOOTER}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.MutedText
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                unlockedItemsForSlot.forEach { item ->
                    val isEquipped = profile.appearance.equippedItems[selectedSlot] == item.id
                    Column(
                        modifier = Modifier
                            .width(155.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(LixoColors.rarityBrush(item.rarity))
                            .border(
                                width = if (isEquipped) 3.dp else 1.dp,
                                color = if (isEquipped) Color.White else Color.White.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                onEquipSlotItem(selectedSlot, item.id)
                            }
                            .padding(14.dp)
                    ) {
                        Text(
                            text = LixoStrings.rarityName(item.rarity).uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.nameSv,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isEquipped) "✓ Utrustad" else "Tryck för att bära",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
