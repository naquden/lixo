package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.components.avatar3d.WardrobeItemPreview
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

/** Rarity-tinted tile with a live preview of the item's shape, used both in the reveal and the unlock dialog. */
@Composable
fun RarityItemTile(
    item: WardrobeItem,
    modifier: Modifier = Modifier,
    tileSize: Dp = 132.dp
) {
    Box(
        modifier = modifier
            .size(tileSize)
            .then(
                if (item.rarity == Rarity.LEGENDARY) {
                    Modifier.drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(LixoColors.Gold.copy(alpha = 0.6f), Color.Transparent)
                            ),
                            radius = size.minDimension * 0.75f
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(24.dp))
            .background(LixoColors.rarityBrush(item.rarity))
            .border(3.dp, Color.White, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        WardrobeItemPreview(item = item, modifier = Modifier.size(tileSize * 0.72f))
    }
}

/** Item that springs out of the opened chest with an overshoot scale and a small rise. */
@Composable
fun ItemRevealTile(item: WardrobeItem, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val riseStartPx = remember(density) { with(density) { 60.dp.toPx() } }
    val scale = remember(item) { Animatable(0f) }
    val rise = remember(item) { Animatable(riseStartPx) }

    LaunchedEffect(item) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 450
                    1.25f at 280
                    1f at 450
                }
            )
        }
        rise.animateTo(0f, spring(dampingRatio = 0.5f))
    }

    RarityItemTile(
        item = item,
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            translationY = rise.value
        }
    )
}

/** Name + rarity pill shown below the opened chest, so it never overlaps the chest body. */
@Composable
fun ItemRevealDetails(item: WardrobeItem, modifier: Modifier = Modifier) {
    val appear = remember(item) { Animatable(0f) }
    LaunchedEffect(item) { appear.animateTo(1f, spring(dampingRatio = 0.55f)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer {
            alpha = appear.value.coerceIn(0f, 1f)
            scaleX = 0.8f + 0.2f * appear.value
            scaleY = 0.8f + 0.2f * appear.value
        }
    ) {
        Text(
            text = item.nameSv,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(LixoColors.rarityColor(item.rarity))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${LixoStrings.rarityName(item.rarity).uppercase()} · ${LixoStrings.slotName(item.slot)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}
