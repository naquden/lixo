package se.lixo.app.ui.screens.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.lixo.app.data.content.BundledContentPack
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.AvatarAppearance
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.avatar.ItemPreview
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

/** Test tag for [ContentPreviewScreen]'s item grid. */
const val CONTENT_PREVIEW_GRID_TAG = "content_preview_grid"

/** Test tag for [ContentPreviewScreen]'s avatar viewport. */
const val CONTENT_PREVIEW_VIEWPORT_TAG = "content_preview_viewport"

/** Test tag for [ContentPreviewScreen]'s [sourceLabel] text. */
const val CONTENT_PREVIEW_SOURCE_TAG = "content_preview_source"

/**
 * Debug screen (020/021) that renders [content] directly: an avatar selector, a big
 * [AvatarViewport], and a grid of every item (including retired ones) that equips/unequips on
 * tap. Reachable only from a debug-build-only button on `AvatarMapScreen`. [sourceLabel], when
 * given, is shown under the title (e.g. "förhandsfil v1", "inbyggt paket v1", "testpaket") --
 * see [ContentPreviewRoute], which loads [content] via [BundledContentPack.loadForPreview].
 */
@Composable
fun ContentPreviewScreen(
    content: ActiveContent,
    modifier: Modifier = Modifier,
    sourceLabel: String? = null,
    onBack: (() -> Unit)? = null
) {
    var avatarId by remember(content) { mutableStateOf(content.defaultAvatar.id) }
    var equipped by remember(content) { mutableStateOf(emptyMap<EquipmentSlot, String>()) }
    val appearance = AvatarAppearance(avatarId = avatarId, equipped = equipped)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onBack != null) {
                LixoButton(onClick = onBack, variant = LixoButtonVariant.GHOST, height = 40.dp) {
                    Text(LixoStrings.CONTENT_PREVIEW_BACK_BTN)
                }
            }
            Text(
                text = "${LixoStrings.CONTENT_PREVIEW_TITLE} v${content.version}",
                style = MaterialTheme.typography.titleMedium,
                color = LixoColors.TextPrimary
            )
        }

        if (sourceLabel != null) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = LixoColors.TextPrimary,
                modifier = Modifier.testTag(CONTENT_PREVIEW_SOURCE_TAG)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (a in content.avatarsOrdered) {
                val selected = a.id == avatarId
                LixoButton(
                    onClick = { avatarId = a.id },
                    variant = if (selected) LixoButtonVariant.PRIMARY else LixoButtonVariant.OUTLINE,
                    height = 40.dp
                ) {
                    Text(content.string(a.nameKey))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AvatarViewport(
            content = content,
            appearance = appearance,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag(CONTENT_PREVIEW_VIEWPORT_TAG)
        )

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag(CONTENT_PREVIEW_GRID_TAG)
        ) {
            items(content.pack.items, key = { it.id }) { item ->
                val on = equipped[item.slot] == item.id
                Column(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (on) 3.dp else 1.dp,
                            color = if (on) LixoColors.Blue else LixoColors.Border,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            equipped = if (on) equipped - item.slot else equipped + (item.slot to item.id)
                        }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ItemPreview(content = content, item = item, modifier = Modifier.size(72.dp))
                    Text(
                        text = content.string(item.nameKey),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = LixoColors.TextPrimary
                    )
                }
            }
        }
    }
}

/**
 * Loads [content] via [BundledContentPack.loadForPreview] (021: preview file -> bundled pack ->
 * [se.lixo.app.domain.content.SampleContentPack] fallback) and renders [ContentPreviewScreen]
 * once loaded, showing [LixoStrings.CONTENT_PREVIEW_LOADING] in the meantime.
 */
@Composable
fun ContentPreviewRoute(onBack: () -> Unit) {
    val loaded by produceState<Pair<ActiveContent, String>?>(null) {
        value = BundledContentPack.loadForPreview()
    }
    val current = loaded
    if (current == null) {
        Text(
            text = LixoStrings.CONTENT_PREVIEW_LOADING,
            style = MaterialTheme.typography.bodyMedium,
            color = LixoColors.TextPrimary,
            modifier = Modifier.padding(16.dp)
        )
    } else {
        val (content, label) = current
        ContentPreviewScreen(content = content, sourceLabel = label, onBack = onBack)
    }
}
