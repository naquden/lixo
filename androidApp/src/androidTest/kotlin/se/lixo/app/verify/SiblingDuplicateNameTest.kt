package se.lixo.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.lixo.app.data.remote.fake.FakeFriendCloud
import se.lixo.app.data.repository.LixoRepository
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.platform.AndroidPlatformContext
import se.lixo.app.platform.RecognizedSpeechToken
import se.lixo.app.platform.SpeechRecognitionEngine
import se.lixo.app.platform.SttEngineState
import se.lixo.app.platform.SttProviderRank
import se.lixo.app.platform.createAndroidProfileStore
import se.lixo.app.platform.createPlatformTextToSpeechEngine
import se.lixo.app.screenshots.FakeCloudGateway
import se.lixo.app.ui.screens.onboarding.ProfileSelectAndTutorialScreen
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.LixoTheme

/**
 * Verifies doc/plans/050: the sibling-creation form on [ProfileSelectAndTutorialScreen] blocks a
 * name that another profile already has (trimmed, case-insensitive).
 */
class SiblingDuplicateNameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var outDir: File
    private lateinit var ctx: Context

    companion object {
        private const val TEST_DB = "lixo_test_050_sibling.db"
    }

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = instrumentation.targetContext
        AndroidPlatformContext.init(ctx)
        try {
            instrumentation.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: Throwable) {
        }
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        outDir = File(baseDir, "lixo_screenshots").apply { mkdirs() }
        ctx.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEST_DB)
    }

    /** Silent fake STT engine: the profile list never listens, it only has to be passed in. */
    private class SilentSttEngine : SpeechRecognitionEngine {
        override val recognizedTokens: SharedFlow<List<RecognizedSpeechToken>> = MutableSharedFlow()
        override val state: StateFlow<SttEngineState> = MutableStateFlow(SttEngineState.IDLE)
        override val lastLatencyMillis: StateFlow<Long> = MutableStateFlow(0L)
        override val activeProvider: SttProviderRank = SttProviderRank.SIMULATED_DETERMINISTIC

        override suspend fun requestMicrophonePermission(): Boolean = true
        override suspend fun startListening(languageIso: String, expectedWords: List<String>) {}
        override suspend fun stopListening() {}
        override suspend fun emitSimulatedWords(words: List<String>, confidence: Float) {}
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "050_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun siblingForm_duplicateName_showsErrorAndDisablesCreate() {
        val repo = LixoRepository(
            FakeCloudGateway(),
            friendCloud = FakeFriendCloud("uid_050_sibling"),
            lexiconCache = null,
            isDebugBuild = false,
            profileStore = createAndroidProfileStore(ctx, TEST_DB),
            content = TestContent.repository
        )
        compose.waitUntil(5_000) { repo.profilesLoaded.value }
        compose.runOnUiThread { repo.createProfile("Ella", BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) }
        compose.waitForIdle()

        compose.setContent {
            val profiles by repo.profiles.collectAsState()
            val activeProfileId by repo.activeProfileId.collectAsState()
            val content by repo.content.active.collectAsState()
            val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.first()
            LixoTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ProfileSelectAndTutorialScreen(
                        profiles = profiles,
                        activeProfile = activeProfile,
                        content = content,
                        sttEngine = remember { SilentSttEngine() },
                        ttsEngine = remember { createPlatformTextToSpeechEngine() },
                        showSwedishTtsButtons = false,
                        onSelectProfile = { repo.selectProfile(it.id) },
                        onCreateSiblingProfile = { repo.createProfile(it, BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) },
                        onTutorialCompleted = {}
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(LixoStrings.PROFILE_ADD_SIBLING.uppercase()).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("profile_name_field").performScrollTo().performTextInput("Ella ")
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.PROFILE_NAME_TAKEN).assertExists()
        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).performScrollTo().assertIsNotEnabled()
        shot("sibling_taken")

        compose.onNodeWithTag("profile_name_field").performTextClearance()
        compose.onNodeWithTag("profile_name_field").performTextInput("Elsa")
        compose.waitForIdle()
        compose.onNodeWithText(LixoStrings.PROFILE_NAME_TAKEN).assertDoesNotExist()
        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).performScrollTo().assertIsEnabled()

        compose.onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).performClick()
        compose.waitForIdle()

        assertEquals(setOf("Ella", "Elsa"), repo.profiles.value.map { it.displayName }.toSet())
    }
}
