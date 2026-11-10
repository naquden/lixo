package se.lixo.app.ui.components.celebration

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.NicknameWords
import se.lixo.app.domain.model.FriendProfile
import se.lixo.app.domain.model.Rarity
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.feedback.LocalReducedMotion
import se.lixo.app.ui.feedback.SfxCue
import se.lixo.app.ui.feedback.rememberLixoHaptics
import se.lixo.app.ui.theme.LixoStrings

private enum class FriendRevealPhase { ENVELOPE, POPPED }

/**
 * The "kompis-kistan" full-screen reveal (doc/plans/030 §7): modelled on [ChestRevealStage] and
 * reusing [LightBurst]/[ConfettiOverlay]/[CelebrationScrim]. Unlike the chest there is no tap to
 * open -- a friend card never needs confirmation -- so it auto-advances: a short "kompis-portal"
 * wobble (skipped under [LocalReducedMotion]), then the friend's [AvatarViewport] springs in with
 * a light burst + confetti burst (also skipped under reduced motion, which just fades the avatar
 * in), then the title, then auto-finishes after 2.5s via [onFinished] (the caller marks the
 * friend revealed and moves on to the label step / next queued friend). A scrim tap finishes
 * immediately once popped. [queuedCount] renders "+N till" while more friends are waiting to be
 * revealed (inviter side, several readers scanned a code at once).
 */
@Composable
fun FriendRevealStage(
    content: ActiveContent,
    friend: FriendProfile,
    words: NicknameWords,
    queuedCount: Int = 0,
    onFinished: () -> Unit
) {
    val reducedMotion = LocalReducedMotion.current
    var phase by remember(friend.userId) { mutableStateOf(FriendRevealPhase.ENVELOPE) }
    val haptics = rememberLixoHaptics()
    val confetti = rememberConfettiState()
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    val size by rememberUpdatedState(hostSize)

    LaunchedEffect(friend.userId, reducedMotion) {
        haptics.tick(SfxCue.FRIEND_ADDED)
        if (!reducedMotion) delay(800)
        phase = FriendRevealPhase.POPPED
    }

    LaunchedEffect(phase, friend.userId) {
        if (phase != FriendRevealPhase.POPPED) return@LaunchedEffect
        haptics.heavy(SfxCue.FRIEND_ADDED)
        if (!reducedMotion) {
            val burstSize = if (size != IntSize.Zero) size else snapshotFlow { size }.first { it != IntSize.Zero }
            confetti.burst(
                Offset(burstSize.width / 2f, burstSize.height * 0.42f),
                confettiCountFor(Rarity.EPIC),
                confettiPaletteFor(Rarity.EPIC)
            )
        }
        delay(2500)
        onFinished()
    }

    val avatarScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (phase == FriendRevealPhase.POPPED) 1f else 0f,
        animationSpec = if (reducedMotion) tween(200) else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow),
        label = "lixo_friend_reveal_avatar_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { hostSize = it }
            .background(CelebrationScrim)
            .testTag("friend_reveal")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (phase == FriendRevealPhase.POPPED) onFinished()
            },
        contentAlignment = Alignment.Center
    ) {
        if (phase == FriendRevealPhase.POPPED) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = LixoStrings.FRIEND_REVEAL_TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    if (!reducedMotion) {
                        LightBurst(Rarity.EPIC, Modifier.size(260.dp))
                    }
                    AvatarViewport(
                        content = content,
                        appearance = friend.appearance,
                        modifier = Modifier.size(200.dp).scale(avatarScale),
                        interactiveRotation = false
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "@${friend.nick.render(words)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                if (queuedCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = LixoStrings.friendRevealQueuedMore(queuedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        } else {
            // Envelope/portal wobble (0 - 0.8s), skipped entirely under reduced motion since
            // phase jumps straight to POPPED in that case.
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .padding(8.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
            )
        }

        if (!reducedMotion) {
            ConfettiOverlay(confetti, Modifier.fillMaxSize().testTag("confetti"))
        }
    }
}
