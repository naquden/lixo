package se.lixo.app.ios

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.handleIncomingUrl
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.platform.QrScanResult
import se.lixo.app.platform.createPlatformQrScanner
import se.lixo.app.ui.LixoApp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosQrUnavailableTest {

    @AfterTest
    fun tearDown() {
        FriendDeepLinkDispatcher.consumeIncomingFriendCode()
    }

    @Test
    fun simulator_scanReturnsUnavailable() {
        // No camera on the simulator and no top view controller in a headless test: the scanner
        // must short-circuit to Unavailable without ever requesting camera permission (the test
        // binary has no NSCameraUsageDescription, so a permission prompt would abort it).
        assertEquals(QrScanResult.Unavailable, runBlocking { createPlatformQrScanner().scan() })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun addFriendTab_showsTypedCodeField() {
        val store = FakeFriendCloudStore()
        val repoA = iosRepo("uidA_qr", store, createProfile = true)
        val repoB = iosRepo("uidB_qr", store, createProfile = true)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        handleIncomingUrl(buildFriendPayload(invite.code))

        runComposeUiTest {
            setContent { LixoApp(repository = repoB) }
            waitUntil(5_000) { onAllNodesWithTag("friend_code_field").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("friend_code_field").assertExists()
        }
    }
}
