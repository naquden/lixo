package se.lixo.app.verify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import se.lixo.app.data.local.ProfileStore
import se.lixo.app.data.remote.CloudSyncGateway
import se.lixo.app.data.remote.FirebaseSyncService
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.platform.QrScanResult
import se.lixo.app.platform.QrScanner
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.ui.screens.social.AvatarMapScreen
import se.lixo.app.ui.theme.LixoTheme

/**
 * Shared test plumbing for doc/plans/030 instrumented friend tests: a [CloudSyncGateway] whose
 * [CloudSyncGateway.ensureSignedIn] is bound to a fixed uid (one simulated "device"), a
 * [LixoRepository] builder sharing one [FakeFriendCloudStore] across several such devices (mirrors
 * `FriendFlowTest`'s commonTest pattern), a controllable [FakeQrScanner], and [FriendVillageTestHost]
 * which wires [AvatarMapScreen] to a [LixoRepository] exactly the way [se.lixo.app.ui.LixoApp] does.
 */
fun friendGatewayFor(uid: String): CloudSyncGateway = object : CloudSyncGateway by FirebaseSyncService() {
    override suspend fun ensureSignedIn(): Result<String> = Result.success(uid)
}

/**
 * Builds one "device" [LixoRepository] sharing [store] with other devices (doc/plans/030 §8). With
 * no [profileStore] (the common case for these tests) [profilesLoaded] is already `true` at
 * construction time, so it's safe to [LixoRepository.createProfile] synchronously right away. With
 * a real, persisted [profileStore] (cold-start tests) that load is asynchronous, so [autoCreateProfile]
 * defaults to `false` -- the caller must wait for `profilesLoaded` itself before creating/reading.
 */
fun friendRepoFor(
    uid: String,
    store: FakeFriendCloudStore,
    clock: () -> Long = { 0L },
    profileStore: ProfileStore? = null,
    displayName: String = "Child_$uid",
    autoCreateProfile: Boolean = profileStore == null
): LixoRepository {
    val repo = LixoRepository(
        friendGatewayFor(uid),
        friendCloud = FakeFriendCloud(uid, store, clock),
        clockMillis = clock,
        lexiconCache = null,
        isDebugBuild = false,
        profileStore = profileStore,
        content = TestContent.repository
    )
    if (autoCreateProfile && repo.profiles.value.isEmpty()) {
        repo.createProfile(displayName, 8)
    }
    return repo
}

/**
 * `AnimatedVisibility`-based [se.lixo.app.ui.theme.LixoBottomSheet] keeps the screen behind it
 * composed, so once the sheet is open a label like "Lägg till kompis" exists both on the village's
 * "+" plot and on the sheet's tab. [clickFirst] targets the first match (the plot, before the sheet
 * opens); [clickLast] targets the last (the sheet's own tab/button, composed after it).
 */
fun ComposeTestRule.clickFirst(text: String) {
    onAllNodesWithText(text, substring = false, ignoreCase = true).onFirst().performClick()
    waitForIdle()
}

fun ComposeTestRule.clickLast(text: String) {
    onAllNodesWithText(text, substring = false, ignoreCase = true).onLast().performClick()
    waitForIdle()
}

/** `true` if at least one node currently displays [text] (for polling inside `waitUntil`). */
fun ComposeTestRule.hasNodeWithText(text: String): Boolean =
    onAllNodesWithText(text, substring = false, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

/** The rendered text of a single-text-node match (e.g. `onNodeWithTag("friend_code_text")`). */
fun SemanticsNodeInteraction.readText(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

/** A [QrScanner] whose next [scan] result is set directly by the test (doc/plans/030 §7). */
class FakeQrScanner(var nextResult: QrScanResult = QrScanResult.Cancelled) : QrScanner {
    override suspend fun scan(): QrScanResult = nextResult
}

/**
 * Hosts [AvatarMapScreen] wired to [repository]'s friend API with the same callbacks
 * [se.lixo.app.ui.LixoApp] uses (`onCreateFriendCode`, `observeNewFriendsDuring`,
 * `onAddFriendByCode`, `unrevealedFriends`, …), plus the same `refreshFriends()` call LixoApp
 * makes when the Avatar tab opens. [qrScanner] and [nowMillis] are injected so a test can control
 * scanning/time deterministically, the same way [prefilledFriendCode] stands in for a deep link.
 *
 * Mirrors [se.lixo.app.ui.LixoApp]'s wiring of [AvatarMapScreen] exactly, including passing
 * [LixoRepository.observeFriendCards] through `AvatarMapScreen`'s `observeFriendCards` parameter
 * (doc/plans/030 §6: "while the Avatar tab is open, the app listens live to each friend's card").
 */
@Composable
fun FriendVillageTestHost(
    repository: LixoRepository,
    qrScanner: QrScanner,
    nowMillis: () -> Long = { repository.nowMillis() },
    prefilledFriendCode: String? = null,
    onOpenWardrobe: () -> Unit = {}
) {
    val profiles by repository.profiles.collectAsState()
    val activeProfileId by repository.activeProfileId.collectAsState()
    val content by repository.content.active.collectAsState()
    val friendsByProfile by repository.friendsByProfile.collectAsState()
    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()
    val ttsEngine = remember { createPlatformTextToSpeechEngine() }
    var pendingCode by remember(prefilledFriendCode) { mutableStateOf(prefilledFriendCode) }

    LaunchedEffect(activeProfile?.id) {
        if (activeProfile != null) repository.refreshFriends()
    }

    LixoTheme {
        if (activeProfile != null) {
            AvatarMapScreen(
                profile = activeProfile,
                friends = friendsByProfile[activeProfile.id].orEmpty(),
                content = content,
                ttsEngine = ttsEngine,
                showSwedishTtsButtons = false,
                qrScanner = qrScanner,
                nowMillis = nowMillis,
                prefilledFriendCode = pendingCode,
                onConsumePrefilledFriendCode = { pendingCode = null },
                onOpenWardrobe = onOpenWardrobe,
                onCreateFriendCode = { repository.createFriendCode() },
                onStopFriendCode = { repository.stopFriendCode() },
                observeNewFriendsDuring = { repository.observeNewFriendsDuring(it) },
                onAddFriendByCode = { raw, fromQr -> repository.addFriendByCode(raw, fromQr) },
                onRemoveFriend = { repository.removeFriend(it) },
                onSetFriendLabel = { uid, label -> repository.setFriendLabel(uid, label) },
                unrevealedFriends = repository.unrevealedFriends(activeProfile.id),
                onMarkRevealed = { repository.markRevealed(activeProfile.id, it) },
                observeFriendCards = { repository.observeFriendCards(activeProfile.id) }
            )
        }
    }
}
