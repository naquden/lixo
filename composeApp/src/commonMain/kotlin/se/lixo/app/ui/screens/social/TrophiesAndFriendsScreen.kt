package se.lixo.app.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.lixo.app.domain.model.Achievement
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.SegmentedTabs
import se.lixo.app.ui.theme.StatPill

/**
 * Duolingo-styled Trophies & Friends Screen:
 * - Equal-width segmented tabs for switching between "Troféer" and "Kompisar".
 * - Clean achievement rows with rarity badges and progress meters.
 * - Anonymous friend gallery with 3D avatars.
 */
@Composable
fun TrophiesAndFriendsScreen(
    activeProfile: ChildProfile,
    achievements: List<Achievement>,
    friends: List<FriendProfile>,
    catalogById: Map<String, WardrobeItem>,
    onAddFriendByQuery: suspend (String) -> Result<FriendProfile>,
    onRemoveFriend: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var friendQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val myQrPayload = remember(activeProfile) {
        val uname = activeProfile.publicUsername ?: activeProfile.displayName.lowercase()
        "lixo://friend/$uname:${activeProfile.appearance.avatarType.id}:${activeProfile.totalStars}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Segmented Tabs
        SegmentedTabs(
            tabs = listOf(LixoStrings.TAB_TROPHIES, LixoStrings.TAB_FRIENDS),
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it }
        )

        Spacer(Modifier.height(20.dp))

        if (selectedTab == 0) {
            // Trophies / Achievements
            Text(
                text = LixoStrings.TROPHIES_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                achievements.forEach { ach ->
                    val frac = (ach.currentCount.toFloat() / ach.targetCount.toFloat()).coerceIn(0f, 1f)
                    val rarityColor = LixoColors.rarityColor(ach.rarity)

                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(rarityColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LixoIcon(
                                            icon = LixoIcons.Trophy,
                                            tint = rarityColor,
                                            size = 22.dp
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = ach.titleSv,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = LixoColors.TextPrimary
                                        )
                                        Text(
                                            text = ach.descriptionSv,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = LixoColors.TextSecondary
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(rarityColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = LixoStrings.rarityName(ach.rarity).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = rarityColor
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LixoProgressBar(
                                    progress = frac,
                                    fillColor = rarityColor,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "${ach.currentCount}/${ach.targetCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LixoColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Friends Tab
            Text(
                text = LixoStrings.FRIENDS_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )
            Spacer(Modifier.height(14.dp))

            // My friend code card
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = LixoColors.BlueTint,
                borderColor = LixoColors.BlueBorder,
                cornerRadius = 16.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LixoIcon(icon = LixoIcons.Person, tint = LixoColors.Blue, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${LixoStrings.FRIENDS_MY_CODE_PREFIX} @${activeProfile.publicUsername ?: activeProfile.displayName.lowercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.Blue
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Add friend field & button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = friendQuery,
                    onValueChange = { friendQuery = it },
                    placeholder = { Text(LixoStrings.FRIENDS_INPUT_PLACEHOLDER) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LixoColors.Blue,
                        unfocusedBorderColor = LixoColors.Border
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                LixoButton(
                    onClick = {
                        if (friendQuery.isNotBlank()) {
                            val q = friendQuery
                            friendQuery = ""
                            scope.launch { onAddFriendByQuery(q) }
                        }
                    },
                    variant = LixoButtonVariant.PRIMARY,
                    height = 54.dp
                ) {
                    Text(
                        text = LixoStrings.FRIENDS_ADD_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Friend 3D Avatar Gallery
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                friends.forEach { friend ->
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(LixoColors.Surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Avatar3DViewport(
                                    appearance = friend.appearance,
                                    catalogById = catalogById,
                                    modifier = Modifier.size(75.dp),
                                    interactiveRotation = true
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "@${friend.username}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LixoColors.TextPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                StatPill(
                                    icon = LixoIcons.Star,
                                    value = "${friend.totalStars}",
                                    tint = LixoColors.GoldLip
                                )
                            }
                            LixoButton(
                                onClick = { onRemoveFriend(friend.userId) },
                                variant = LixoButtonVariant.GHOST,
                                height = 36.dp
                            ) {
                                Text(
                                    text = LixoStrings.FRIENDS_REMOVE_BTN,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LixoColors.TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
