package se.lixo.app.ui.components.celebration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

/**
 * Shared "Bana klar!" card used by both activity screens: animated stars, a counting-up "+N
 * stjärnor" line, caller-provided extra content (time rows, practice words, …), and the
 * back-to-map button. [CelebrationSequenceHost] runs above this for chests and unlock dialogs.
 */
@Composable
fun LevelCompleteCard(
    starsEarned: Int,
    netNewStars: Int,
    statsSuffix: String?,
    showDuplicateGiftNote: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    continueEnabled: Boolean = true,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    LixoCard(
        modifier = modifier,
        borderColor = LixoColors.Green,
        backgroundColor = LixoColors.GreenTint,
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = LixoStrings.ACTIVITY_FINISH_CELEBRATION_TITLE,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LixoColors.GreenLip
            )
            Spacer(Modifier.height(12.dp))
            AnimatedStarRow(starsEarned = starsEarned)
            Spacer(Modifier.height(8.dp))
            CountUpText(
                target = netNewStars,
                format = { n ->
                    val base = "+$n ${LixoStrings.MAP_STARS_LABEL}"
                    if (statsSuffix != null) "$base · $statsSuffix" else base
                },
                style = MaterialTheme.typography.titleMedium,
                color = LixoColors.TextPrimary
            )

            extraContent()

            if (showDuplicateGiftNote) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = LixoStrings.GIFT_DUPLICATE_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LixoColors.TextSecondary
                )
            }

            Spacer(Modifier.height(20.dp))
            LixoButton(
                onClick = onContinue,
                variant = LixoButtonVariant.PRIMARY,
                enabled = continueEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = LixoStrings.ACTIVITY_BACK_TO_MAP_BTN,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }
    }
}
