package se.lixo.app.ui.screens.social

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import se.lixo.app.data.remote.FriendInvite
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentItem
import se.lixo.app.domain.model.AddFriendError
import se.lixo.app.domain.model.AddFriendException
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.social.Nickname
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.platform.QrScanResult
import se.lixo.app.platform.QrScanner
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.celebration.FriendRevealStage
import se.lixo.app.ui.theme.LixoBottomSheet
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.SegmentedTabs
import se.lixo.app.ui.theme.TtsIconButton

/**
 * "Kompisbyn" (the friend village): a Duolingo-style scene where the child's own avatar stands
 * on a podium in the middle and friends are laid out on grass plots in a winding, zig-zag path
 * below it. Replaces the old friends tab that used to live inside the Trophies screen.
 *
 * Friends are added with a code/QR only (doc/plans/030 §7), never by searching a username: the
 * [onAddFriendByCode] / [onCreateFriendCode] / [observeNewFriendsDuring] callbacks come straight
 * from [se.lixo.app.data.repository.LixoRepository]'s friend API, [qrScanner] and [nowMillis] are
 * passed in explicitly (rather than resolved from a platform singleton/real clock) so an
 * instrumented test can inject a fake scanner and fast-forward the invite countdown.
 */
@Composable
fun AvatarMapScreen(
    profile: ChildProfile,
    friends: List<FriendProfile>,
    content: ActiveContent,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    qrScanner: QrScanner,
    nowMillis: () -> Long,
    prefilledFriendCode: String? = null,
    onConsumePrefilledFriendCode: () -> Unit = {},
    onOpenWardrobe: () -> Unit,
    onShuffleNickname: suspend () -> Result<Nickname>,
    onCreateFriendCode: suspend () -> Result<FriendInvite>,
    onStopFriendCode: suspend () -> Unit,
    observeNewFriendsDuring: (String) -> Flow<FriendProfile>,
    onAddFriendByCode: suspend (String, Boolean) -> Result<FriendProfile>,
    onRemoveFriend: (String) -> Unit,
    onSetFriendLabel: (String, String?) -> Unit,
    unrevealedFriends: Flow<List<FriendProfile>>,
    onMarkRevealed: (String) -> Unit,
    observeFriendCards: () -> Flow<List<FriendProfile>>,
    onOpenContentPreview: (() -> Unit)? = null
) {
    // doc/plans/030 §6: while this screen is visible, listen live to every friend's card (gear
    // changes show up right away) via LixoRepository.observeFriendCards, which writes through to
    // Room/in-memory state; the updated `friends` list then flows back in through the `friends`
    // parameter above. Keyed on the friends' card ids (+ profile) so listeners restart when the
    // friend list changes, and stop entirely once this screen leaves composition.
    LaunchedEffect(profile.id, friends.map { it.cardId }) {
        // doc/plans/030 review H4: never let an exception from the live card listener escape to
        // the UI -- FriendCloud.observeCard itself must not throw, but this collector is the
        // last line of defense against an ex-friend removing this device becoming a crash.
        runCatching { observeFriendCards().collect { } }
    }
    var selectedFriend by remember { mutableStateOf<FriendProfile?>(null) }
    var showAddFriendSheet by remember { mutableStateOf(prefilledFriendCode != null) }
    var addFriendTab by remember { mutableStateOf(if (prefilledFriendCode != null) 1 else 0) }
    LaunchedEffect(prefilledFriendCode) {
        if (prefilledFriendCode != null) {
            addFriendTab = 1
            showAddFriendSheet = true
        }
    }
    var newlyAddedFriendUid by remember { mutableStateOf<String?>(null) }

    // doc/plans/030 §7 "kompis-kistan": right after a scanner-side redeem, before the name step.
    var scannerSideReveal by remember { mutableStateOf<FriendProfile?>(null) }
    // The step after a successful add (scan or type, scanner side only): "Vad heter din kompis?".
    var nameStepFor by remember { mutableStateOf<FriendProfile?>(null) }

    val unrevealed by unrevealedFriends.collectAsState(initial = emptyList())

    // Inviter-side reveal queue (both "sheet open, someone just scanned" and "friends added
    // while the tab was closed, picked up by refreshFriends on open") -- both insert the friend
    // with revealedAtEpochMillis == null, so a single unrevealedFriends() stream drives both.
    val inviterReveal = unrevealed.firstOrNull()

    LaunchedEffect(inviterReveal?.userId) {
        if (inviterReveal != null) {
            newlyAddedFriendUid = inviterReveal.userId
        }
    }

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
                // doc/plans/030 §7: stars are never shown in the friend village.
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
                            content = content,
                            onClick = onOpenWardrobe,
                            onShuffleNickname = onShuffleNickname
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

                        if (onOpenContentPreview != null) {
                            Spacer(Modifier.height(8.dp))
                            LixoButton(
                                onClick = onOpenContentPreview,
                                variant = LixoButtonVariant.GHOST,
                                height = 40.dp
                            ) {
                                Text(
                                    text = LixoStrings.CONTENT_PREVIEW_OPEN_BTN,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LixoColors.TextSecondary
                                )
                            }
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
                                        content = content,
                                        isNewlyAdded = friend.userId == newlyAddedFriendUid,
                                        onClick = { selectedFriend = friend }
                                    )
                                } else {
                                    AddFriendPlot(
                                        unrevealedCount = unrevealed.size,
                                        onClick = { addFriendTab = 0; showAddFriendSheet = true }
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            for (index in 1 until totalPlots step 2) {
                                if (index < friends.size) {
                                    val friend = friends[index]
                                    FriendPlot(
                                        friend = friend,
                                        content = content,
                                        isNewlyAdded = friend.userId == newlyAddedFriendUid,
                                        onClick = { selectedFriend = friend }
                                    )
                                } else {
                                    AddFriendPlot(
                                        unrevealedCount = unrevealed.size,
                                        onClick = { addFriendTab = 0; showAddFriendSheet = true }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (newlyAddedFriendUid != null) {
        LaunchedEffect(newlyAddedFriendUid) {
            delay(3000)
            newlyAddedFriendUid = null
        }
    }

    // Friend detail sheet
    LixoBottomSheet(
        visible = selectedFriend != null,
        onDismiss = { selectedFriend = null }
    ) {
        selectedFriend?.let { friend ->
            FriendDetailSheetContent(
                friend = friend,
                content = content,
                showSwedishTtsButtons = showSwedishTtsButtons,
                ttsEngine = ttsEngine,
                onSetFriendLabel = onSetFriendLabel,
                onRemoveFriend = {
                    onRemoveFriend(friend.userId)
                    selectedFriend = null
                },
                onClose = { selectedFriend = null }
            )
        }
    }

    // Add-friend sheet: "Min kompiskod" / "Lägg till kompis".
    LixoBottomSheet(
        visible = showAddFriendSheet,
        onDismiss = { showAddFriendSheet = false }
    ) {
        AddFriendSheetContent(
            content = content,
            qrScanner = qrScanner,
            nowMillis = nowMillis,
            initialTab = addFriendTab,
            prefilledFriendCode = prefilledFriendCode,
            onConsumePrefilledFriendCode = onConsumePrefilledFriendCode,
            onCreateFriendCode = onCreateFriendCode,
            onStopFriendCode = onStopFriendCode,
            observeNewFriendsDuring = observeNewFriendsDuring,
            onAddFriendByCode = onAddFriendByCode,
            onRemoveFriend = onRemoveFriend,
            onAdded = { friend ->
                showAddFriendSheet = false
                scannerSideReveal = friend
            }
        )
    }

    // doc/plans/030 §7 "kompis-kistan": scanner-side reveal, right after redeeming, before the
    // name step.
    scannerSideReveal?.let { friend ->
        FriendRevealStage(
            content = content,
            friend = friend,
            words = content.nicknameWords,
            onFinished = {
                scannerSideReveal = null
                nameStepFor = friend
                newlyAddedFriendUid = friend.userId
            }
        )
    }

    // Inviter-side reveal: driven purely by unrevealedFriends (covers both "someone just scanned
    // my open code" and "I'm picking up friends added while the tab was closed").
    inviterReveal?.let { friend ->
        if (scannerSideReveal == null) {
            FriendRevealStage(
                content = content,
                friend = friend,
                words = content.nicknameWords,
                queuedCount = unrevealed.size - 1,
                onFinished = { onMarkRevealed(friend.userId) }
            )
        }
    }

    // "Vad heter din kompis?" name step (doc/plans/030 §7), scanner side only.
    nameStepFor?.let { friend ->
        FriendNameStepSheet(
            friend = friend,
            content = content,
            onSave = { label ->
                onSetFriendLabel(friend.userId, label)
                nameStepFor = null
            },
            onSkip = { nameStepFor = null }
        )
    }
}

/** The "Vad heter din kompis?" step shown right after a successful add (doc/plans/030 §7). */
@Composable
private fun FriendNameStepSheet(
    friend: FriendProfile,
    content: ActiveContent,
    onSave: (String?) -> Unit,
    onSkip: () -> Unit
) {
    var label by remember(friend.userId) { mutableStateOf("") }
    LixoBottomSheet(visible = true, onDismiss = onSkip) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = LixoStrings.FRIENDS_NAME_STEP_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .avatarGround(face = LixoColors.Green, lip = LixoColors.GreenLip),
                contentAlignment = Alignment.Center
            ) {
                AvatarViewport(
                    content = content,
                    appearance = friend.appearance,
                    modifier = Modifier.fillMaxSize(),
                    interactiveRotation = false
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text(friend.nick.render(content.nicknameWords)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LixoColors.Blue,
                    unfocusedBorderColor = LixoColors.Border
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = LixoStrings.FRIENDS_NAME_STEP_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = LixoColors.TextMuted
            )
            Spacer(Modifier.height(16.dp))
            LixoButton(
                onClick = { onSave(label.trim().ifBlank { null }) },
                variant = LixoButtonVariant.PRIMARY,
                height = 52.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(LixoStrings.FRIENDS_NAME_SAVE_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            LixoButton(
                onClick = onSkip,
                variant = LixoButtonVariant.OUTLINE,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(LixoStrings.FRIENDS_NAME_SKIP_BTN, style = MaterialTheme.typography.labelLarge, color = LixoColors.Blue)
            }
        }
    }
}

/** Friend detail sheet content: header, "Byt namn", gear list, "Ta bort kompis". */
@Composable
private fun FriendDetailSheetContent(
    friend: FriendProfile,
    content: ActiveContent,
    showSwedishTtsButtons: Boolean,
    ttsEngine: TextToSpeechEngine,
    onSetFriendLabel: (String, String?) -> Unit,
    onRemoveFriend: () -> Unit,
    onClose: () -> Unit
) {
    var editingLabel by remember(friend.userId) { mutableStateOf(false) }
    var labelInput by remember(friend.userId) { mutableStateOf(friend.label.orEmpty()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .avatarGround(face = LixoColors.Green, lip = LixoColors.GreenLip),
            contentAlignment = Alignment.Center
        ) {
            AvatarViewport(
                content = content,
                appearance = friend.appearance,
                modifier = Modifier.fillMaxSize(),
                interactiveRotation = true
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = friend.label?.let { "$it (@${friend.nick.render(content.nicknameWords)})" }
                ?: "@${friend.nick.render(content.nicknameWords)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = LixoColors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = content.avatarName(friend.appearance.avatarId),
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )
        }
        Spacer(Modifier.height(12.dp))

        if (editingLabel) {
            OutlinedTextField(
                value = labelInput,
                onValueChange = { labelInput = it },
                placeholder = { Text(friend.nick.render(content.nicknameWords)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LixoColors.Blue,
                    unfocusedBorderColor = LixoColors.Border
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LixoButton(
                onClick = {
                    onSetFriendLabel(friend.userId, labelInput.trim().ifBlank { null })
                    editingLabel = false
                },
                variant = LixoButtonVariant.PRIMARY,
                height = 44.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(LixoStrings.FRIENDS_NAME_SAVE_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        } else {
            LixoButton(
                onClick = { editingLabel = true },
                variant = LixoButtonVariant.OUTLINE,
                height = 44.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(LixoStrings.FRIEND_SHEET_CHANGE_NAME_BTN, style = MaterialTheme.typography.labelLarge, color = LixoColors.Blue)
            }
        }

        Spacer(Modifier.height(20.dp))

        val equippedGear = remember(friend, content) {
            EquipmentSlot.entries
                .mapNotNull { slot -> friend.appearance.equipped[slot]?.let { content.itemsById[it] } }
                .sortedByDescending { it.rarity.ordinal }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = LixoStrings.FRIEND_SHEET_GEAR_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (showSwedishTtsButtons) {
                Spacer(Modifier.width(8.dp))
                TtsIconButton(
                    textToSpeak = "${LixoStrings.FRIEND_SHEET_GEAR_TITLE}. " +
                        equippedGear.joinToString(". ") { content.itemName(it) },
                    ttsEngine = ttsEngine
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (equippedGear.isEmpty()) {
            Text(
                text = LixoStrings.FRIEND_SHEET_NO_GEAR,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextMuted,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                equippedGear.forEach { item ->
                    EquippedGearRow(content = content, item = item)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        LixoButton(
            onClick = onRemoveFriend,
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
            onClick = onClose,
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

/** The two-tab "Min kompiskod" / "Lägg till kompis" sheet content (doc/plans/030 §7). */
@Composable
private fun AddFriendSheetContent(
    content: ActiveContent,
    qrScanner: QrScanner,
    nowMillis: () -> Long,
    initialTab: Int,
    prefilledFriendCode: String?,
    onConsumePrefilledFriendCode: () -> Unit,
    onCreateFriendCode: suspend () -> Result<FriendInvite>,
    onStopFriendCode: suspend () -> Unit,
    observeNewFriendsDuring: (String) -> Flow<FriendProfile>,
    onAddFriendByCode: suspend (String, Boolean) -> Result<FriendProfile>,
    onRemoveFriend: (String) -> Unit,
    onAdded: (FriendProfile) -> Unit
) {
    var tab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) { tab = initialTab }

    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedTabs(
            tabs = listOf(LixoStrings.FRIENDS_TAB_MY_CODE, LixoStrings.FRIENDS_TAB_ADD_FRIEND),
            selectedIndex = tab,
            onTabSelected = { tab = it }
        )
        Spacer(Modifier.height(16.dp))

        if (tab == 0) {
            MyCodeTab(
                nowMillis = nowMillis,
                onCreateFriendCode = onCreateFriendCode,
                onStopFriendCode = onStopFriendCode,
                observeNewFriendsDuring = observeNewFriendsDuring,
                content = content,
                onRemoveFriend = onRemoveFriend
            )
        } else {
            AddFriendTab(
                qrScanner = qrScanner,
                prefilledFriendCode = prefilledFriendCode,
                onConsumePrefilledFriendCode = onConsumePrefilledFriendCode,
                onAddFriendByCode = onAddFriendByCode,
                onAdded = onAdded
            )
        }
    }
}

/** "Min kompiskod": the QR, the code as text, the countdown, and the live "Nya kompisar" list. */
@Composable
private fun MyCodeTab(
    nowMillis: () -> Long,
    onCreateFriendCode: suspend () -> Result<FriendInvite>,
    onStopFriendCode: suspend () -> Unit,
    observeNewFriendsDuring: (String) -> Flow<FriendProfile>,
    content: ActiveContent,
    onRemoveFriend: (String) -> Unit
) {
    var invite by remember { mutableStateOf<FriendInvite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var nowTick by remember { mutableStateOf(nowMillis()) }
    // doc/plans/030 review M5: "Stoppa koden" used to set `invite = null`, which fell into the
    // SAME branch below as "still loading the first code" (FRIENDS_CODE_LOADING), stuck forever
    // since nothing re-requests a code after a deliberate stop. Track the stop explicitly so that
    // state shows "Visa ny kod" instead.
    var stoppedByUser by remember { mutableStateOf(false) }
    val newFriends = remember { mutableStateOf(listOf<FriendProfile>()) }
    val scope = rememberCoroutineScope()

    suspend fun requestNewCode() {
        error = null
        stoppedByUser = false
        onCreateFriendCode().fold(
            onSuccess = { invite = it },
            onFailure = {
                val reason = (it as? AddFriendException)?.reason
                error = if (reason == AddFriendError.NETWORK || reason == null) {
                    LixoStrings.FRIENDS_OFFLINE_NOTICE
                } else {
                    LixoStrings.FRIENDS_ERROR_NETWORK
                }
            }
        )
    }

    LaunchedEffect(Unit) { requestNewCode() }

    // Ticks every second so the "Koden gäller i m:ss" countdown (and the expiry -> "Visa ny kod"
    // transition) updates live while the sheet is open.
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = nowMillis()
            delay(1000)
        }
    }

    val currentInvite = invite
    val isExpired = currentInvite != null && currentInvite.expiresAtEpochMillis <= nowTick

    LaunchedEffect(currentInvite?.code) {
        val code = currentInvite?.code ?: return@LaunchedEffect
        observeNewFriendsDuring(code).collect { friend ->
            newFriends.value = newFriends.value + friend
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when {
            error != null -> Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.Red
            )
            currentInvite == null && stoppedByUser -> LixoButton(
                onClick = { scope.launch { requestNewCode() } },
                variant = LixoButtonVariant.PRIMARY,
                height = 44.dp
            ) {
                Text(LixoStrings.FRIENDS_SHOW_NEW_CODE_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            currentInvite == null -> Text(
                text = LixoStrings.FRIENDS_CODE_LOADING,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.TextSecondary
            )
            else -> {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .testTag("friend_qr")
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberQrCodePainter(buildFriendPayload(currentInvite.code)),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (isExpired) 0.35f else 1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = formatFriendCodeForDisplay(currentInvite.code),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isExpired) LixoColors.TextMuted else LixoColors.TextPrimary,
                    modifier = Modifier.testTag("friend_code_text")
                )
                Spacer(Modifier.height(6.dp))
                if (isExpired) {
                    LixoButton(
                        onClick = { scope.launch { requestNewCode() } },
                        variant = LixoButtonVariant.PRIMARY,
                        height = 44.dp
                    ) {
                        Text(LixoStrings.FRIENDS_SHOW_NEW_CODE_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                } else {
                    Text(
                        text = LixoStrings.friendCodeExpiresIn((currentInvite.expiresAtEpochMillis - nowTick) / 1000),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    LixoButton(
                        onClick = {
                            scope.launch {
                                onStopFriendCode()
                                stoppedByUser = true
                                invite = null
                            }
                        },
                        variant = LixoButtonVariant.GHOST,
                        height = 40.dp
                    ) {
                        Text(LixoStrings.FRIENDS_STOP_CODE_BTN, style = MaterialTheme.typography.labelLarge, color = LixoColors.Red)
                    }
                }
            }
        }

        if (newFriends.value.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = LixoStrings.FRIENDS_NEW_FRIENDS_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LixoColors.TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().testTag("new_friends_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                newFriends.value.forEach { friend ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "@${friend.nick.render(content.nicknameWords)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = LixoColors.TextPrimary
                        )
                        LixoButton(
                            onClick = {
                                newFriends.value = newFriends.value.filterNot { it.userId == friend.userId }
                                onRemoveFriend(friend.userId)
                            },
                            variant = LixoButtonVariant.GHOST,
                            height = 32.dp,
                            modifier = Modifier.testTag("new_friend_remove_${friend.userId}")
                        ) {
                            LixoIcon(icon = LixoIcons.Close, tint = LixoColors.Red, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
}

/** "Lägg till kompis": scan button, code field, "Lägg till". */
@Composable
private fun AddFriendTab(
    qrScanner: QrScanner,
    prefilledFriendCode: String?,
    onConsumePrefilledFriendCode: () -> Unit,
    onAddFriendByCode: suspend (String, Boolean) -> Result<FriendProfile>,
    onAdded: (FriendProfile) -> Unit
) {
    var codeInput by remember { mutableStateOf(prefilledFriendCode.orEmpty()) }
    var fromQr by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var scannerUnavailable by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefilledFriendCode) {
        if (prefilledFriendCode != null) {
            codeInput = prefilledFriendCode
            onConsumePrefilledFriendCode()
        }
    }

    suspend fun submit(raw: String, viaQr: Boolean) {
        if (raw.isBlank() || isSubmitting) return
        isSubmitting = true
        error = null
        val result = onAddFriendByCode(raw, viaQr)
        isSubmitting = false
        result.fold(
            onSuccess = { friend ->
                codeInput = ""
                onAdded(friend)
            },
            onFailure = {
                val reason = (it as? AddFriendException)?.reason
                error = when (reason) {
                    AddFriendError.NOT_FOUND -> LixoStrings.FRIENDS_ERROR_NOT_FOUND
                    AddFriendError.ALREADY_FRIEND -> LixoStrings.FRIENDS_ERROR_ALREADY
                    AddFriendError.EXPIRED -> LixoStrings.FRIENDS_ERROR_EXPIRED
                    AddFriendError.SELF -> LixoStrings.FRIENDS_ERROR_SELF
                    AddFriendError.FULL -> LixoStrings.FRIENDS_ERROR_FULL
                    AddFriendError.INVALID_QR -> LixoStrings.FRIENDS_ERROR_INVALID_QR
                    AddFriendError.NETWORK, null -> LixoStrings.FRIENDS_ERROR_NETWORK
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LixoButton(
            onClick = {
                scope.launch {
                    when (val scanResult = qrScanner.scan()) {
                        is QrScanResult.Success -> {
                            fromQr = true
                            codeInput = scanResult.raw
                            submit(scanResult.raw, viaQr = true)
                        }
                        QrScanResult.Cancelled -> Unit
                        QrScanResult.Unavailable -> {
                            scannerUnavailable = true
                            focusRequester.requestFocus()
                        }
                    }
                }
            },
            variant = LixoButtonVariant.PRIMARY,
            height = 54.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(LixoStrings.FRIENDS_SCAN_QR_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }

        if (scannerUnavailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = LixoStrings.FRIENDS_SCANNER_UNAVAILABLE,
                style = MaterialTheme.typography.bodySmall,
                color = LixoColors.TextSecondary
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = {
                    fromQr = false
                    codeInput = it.uppercase()
                    error = null
                },
                placeholder = { Text(LixoStrings.FRIENDS_CODE_FIELD_PLACEHOLDER) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LixoColors.Blue,
                    unfocusedBorderColor = LixoColors.Border
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("friend_code_field")
            )
            LixoButton(
                onClick = { scope.launch { submit(codeInput, fromQr) } },
                enabled = !isSubmitting,
                variant = LixoButtonVariant.PRIMARY,
                height = 54.dp
            ) {
                Text(LixoStrings.FRIENDS_ADD_BTN, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }

        error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodyMedium,
                color = LixoColors.Red
            )
        }
    }
}

/** `"ABCD-1234"`, for reading aloud; falls back to the raw code if its length is unexpected. */
private fun formatFriendCodeForDisplay(code: String): String =
    if (code.length == 8) "${code.take(4)}-${code.takeLast(4)}" else code

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
 * reads as standing on a tactile base, matching [AvatarViewport]'s own shadow/ring geometry.
 * Drawn with `drawBehind` so it renders under the avatar's own shadow/ring rather than as a
 * separate floating pill.
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

/** The child's own avatar standing on a blue tactile podium, with a name pill + "Slumpa" below it. */
@Composable
private fun OwnAvatarPodium(
    profile: ChildProfile,
    content: ActiveContent,
    onClick: () -> Unit,
    onShuffleNickname: suspend () -> Result<Nickname>
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lixo_own_avatar_podium_scale"
    )
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                AvatarViewport(
                    content = content,
                    appearance = profile.appearance,
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
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${LixoStrings.AVATAR_MAP_NICKNAME_PREFIX} @${profile.nickname?.render(content.nicknameWords).orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = LixoColors.TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            LixoButton(
                onClick = { scope.launch { onShuffleNickname() } },
                variant = LixoButtonVariant.GHOST,
                height = 28.dp
            ) {
                Text(
                    text = LixoStrings.AVATAR_MAP_SHUFFLE_NICKNAME_BTN,
                    style = MaterialTheme.typography.labelSmall,
                    color = LixoColors.Blue
                )
            }
        }
    }
}

/** One friend's grass plot: a green tactile oval, their 3D avatar and a name tag (label + @nick). */
@Composable
private fun FriendPlot(
    friend: FriendProfile,
    content: ActiveContent,
    isNewlyAdded: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lixo_friend_plot_scale"
    )
    // doc/plans/030 §7 landing animation: a brief pop + glow when this plot was just added.
    val landingScale by animateFloatAsState(
        targetValue = if (isNewlyAdded) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.4f),
        label = "lixo_friend_plot_landing_scale"
    )

    Column(
        modifier = Modifier
            .scale(pressScale * landingScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag("friend_plot_${friend.userId}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .avatarGround(face = LixoColors.Green, lip = LixoColors.GreenLip)
                .let {
                    if (isNewlyAdded) {
                        it.border(3.dp, LixoColors.Gold, CircleShape)
                    } else it
                }
        ) {
            AvatarViewport(
                content = content,
                appearance = friend.appearance,
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val label = friend.label
                Text(
                    text = label ?: "@${friend.nick.render(content.nicknameWords)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = LixoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (label != null) {
                    Text(
                        text = "@${friend.nick.render(content.nicknameWords)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LixoColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // doc/plans/030 §7: stars are never shown in the friend village.

        val equippedRarities = remember(friend, content) {
            EquipmentSlot.entries.mapNotNull { slot ->
                friend.appearance.equipped[slot]?.let { content.itemsById[it] }?.rarity
            }
        }
        if (equippedRarities.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                equippedRarities.forEach { rarity ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(LixoColors.rarityColor(rarity))
                    )
                }
            }
        }
    }
}

/** The dashed-border "+" plot at the end of the path that opens the add-friend sheet. */
@Composable
private fun AddFriendPlot(unrevealedCount: Int, onClick: () -> Unit) {
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
        Box {
            Box(
                modifier = Modifier
                    .size(150.dp)
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
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(LixoColors.Border),
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(icon = LixoIcons.Plus, tint = LixoColors.TextSecondary, size = 28.dp)
                }
            }
            if (unrevealedCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LixoColors.Red)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = LixoStrings.friendsUnrevealedBadge(unrevealedCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
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

/** One row in the friend's gear list: a rarity badge, item name and slot, modeled on the wardrobe item card. */
@Composable
private fun EquippedGearRow(content: ActiveContent, item: ContentItem) {
    val rarityColor = LixoColors.rarityColor(item.rarity)

    LixoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(rarityColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = LixoStrings.rarityName(item.rarity).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = rarityColor
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = content.itemName(item),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LixoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = LixoStrings.slotName(item.slot),
                    style = MaterialTheme.typography.labelSmall,
                    color = LixoColors.TextSecondary
                )
            }
        }
    }
}
