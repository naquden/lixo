package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings

private val popInKeyframes = keyframes<Float> {
    durationMillis = 350
    1.3f at 200
    1f at 350
}

@Composable
fun ItemUnlockedDialog(
    item: WardrobeItem,
    onEquipNow: () -> Unit,
    onClose: () -> Unit
) {
    val haptics = rememberLixoHaptics()
    var equipped by remember { mutableStateOf(false) }
    val confirmScale = remember { Animatable(0f) }

    LaunchedEffect(equipped) {
        if (equipped) {
            haptics.success(SfxCue.EQUIP_CONFIRM)
            confirmScale.animateTo(1f, popInKeyframes)
            delay(900)
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CelebrationScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        LixoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            cornerRadius = 24.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = LixoStrings.ITEM_UNLOCKED_TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.GreenLip
                )
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    LightBurst(item.rarity, Modifier.fillMaxSize())
                    RarityItemTile(item, Modifier.align(Alignment.Center), tileSize = 110.dp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = item.nameSv,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = LixoColors.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(LixoColors.rarityColor(item.rarity))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${LixoStrings.rarityName(item.rarity).uppercase()} · ${LixoStrings.slotName(item.slot)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(20.dp))

                if (!equipped) {
                    LixoButton(
                        onClick = {
                            if (!equipped) {
                                equipped = true
                                onEquipNow()
                            }
                        },
                        variant = LixoButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.EQUIP_NOW_BTN.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LixoButton(
                        onClick = onClose,
                        variant = LixoButtonVariant.GHOST,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = LixoStrings.SHEET_CLOSE_BTN,
                            style = MaterialTheme.typography.labelLarge,
                            color = LixoColors.TextSecondary
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.graphicsLayer {
                            scaleX = confirmScale.value
                            scaleY = confirmScale.value
                        }
                    ) {
                        LixoIcon(icon = LixoIcons.Check, tint = LixoColors.GreenLip, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = LixoStrings.EQUIPPED_CONFIRM,
                            style = MaterialTheme.typography.headlineMedium,
                            color = LixoColors.GreenLip
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarUnlockedDialog(
    avatarType: AvatarType,
    onSwitchNow: () -> Unit,
    onClose: () -> Unit
) {
    val haptics = rememberLixoHaptics()
    val iconScale = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        haptics.heavy(SfxCue.ITEM_REVEAL)
        iconScale.animateTo(1f, popInKeyframes)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CelebrationScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        LixoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            cornerRadius = 24.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = LixoStrings.AVATAR_UNLOCKED_TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.GreenLip
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .graphicsLayer {
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        }
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(LixoColors.Gold, LixoColors.Orange))),
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(icon = LixoIcons.Person, tint = Color.White, size = 56.dp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = LixoStrings.avatarName(avatarType),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.TextPrimary
                )
                Spacer(Modifier.height(20.dp))

                LixoButton(
                    onClick = {
                        haptics.success(SfxCue.AVATAR_SWITCH)
                        onSwitchNow()
                        onClose()
                    },
                    variant = LixoButtonVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.SWITCH_AVATAR_NOW_BTN.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(10.dp))
                LixoButton(
                    onClick = onClose,
                    variant = LixoButtonVariant.GHOST,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.SHEET_CLOSE_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = LixoColors.TextSecondary
                    )
                }
            }
        }
    }
}
