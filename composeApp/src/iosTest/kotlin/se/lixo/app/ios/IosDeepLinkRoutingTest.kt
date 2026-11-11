package se.lixo.app.ios

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import se.lixo.app.data.remote.fake.FakeFriendCloudStore
import se.lixo.app.domain.social.buildFriendPayload
import se.lixo.app.handleIncomingUrl
import se.lixo.app.platform.EmailLinkAuthDispatcher
import se.lixo.app.platform.FriendDeepLinkDispatcher
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.theme.LixoStrings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDeepLinkRoutingTest {

    @AfterTest
    fun tearDown() {
        FriendDeepLinkDispatcher.consumeIncomingFriendCode()
        EmailLinkAuthDispatcher.consumeIncomingLink()
    }

    @Test
    fun friendLink_routesToFriendDispatcher() {
        val link = buildFriendPayload("ABCD2345")

        assertTrue(handleIncomingUrl(link))

        assertEquals(link, FriendDeepLinkDispatcher.incomingFriendCode.value)
        assertNull(EmailLinkAuthDispatcher.incomingLink.value)
    }

    @Test
    fun emailLink_routesToEmailDispatcher() {
        val link = "https://lixo-fd45d.firebaseapp.com/__/auth/links?oobCode=x"

        assertTrue(handleIncomingUrl(link))

        assertEquals(link, EmailLinkAuthDispatcher.incomingLink.value)
        assertNull(FriendDeepLinkDispatcher.incomingFriendCode.value)
    }

    @Test
    fun junk_rejected() {
        assertTrue(!handleIncomingUrl("https://example.com/x"))

        assertNull(FriendDeepLinkDispatcher.incomingFriendCode.value)
        assertNull(EmailLinkAuthDispatcher.incomingLink.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun friendLink_opensAddFriendWithCodePrefilled() {
        val store = FakeFriendCloudStore()
        val repoA = iosRepo("uidA", store, createProfile = true)
        val repoB = iosRepo("uidB", store, createProfile = true)
        val invite = runBlocking { repoA.createFriendCode().getOrThrow() }

        handleIncomingUrl(buildFriendPayload(invite.code))

        runComposeUiTest {
            setContent { LixoApp(repository = repoB) }
            waitUntil(timeoutMillis = 5_000) { hasNodeWithText(LixoStrings.FRIENDS_TAB_ADD_FRIEND) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("friend_code_field").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("friend_code_field").assert(hasText(invite.code))
        }

        assertTrue(store.recordedWrites.none { it.startsWith("redeem") })
    }
}
