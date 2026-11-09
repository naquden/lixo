package se.lixo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentEvent
import se.lixo.app.domain.content.parseContentHex
import se.lixo.app.domain.engine.EventEngine
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoBottomSheet
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings

/** Test tag on the clickable [EventBanner] row, for instrumented tests (025). */
const val EVENT_BANNER_TAG = "event_banner"

/**
 * A map/home-screen banner for an active holiday special (025): `event.banner`'s title on a
 * coloured background. Renders nothing when [event] has no banner.
 */
@Composable
fun EventBanner(
    content: ActiveContent,
    event: ContentEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val banner = event.banner ?: return
    val bg = Color(parseContentHex(banner.bgHex) ?: 0xFFB71C1CL)
    val fg = Color(parseContentHex(banner.fgHex) ?: 0xFFFFFFFFL)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(EVENT_BANNER_TAG)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        LixoIcon(icon = LixoIcons.Gift, tint = fg, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = content.string(banner.titleKey),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = fg
        )
    }
}

/**
 * A bottom sheet listing [event]'s event-pool items (025), each marked owned/unowned against
 * [unlockedItemIds]. Opened by tapping [EventBanner].
 */
@Composable
fun EventItemsSheet(
    visible: Boolean,
    content: ActiveContent,
    event: ContentEvent,
    unlockedItemIds: Set<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LixoBottomSheet(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Text(
            text = content.string(event.nameKey),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = LixoColors.TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = LixoStrings.EVENT_SHEET_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = LixoColors.TextSecondary
        )
        Spacer(Modifier.height(16.dp))

        val items = EventEngine.eventItems(content, event)
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { item ->
                    val owned = item.id in unlockedItemIds
                    Column(
                        modifier = Modifier.width(96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LixoColors.rarityBrush(item.rarity))
                        ) {
                            ItemPreview(
                                content = content,
                                item = item,
                                modifier = Modifier.size(64.dp).align(Alignment.Center)
                            )
                            if (owned) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(LixoColors.Green)
                                        .semantics { contentDescription = LixoStrings.EVENT_ITEM_OWNED },
                                    contentAlignment = Alignment.Center
                                ) {
                                    LixoIcon(icon = LixoIcons.Check, tint = Color.White, size = 16.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = content.itemName(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = LixoColors.TextPrimary,
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
