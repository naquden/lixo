package se.lixo.app.ui.screens.social

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.WardrobeItem
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar3d.Avatar3DViewport
import se.lixo.app.ui.theme.LixoBottomSheet
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.StatPill
import se.lixo.app.ui.theme.TtsIconButton

/**
 * "Kompisbyn" (the friend village): a Duolingo-style scene where the child's own avatar stands
 * on a podium in the middle and friends are laid out on grass plots in a winding, zig-zag path
 * below it. Replaces the old friends tab that used to live inside the Trophies screen.
 */
@Composable
fun AvatarMapScreen(
    profile: ChildProfile,
    friends: List<FriendProfile>,
    catalogById: Map<String, WardrobeItem>,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onOpenWardrobe: () -> Unit,
    onAddFriendByQuery: suspend (String) -> Result<FriendProfile>,
    onRemoveFriend: (String) -> Unit
) {
    var selectedFriend by remember { mutableStateOf<FriendProfile?>(null) }
    var showAddFriendSheet by remember { mutableStateOf(false) }
    var friendQuery by remember { mutableStateOf("") }
    var addFriendError by remember { mutableStateOf<String?>(null) }
    var isAddingFriend by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val myUsername = remember(profile) { profile.publicUsername ?: profile.displayName.lowercase() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = LixoStrings.AVATAR_MAP_TITLE,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )
                Text(
                    text = LixoStrings.AVATAR_MAP_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LixoColors.TextSecondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSwedishTtsButtons) {
                    TtsIconButton(
                        textToSpeak = "${LixoStrings.AVATAR_MAP_TITLE}. ${LixoStrings.AVATAR_MAP_SUBTITLE}",
                        ttsEngine = ttsEngine
                    )
                    Spacer(Modifier.width(8.dp))
                }
                StatPill(icon = LixoIcons.Star, value = "${profile.totalStars}", tint = LixoColors.GoldLip)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Village scene card
        LixoCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            contentPadding = PaddingValues(0.dp)
        ) {
            VillageBackdrop(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(20.dp))

                    // Own avatar podium, centered.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OwnAvatarPodium(
                            profile = profile,
                            catalogById = catalogById,
                            onClick = onOpenWardrobe
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LixoButton(
                            onClick = onOpenWardrobe,
                            variant = LixoButtonVariant.GOLD,
                            height = 48.dp
                        ) {
                            LixoIcon(icon = LixoIcons.Shirt, tint = Color(0xFF4A3800), size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = LixoStrings.AVATAR_MAP_DRESS_UP_BTN,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF4A3800)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    if (friends.isEmpty()) {
                        Text(
                            text = LixoStrings.AVATAR_MAP_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Zig-zag winding path of friend plots, ending with the "+" add-friend plot.
                    // Real two-column stagger: even slots (0, 2, 4, ...) go in the left column,
                    // odd slots (1, 3, 5, ...) go in the right column, pushed down by roughly half
                    // a plot's height so the two columns read as a winding path, not two straight rows.
                    val totalPlots = friends.size + 1
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            for (index in 0 until totalPlots step 2) {
                                if (index < friends.size) {
                                    val friend = friends[index]
                                    FriendPlot(
                                        friend = friend,
                                        catalogById = catalogById,
                                        onClick = { selectedFriend = friend }
                                    )
                                } else {
                                    AddFriendPlot(onClick = { showAddFriendSheet = true })
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(top = 85.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            for (index in 1 until totalPlots step 2) {
                                if (index < friends.size) {
                                    val friend = friends[index]
                                    FriendPlot(
                                        friend = friend,
                                        catalogById = catalogById,
                                        onClick = { selectedFriend = friend }
                                    )
                                } else {
                                    AddFriendPlot(onClick = { showAddFriendSheet = true })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    // Friend detail sheet
    LixoBottomSheet(
        visible = selectedFriend != null,
        onDismiss = { selectedFriend = null }
    ) {
        selectedFriend?.let { friend ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.Surface),
                    contentAlignment = Alignment.Center
                ) {
                    Avatar3DViewport(
                        appearance = friend.appearance,
                        catalogById = catalogById,
                        modifier = Modifier.fillMaxSize(),
                        interactiveRotation = true
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "@${friend.username}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatPill(icon = LixoIcons.Star, value = "${friend.totalStars}", tint = LixoColors.GoldLip)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = LixoStrings.avatarName(friend.appearance.avatarType),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.TextSecondary
                    )
                }
                Spacer(Modifier.height(20.dp))
                LixoButton(
                    onClick = {
                        onRemoveFriend(friend.userId)
                        selectedFriend = null
                    },
                    variant = LixoButtonVariant.GHOST,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.FRIEND_SHEET_REMOVE_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = LixoColors.Red
                    )
                }
                Spacer(Modifier.height(8.dp))
                LixoButton(
                    onClick = { selectedFriend = null },
                    variant = LixoButtonVariant.OUTLINE,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.SHEET_CLOSE_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = LixoColors.Blue
                    )
                }
            }
        }
    }

    // Add-friend sheet
    LixoBottomSheet(
        visible = showAddFriendSheet,
        onDismiss = {
            showAddFriendSheet = false
            addFriendError = null
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LixoCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = LixoColors.BlueTint,
                borderColor = LixoColors.BlueBorder,
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LixoIcon(icon = LixoIcons.Person, tint = LixoColors.Blue, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${LixoStrings.FRIENDS_MY_CODE_PREFIX} @$myUsername",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.Blue
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = LixoStrings.FRIENDS_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = friendQuery,
                    onValueChange = {
                        friendQuery = it
                        addFriendError = null
                    },
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
                        val q = friendQuery
                        if (q.isNotBlank() && !isAddingFriend) {
                            isAddingFriend = true
                            scope.launch {
                                val result = onAddFriendByQuery(q)
                                isAddingFriend = false
                                if (result.isSuccess) {
                                    friendQuery = ""
                                    addFriendError = null
                                    showAddFriendSheet = false
                                } else {
                                    val reason = (result.exceptionOrNull() as? AddFriendException)?.reason
                                    addFriendError = when (reason) {
                                        AddFriendError.NOT_FOUND -> LixoStrings.FRIENDS_ERROR_NOT_FOUND
                                        AddFriendError.ALREADY_FRIEND -> LixoStrings.FRIENDS_ERROR_ALREADY
                                        AddFriendError.NETWORK, null -> LixoStrings.FRIENDS_ERROR_NETWORK
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isAddingFriend,
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

            addFriendError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.Red,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showSwedishTtsButtons) {
                        Spacer(Modifier.width(8.dp))
                        TtsIconButton(textToSpeak = error, ttsEngine = ttsEngine)
                    }
                }
            }
        }
    }
}

/**
 * Soft sky-to-meadow backdrop covering the whole scene card: no image assets, just a
 * gradient that transitions from sky to meadow at a fixed absolute offset from the top
 * (not a fraction of the total, possibly-scrolling, height), low-alpha hill ovals and
 * grass tufts, a sun glow and a couple of clouds, with [content] laid over the top.
 */
@Composable
private fun VillageBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val transitionY = 320.dp.toPx().coerceAtMost(h)

            // Sky-to-meadow gradient down to the fixed transition point, then flat meadow tint.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(LixoColors.BlueTint, LixoColors.GreenTint),
                    startY = 0f,
                    endY = transitionY
                ),
                size = Size(w, transitionY)
            )
            if (h > transitionY) {
                drawRect(
                    color = LixoColors.GreenTint,
                    topLeft = Offset(0f, transitionY),
                    size = Size(w, h - transitionY)
                )
            }

            // Sun glow, near the top of the sky.
            drawCircle(
                color = LixoColors.GoldTint.copy(alpha = 0.6f),
                radius = w * 0.18f,
                center = Offset(w * 0.82f, 60.dp.toPx())
            )

            // Rolling hills, anchored around the sky/meadow transition just below the own avatar.
            val hillBaseY = 300.dp.toPx()
            val hill1 = Path().apply {
                moveTo(0f, hillBaseY - 40.dp.toPx())
                quadraticBezierTo(w * 0.25f, hillBaseY - 70.dp.toPx(), w * 0.55f, hillBaseY - 30.dp.toPx())
                quadraticBezierTo(w * 0.8f, hillBaseY, w, hillBaseY - 50.dp.toPx())
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path = hill1, color = LixoColors.Green.copy(alpha = 0.18f))

            val hill2 = Path().apply {
                moveTo(0f, hillBaseY + 10.dp.toPx())
                quadraticBezierTo(w * 0.3f, hillBaseY - 20.dp.toPx(), w * 0.6f, hillBaseY + 15.dp.toPx())
                quadraticBezierTo(w * 0.85f, hillBaseY + 40.dp.toPx(), w, hillBaseY + 5.dp.toPx())
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path = hill2, color = LixoColors.Green.copy(alpha = 0.28f))

            // Clouds, near the top.
            fun drawCloud(cx: Float, cy: Float, s: Float) {
                drawCircle(Color.White.copy(alpha = 0.85f), radius = s, center = Offset(cx - s * 0.6f, cy))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = s * 1.1f, center = Offset(cx, cy - s * 0.3f))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = s, center = Offset(cx + s * 0.7f, cy))
            }
            drawCloud(w * 0.2f, 50.dp.toPx(), w * 0.045f)
            drawCloud(w * 0.65f, 90.dp.toPx(), w * 0.03f)

            // Deterministic (seeded, not Random-at-draw-time) low-alpha grass tufts sprinkled
            // across the lower meadow for texture.
            if (h > transitionY) {
                val tuftColor = LixoColors.Green.copy(alpha = 0.22f)
                val tuftWidth = 14.dp.toPx()
                val tuftHeight = 6.dp.toPx()
                val seededOffsets = listOf(
                    0.08f to 0.05f, 0.22f to 0.18f, 0.37f to 0.08f, 0.52f to 0.22f,
                    0.68f to 0.06f, 0.81f to 0.16f, 0.93f to 0.09f, 0.15f to 0.32f,
                    0.44f to 0.30f, 0.60f to 0.34f, 0.77f to 0.28f, 0.90f to 0.36f,
                    0.05f to 0.42f, 0.30f to 0.44f, 0.70f to 0.46f
                )
                for ((fx, fy) in seededOffsets) {
                    val tx = fx * w
                    val ty = transitionY + fy * (h - transitionY)
                    drawOval(
                        color = tuftColor,
                        topLeft = Offset(tx - tuftWidth / 2f, ty - tuftHeight / 2f),
                        size = Size(tuftWidth, tuftHeight)
                    )
                }
            }
        }
        content()
    }
}

