package se.lixo.app.ui.screens.math

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import se.lixo.app.domain.engine.LevelCompletionOutcome
import se.lixo.app.domain.engine.MathEngine
import se.lixo.app.domain.engine.MathProblemAttemptState
import se.lixo.app.domain.engine.MathSessionScoreResult
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.TaskType
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.theme.ChoiceCard
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoProgressBar
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Duolingo-inspired Math & English Activity Screen:
 * - Clean progress bar and ghost close button at top.
 * - Prominent typography on white surfaces with crisp visual contrast.
 * - Choice tiles that select (blue) before confirmation.
 * - Bottom tactile feedback bar (Green = Correct, Red = Growth Mindset hint).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MathActivityScreen(
    level: CampaignLevel,
    task: HomeworkTask,
    mathEngine: MathEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    onLogMathError: (MathProblemType, String, Int, Int) -> Unit,
    onFinishMathSession: (MathSessionScoreResult, Int) -> LevelCompletionOutcome,
    onCloseToMap: () -> Unit
) {
    val isEnglishTask = task.type == TaskType.ENGLISH_VOCAB
    val problems = remember(task) {
        if (isEnglishTask) {
            task.englishItems.map { item ->
                se.lixo.app.domain.model.MathProblem(
                    id = item.id,
                    type = MathProblemType.WORD_PROBLEM,
                    promptSv = "Vad betyder ordet \"${item.englishWord}\"?\n(${item.simpleSentenceEn})",
                    correctAnswer = item.swedishTranslation,
                    choices = listOf(item.swedishTranslation, "sköld", "drake", "stjärna").distinct(),
                    hintStep1Sv = "\"${item.simpleSentenceEn}\" = \"${item.simpleSentenceSv}\"",
                    hintStep2Sv = "\"${item.englishWord}\" betyder \"${item.swedishTranslation}\""
                )
            }
        } else {
            task.mathProblems
        }
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var attemptStates by remember(problems) {
        mutableStateOf(problems.map { MathProblemAttemptState(it) })
    }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var selectedChoice by remember { mutableStateOf<String?>(null) }
    var lastCheckResult by remember { mutableStateOf<Boolean?>(null) }
    var completedResult by remember { mutableStateOf<Pair<MathSessionScoreResult, LevelCompletionOutcome>?>(null) }

    val allSolved = attemptStates.all { it.isSolved }

    LaunchedEffect(allSolved) {
        while (!allSolved) {
            delay(1000L)
            elapsedSeconds++
        }
        if (allSolved && completedResult == null) {
            val score = mathEngine.evaluateMathSession(
                states = attemptStates,
                elapsedSeconds = elapsedSeconds,
                timeRequirementSeconds = level.timeRequirementSeconds,
                isTimedChallenge = true
            )
            val completion = onFinishMathSession(score, elapsedSeconds.coerceAtLeast(1))
            completedResult = score to completion
        }
    }

    val currentAttempt = attemptStates.getOrNull(currentIndex)
    val progress = if (attemptStates.isEmpty()) 1f else {
        attemptStates.count { it.isSolved }.toFloat() / attemptStates.size.toFloat()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (completedResult != null) 0.dp else 100.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top Bar: Ghost Close Button + Progress Bar + Timer Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onCloseToMap() },
                    contentAlignment = Alignment.Center
                ) {
                    LixoIcon(
                        icon = LixoIcons.Close,
                        tint = LixoColors.TextSecondary,
                        size = 22.dp
                    )
                }
                Spacer(Modifier.width(12.dp))
                LixoProgressBar(
                    progress = progress,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LixoColors.Surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    LixoIcon(icon = LixoIcons.Timer, tint = LixoColors.Orange, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${elapsedSeconds}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = LixoColors.Orange
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            if (currentAttempt != null && !allSolved) {
                val problem = currentAttempt.problem

                // Category pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEnglishTask) LixoStrings.ENGLISH_SECTION_TITLE
                        else LixoStrings.mathTypeName(problem.type).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LixoColors.Blue
                    )
                    if (showSwedishTtsButtons) {
                        TtsIconButton(
                            textToSpeak = problem.promptSv,
                            ttsEngine = ttsEngine
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Problem Question Prompt
                Text(
                    text = problem.promptSv,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = LixoColors.TextPrimary
                )

                // Visual Manipulative: Clock Face
                if (problem.visualClockHour != null && problem.visualClockMinute != null) {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(LixoColors.Surface)
                            .border(1.5.dp, LixoColors.Border, RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnalogClockFace(
                            hour = problem.visualClockHour,
                            minute = problem.visualClockMinute,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                }

                // Visual Manipulative: Swedish Kronor
                if (problem.visualMoneyBreakdownKr.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        problem.visualMoneyBreakdownKr.forEach { kr ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (kr >= 20) LixoColors.PurpleTint else LixoColors.GoldTint)
                                    .border(
                                        1.5.dp,
                                        if (kr >= 20) LixoColors.PurpleLip else LixoColors.GoldLip,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "$kr kr",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (kr >= 20) LixoColors.PurpleLip else LixoColors.GoldLip
                                )
                            }
                        }
                    }
                }

                // Active Hints
                if (currentAttempt.activeHints.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    LixoCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = LixoColors.GoldTint,
                        borderColor = LixoColors.GoldLip,
                        cornerRadius = 14.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LixoIcon(icon = LixoIcons.Bulb, tint = LixoColors.GoldLip, size = 18.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Ledtråd",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LixoColors.GoldLip
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            currentAttempt.activeHints.forEachIndexed { idx, hintText ->
                                Text(
                                    text = "${idx + 1}. $hintText",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LixoColors.TextPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Multiple Choice Answers
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    problem.choices.forEachIndexed { index, choice ->
                        val isSelected = selectedChoice == choice
                        ChoiceCard(
                            text = choice,
                            selected = isSelected,
                            badgeNumber = "${index + 1}",
                            onClick = {
                                if (lastCheckResult == null) {
                                    selectedChoice = choice
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Request Hint Text Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    LixoButton(
                        onClick = {
                            val copyList = attemptStates.toMutableList()
                            copyList[currentIndex] = mathEngine.requestHint(currentAttempt)
                            attemptStates = copyList
                        },
                        variant = LixoButtonVariant.GHOST
                    ) {
                        LixoIcon(icon = LixoIcons.Bulb, tint = LixoColors.Blue, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = LixoStrings.MATH_HINT_BTN,
                            style = MaterialTheme.typography.labelMedium,
                            color = LixoColors.Blue
                        )
                    }
                }
            }

            // End of Session Celebration
            completedResult?.let { (score, completion) ->
                Spacer(Modifier.height(24.dp))
                LixoCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = LixoColors.Green,
                    backgroundColor = LixoColors.GreenTint,
                    cornerRadius = 24.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
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
                        Row(horizontalArrangement = Arrangement.Center) {
                            for (i in 1..3) {
                                LixoIcon(
                                    icon = LixoIcons.Star,
                                    tint = if (i <= score.starsEarned) LixoColors.Gold else LixoColors.BorderDarker,
                                    size = 36.dp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "+${completion.netNewStarsAwarded} ${LixoStrings.MAP_STARS_LABEL}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LixoColors.TextPrimary
                        )

                        if (completion.newlyUnlockedItems.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            completion.newlyUnlockedItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.GreenLip, size = 20.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${item.nameSv} (${LixoStrings.rarityName(item.rarity)})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = LixoColors.GreenLip
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        LixoButton(
                            onClick = onCloseToMap,
                            variant = LixoButtonVariant.PRIMARY,
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
        }

        // Duolingo-style Bottom Action / Feedback Sheet
        if (currentAttempt != null && !allSolved && completedResult == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        when (lastCheckResult) {
                            true -> LixoColors.GreenTint
                            false -> LixoColors.RedTint
                            null -> LixoColors.Background
                        }
                    )
                    .border(
                        1.dp,
                        when (lastCheckResult) {
                            true -> LixoColors.GreenLip
                            false -> LixoColors.RedLip
                            null -> LixoColors.Border
                        }
                    )
                    .padding(16.dp)
            ) {
                Column {
                    if (lastCheckResult == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LixoIcon(icon = LixoIcons.Check, tint = LixoColors.GreenLip, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Snyggt jobbat!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.GreenLip
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LixoButton(
                            onClick = {
                                lastCheckResult = null
                                selectedChoice = null
                                if (currentIndex < attemptStates.size - 1) {
                                    currentIndex++
                                }
                            },
                            variant = LixoButtonVariant.PRIMARY,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "FORTSÄTT",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    } else if (lastCheckResult == false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LixoIcon(icon = LixoIcons.Close, tint = LixoColors.RedLip, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Inte riktigt — prova igen!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = LixoColors.RedLip
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LixoButton(
                            onClick = {
                                lastCheckResult = null
                                selectedChoice = null
                            },
                            variant = LixoButtonVariant.DANGER,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "FÖRSÖK IGEN",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    } else {
                        // "KONTROLLERA" (Check) button
                        LixoButton(
                            onClick = {
                                val choice = selectedChoice ?: return@LixoButton
                                val (updatedState, isCorrect) = mathEngine.checkAnswer(currentAttempt, choice)
                                val copyList = attemptStates.toMutableList()
                                copyList[currentIndex] = updatedState
                                attemptStates = copyList
                                lastCheckResult = isCorrect

                                if (isCorrect) {
                                    if (updatedState.wrongAttempts > 0 || updatedState.hintsRevealed > 0) {
                                        onLogMathError(
                                            currentAttempt.problem.type,
                                            currentAttempt.problem.promptSv,
                                            updatedState.wrongAttempts,
                                            updatedState.hintsRevealed
                                        )
                                    }
                                }
                            },
                            enabled = selectedChoice != null,
                            variant = LixoButtonVariant.PRIMARY,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = LixoStrings.MATH_SUBMIT_BTN.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalogClockFace(
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = Color.White, radius = radius, center = center)
        drawCircle(
            color = LixoColors.BorderDarker,
            radius = radius,
            center = center,
            style = Stroke(width = 6f)
        )

        for (h in 0 until 12) {
            val angleRad = ((h * 30.0 - 90.0) * PI / 180.0).toFloat()
            val inner = radius * 0.82f
            val outer = radius * 0.92f
            drawLine(
                color = LixoColors.TextSecondary,
                start = Offset(center.x + cos(angleRad) * inner, center.y + sin(angleRad) * inner),
                end = Offset(center.x + cos(angleRad) * outer, center.y + sin(angleRad) * outer),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        val hourAngleRad = (((hour % 12 + minute / 60f) * 30f - 90f) * (PI / 180.0)).toFloat()
        drawLine(
            color = LixoColors.Red,
            start = center,
            end = Offset(
                center.x + cos(hourAngleRad) * radius * 0.50f,
                center.y + sin(hourAngleRad) * radius * 0.50f
            ),
            strokeWidth = 8f,
            cap = StrokeCap.Round
        )

        val minAngleRad = ((minute * 6f - 90f) * (PI / 180.0)).toFloat()
        drawLine(
            color = LixoColors.Blue,
            start = center,
            end = Offset(
                center.x + cos(minAngleRad) * radius * 0.75f,
                center.y + sin(minAngleRad) * radius * 0.75f
            ),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )

        drawCircle(color = LixoColors.TextPrimary, radius = 5f, center = center)
    }
}
