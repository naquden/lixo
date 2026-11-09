package se.lixo.app.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.lixo.app.domain.engine.AchievementEngine
import se.lixo.app.domain.model.Achievement
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.challenge.ChallengeCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings

/**
 * Trophies screen (doc/plans/014): one picture-first [ChallengeCard] per challenge chain,
 * showing the chain's current (lowest locked, or top if done) tier. [achievements] is expected
 * to already be overlaid with profile progress via [AchievementEngine.withProfileProgress].
 *
 * Deliberately a non-lazy `Column` + `verticalScroll` (NOT `LazyColumn`): every card, and every
 * ladder-tier tile inside it, must stay in the semantics tree for off-screen assertions/clicks in
 * instrumented tests. Cards are sorted: unfinished chains first (ordered by how close their
 * current tier is to unlocking), finished chains last, ties broken by [AchievementAction] order.
 *
 * The friends gallery and add-friend flow that used to live here as a second tab now live in
 * [AvatarMapScreen] ("Kompisbyn").
 */
@Composable
fun TrophiesScreen(achievements: List<Achievement>, ttsEngine: TextToSpeechEngine) {
    val chains = achievements
        .filter { it.action != null }
        .groupBy { it.action!! }
        .map { (action, defs) -> action to defs.sortedBy { it.tier } }
        .sortedWith(
            compareBy(
                { (_, tiers) -> tiers.all { it.isUnlocked } },
                { (_, tiers) ->
                    val cur = AchievementEngine.currentTier(tiers)
                    -(cur.currentCount.toFloat() / cur.targetCount.toFloat())
                },
                { (action, _) -> action.ordinal }
            )
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = LixoStrings.TROPHIES_TITLE,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.ExtraBold,
            color = LixoColors.TextPrimary
        )
        Text(
            text = LixoStrings.TROPHIES_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = LixoColors.TextSecondary
        )
        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chains.forEach { (_, tiers) ->
                ChallengeCard(tiers = tiers, ttsEngine = ttsEngine)
            }
        }
    }
}
