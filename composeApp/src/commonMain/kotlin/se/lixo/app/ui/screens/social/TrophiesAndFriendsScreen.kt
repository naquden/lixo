package se.lixo.app.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.ParticleProgressBar

/**
 * Trophies & Friend Gallery Screen (`project.md` §2 "Sociala funktioner" & "Epic/Legendary via prestationer"):
 * - Trophies tab: Shows progress toward Epic/Legendary achievements (speed, pronunciation in AI mode, weekly streaks).
 * - Friends tab: Add/remove friends by QR code or anonymous username. Shows friends' 3D avatars.
 *   No real names, no chats or DMs.
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
            .background(LixoColors.SkyBackgroundBottom)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Tab Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BouncyButton(
                onClick = { selectedTab = 0 },
                containerColor = if (selectedTab == 0) LixoColors.PrimaryActionGreen else LixoColors.CardSurfaceElevated,
                modifier = Modifier.weight(1f)
            ) {
                Text("🏆 ${LixoStrings.TAB_TROPHIES}", color = Color.White)
            }
            BouncyButton(
                onClick = { selectedTab = 1 },
                containerColor = if (selectedTab == 1) LixoColors.SecondaryBlue else LixoColors.CardSurfaceElevated,
                modifier = Modifier.weight(1f)
            ) {
                Text("🤝 ${LixoStrings.TAB_FRIENDS}", color = Color.White)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (selectedTab == 0) {
            Text(
                text = LixoStrings.TROPHIES_SUBTITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = LixoColors.MutedText
            )
            Spacer(Modifier.height(12.dp))

            achievements.forEach { ach ->
                val frac = (ach.currentCount.toFloat() / ach.targetCount.toFloat()).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.CardSurface)
                        .border(2.dp, Color(ach.rarity.hexColorPrimary), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ach.titleSv,
                            style = MaterialTheme.typography.titleMedium,
                            color = LixoColors.SoftTextWhite
                        )
                        Text(
                            text = LixoStrings.rarityName(ach.rarity).uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(ach.rarity.hexColorPrimary)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ach.descriptionSv,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.MutedText
                    )
                    Spacer(Modifier.height(10.dp))
                    ParticleProgressBar(progress = frac)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${ach.currentCount} / ${ach.targetCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.StarGold
                    )
                }
            }
        } else {
            Text(
                text = LixoStrings.FRIENDS_SUBTITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = LixoColors.MutedText
            )
            Spacer(Modifier.height(10.dp))

            // Shareable QR / Username Code Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LixoColors.CardSurface)
                    .padding(14.dp)
            ) {
                Text(
                    text = "📲 ${LixoStrings.FRIENDS_MY_CODE_PREFIX} $myQrPayload",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LixoColors.StarGold
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = friendQuery,
                    onValueChange = { friendQuery = it },
                    label = { Text(LixoStrings.FRIENDS_INPUT_PLACEHOLDER) },
                    modifier = Modifier.weight(1f)
                )
                BouncyButton(
                    onClick = {
                        if (friendQuery.isNotBlank()) {
                            val q = friendQuery
                            friendQuery = ""
                            scope.launch { onAddFriendByQuery(q) }
                        }
                    }
                ) {
                    Text(LixoStrings.FRIENDS_ADD_BTN, color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Friend 3D Avatar Gallery (Zero chat/DM)
            friends.forEach { friend ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(LixoColors.CardSurface)
                        .border(1.5.dp, Color(0xFF334155), RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar3DViewport(
                        appearance = friend.appearance,
                        catalogById = catalogById,
                        modifier = Modifier.size(110.dp),
                        interactiveRotation = true
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "@${friend.username}",
                            style = MaterialTheme.typography.titleMedium,
                            color = LixoColors.SoftTextWhite
                        )
                        Text(
                            text = "⭐ ${friend.totalStars} ${LixoStrings.MAP_STARS_LABEL}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.StarGold
                        )
                    }
                    BouncyButton(
                        onClick = { onRemoveFriend(friend.userId) },
                        containerColor = LixoColors.CardSurfaceElevated,
                        lipColor = LixoColors.CardSurface
                    ) {
                        Text(LixoStrings.FRIENDS_REMOVE_BTN, color = LixoColors.SoftTextWhite)
                    }
                }
            }
        }
    }
}
