package se.lixo.app.ios

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import se.lixo.app.ui.LixoApp
import se.lixo.app.ui.theme.LixoStrings
import kotlin.test.Test

/**
 * Smoke tests over the real [LixoApp], real platform STT/TTS engines, and the real bundled
 * content. Never taps [LixoStrings.TUTORIAL_ALLOW_MIC_BTN]: that would trigger an on-device mic
 * permission prompt, and the iosTest binary has no usage-description strings, so the OS aborts
 * the process (TCC) rather than showing the prompt.
 */
@OptIn(ExperimentalTestApi::class)
class IosSmokeUiTest {

    @Test
    fun releaseRepo_showsFirstProfileSetup() = runComposeUiTest {
        setContent { LixoApp(repository = iosRepo()) }
        waitForIdle()

        onNodeWithText(LixoStrings.FIRST_PROFILE_TITLE).assertExists()
        onNodeWithText(LixoStrings.FIRST_PROFILE_SUBTITLE).assertExists()
    }

    @Test
    fun createProfile_startsTutorial() = runComposeUiTest {
        setContent { LixoApp(repository = iosRepo()) }
        waitForIdle()

        onNode(hasSetTextAction()).performTextInput("Ella")
        onNodeWithText("7").performClick()
        onNodeWithText(LixoStrings.PROFILE_CREATE_BTN).assertIsEnabled().performClick()
        waitForIdle()

        onNodeWithText(LixoStrings.TUTORIAL_WELCOME_TITLE).assertExists()
    }
}
