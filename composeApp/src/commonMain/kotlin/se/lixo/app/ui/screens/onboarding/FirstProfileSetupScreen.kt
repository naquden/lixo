package se.lixo.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Name input form shared by [FirstProfileSetupScreen] and the sibling-creation form in
 * [ProfileSelectAndTutorialScreen].
 */
@Composable
internal fun ProfileNameForm(
    name: String,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null
) {
    Column(modifier) {
        Text(
            text = LixoStrings.PROFILE_NEW_NAME_LABEL,
            style = MaterialTheme.typography.titleMedium,
            color = LixoColors.TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { onNameChange(it.take(BalanceConfig.PROFILE_NAME_MAX_LENGTH)) },
            placeholder = { Text(LixoStrings.PROFILE_NAME_PLACEHOLDER) },
            singleLine = true,
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LixoColors.Blue,
                unfocusedBorderColor = LixoColors.Border
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_name_field")
        )
    }
}

/**
 * First-run profile setup screen shown in release builds when there is no profile yet
 * (see [se.lixo.app.data.repository.LixoRepository] `isDebugBuild`). Collects a name and age,
 * then hands off to [onCreateProfile] which creates and activates the new profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstProfileSetupScreen(
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onCreateProfile: (name: String, ageYears: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ageYears by remember { mutableStateOf(BalanceConfig.PROFILE_DEFAULT_AGE_YEARS) }
    var submitted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = LixoStrings.FIRST_PROFILE_TITLE,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            if (showSwedishTtsButtons) {
                Spacer(Modifier.width(12.dp))
                TtsIconButton(
                    textToSpeak = "${LixoStrings.FIRST_PROFILE_TITLE} ${LixoStrings.FIRST_PROFILE_SUBTITLE}",
                    ttsEngine = ttsEngine
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = LixoStrings.FIRST_PROFILE_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = LixoColors.TextSecondary
        )

        Spacer(Modifier.height(32.dp))

        LixoCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp
        ) {
            Column {
                ProfileNameForm(name = name, onNameChange = { name = it })

                Spacer(Modifier.height(18.dp))
                Text(
                    text = LixoStrings.FIRST_PROFILE_AGE_LABEL,
                    style = MaterialTheme.typography.titleMedium,
                    color = LixoColors.TextPrimary
                )
                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (age in BalanceConfig.PROFILE_MIN_AGE_YEARS..BalanceConfig.PROFILE_MAX_AGE_YEARS) {
                        val selected = age == ageYears
                        LixoCard(
                            selected = selected,
                            cornerRadius = 12.dp,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            onClick = { ageYears = age }
                        ) {
                            Text(
                                text = "$age",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) LixoColors.Blue else LixoColors.TextPrimary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                LixoButton(
                    onClick = {
                        if (!submitted && name.isNotBlank()) {
                            submitted = true
                            onCreateProfile(name.trim(), ageYears)
                        }
                    },
                    enabled = name.isNotBlank() && !submitted,
                    variant = LixoButtonVariant.PRIMARY,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LixoStrings.PROFILE_CREATE_BTN,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}