/**
 * Draws a podium "lip" + "face" oval pair directly under an avatar's feet so the avatar
 * reads as standing on a tactile base, matching [Avatar3DViewport]'s own shadow/ring geometry
 * (drawn in `Avatar3DController.kt`'s `drawLowPolyAvatarScene`: feet sit at y = 0.842 * side,
 * ring centered around y = 0.88 * side). Drawn with `drawBehind` so it renders under the
 * avatar's own shadow/ring rather than as a separate floating pill.
 */
private fun Modifier.avatarGround(face: Color, lip: Color): Modifier = this.drawBehind {
    val s = size.minDimension
    val centerX = size.width / 2f
    val groundWidth = 0.78f * s
    val groundHeight = 0.20f * s
    val faceCenterY = 0.89f * s
    val lipCenterY = faceCenterY + 0.025f * s

    drawOval(
        color = lip,
        topLeft = Offset(centerX - groundWidth / 2f, lipCenterY - groundHeight / 2f),
        size = Size(groundWidth, groundHeight)
    )
    drawOval(
        color = face,
        topLeft = Offset(centerX - groundWidth / 2f, faceCenterY - groundHeight / 2f),
        size = Size(groundWidth, groundHeight)
    )
}

/** The child's own avatar standing on a blue tactile podium, with a name pill below it. */
@Composable
private fun OwnAvatarPodium(
    profile: ChildProfile,
    catalogById: Map<String, WardrobeItem>,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lixo_own_avatar_podium_scale"
    )

    Column(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .avatarGround(face = LixoColors.Blue, lip = LixoColors.BlueLip)
        ) {
            Avatar3DViewport(
                appearance = profile.appearance,
                catalogById = catalogById,
                modifier = Modifier.fillMaxSize(),
                interactiveRotation = true
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(LixoColors.Blue)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${LixoStrings.AVATAR_MAP_ME_PREFIX} · ${profile.displayName}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

/** One friend's grass plot: a green tactile oval, their 3D avatar, a name tag and a star pill. */
@Composable
private fun FriendPlot(
    friend: FriendProfile,
    catalogById: Map<String, WardrobeItem>,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lixo_friend_plot_scale"
    )

    Column(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .avatarGround(face = LixoColors.Green, lip = LixoColors.GreenLip)
        ) {
            Avatar3DViewport(
                appearance = friend.appearance,
                catalogById = catalogById,
                modifier = Modifier.fillMaxSize(),
                interactiveRotation = false
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(1.5.dp, LixoColors.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "@${friend.username}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = LixoColors.TextPrimary
            )
        }
        Spacer(Modifier.height(4.dp))
        StatPill(icon = LixoIcons.Star, value = "${friend.totalStars}", tint = LixoColors.GoldLip)
    }
}

/** The dashed-border "+" plot at the end of the path that opens the add-friend sheet. */
@Composable
private fun AddFriendPlot(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lixo_add_friend_plot_scale"
    )

    Column(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(50))
                .background(LixoColors.Surface)
                .drawBehind {
                    drawCircle(
                        color = LixoColors.BorderDarker,
                        radius = size.minDimension / 2f - 4.dp.toPx(),
                        style = Stroke(
                            width = 3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(LixoColors.Border),
                contentAlignment = Alignment.Center
            ) {
                LixoIcon(icon = LixoIcons.Plus, tint = LixoColors.TextSecondary, size = 22.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = LixoStrings.AVATAR_MAP_ADD_FRIEND,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = LixoColors.TextSecondary
        )
    }
}
