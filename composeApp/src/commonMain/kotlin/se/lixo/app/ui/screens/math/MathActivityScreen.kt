package se.lixo.app.ui.screens.math

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
import se.lixo.app.ui.theme.BouncyButton
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.ParticleProgressBar
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Fullscreen Math & English Vocabulary Activity View (`project.md` §2 & §4):
 * - Covers Addition, Subtraction, Ten-Crossing, Multiplication, Division, Clock (`Klockan`),
 *   Money (`Pengar` in Swedish kronor), Word Problems, and English Glosor.
 * - Growth mindset: supportive visual breakdowns & step-by-step hints on wrong attempts.
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
                    promptSv = "Vad betyder det engelska ordet \"${item.englishWord}\" på svenska?\n(${item.simpleSentenceEn})",
                    correctAnswer = item.swedishTranslation,
                    choices = listOf(item.swedishTranslation, "sköld", "drake", "stjärna").distinct(),
                    hintStep1Sv = "Läs hela meningen: \"${item.simpleSentenceEn}\" = \"${item.simpleSentenceSv}\".",
                    hintStep2Sv = "Ordet \"${item.englishWord}\" betyder \"${item.swedishTranslation}\"!"
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
    var showEncouragementBanner by remember { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LixoColors.SkyBackgroundBottom)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        // Top Header with Close (X) and Particle Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LixoColors.CardSurfaceElevated)
                    .clickable { onCloseToMap() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = LixoColors.SoftTextWhite)
            }
            Spacer(Modifier.width(12.dp))
            ParticleProgressBar(
                progress = progress,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "⏱️ ${elapsedSeconds}s",
                style = MaterialTheme.typography.titleMedium,
                color = LixoColors.StarGold
            )
        }

        Spacer(Modifier.height(14.dp))

        if (currentAttempt != null && !allSolved) {
            val problem = currentAttempt.problem

            // Problem Prompt Card + Swedish TTS button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(LixoColors.CardSurface)
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEnglishTask) LixoStrings.ENGLISH_SECTION_TITLE
                        else LixoStrings.mathTypeName(problem.type),
                        style = MaterialTheme.typography.titleMedium,
                        color = LixoColors.StarGold
                    )
                    if (showSwedishTtsButtons) {
                        TtsIconButton(
                            textToSpeak = problem.promptSv,
                            ttsEngine = ttsEngine
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = problem.promptSv,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.SoftTextWhite
                )

                // Visual Manipulative: Analog Clock (`Klockan`)
                if (problem.visualClockHour != null && problem.visualClockMinute != null) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        AnalogClockFace(
                            hour = problem.visualClockHour,
                            minute = problem.visualClockMinute,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                }

                // Visual Manipulative: Swedish Kronor Coins/Notes (`Pengar`)
                if (problem.visualMoneyBreakdownKr.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        problem.visualMoneyBreakdownKr.forEach { kr ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (kr >= 20) Color(0xFF8E44AD) else Color(0xFFF39C12))
                                    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "$kr kr",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Growth Mindset Hint Drawer
            if (showEncouragementBanner || currentAttempt.activeHints.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(LixoColors.CardSurfaceElevated)
                        .border(2.dp, LixoColors.StarGold, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    if (showEncouragementBanner) {
                        Text(
                            text = "💡 ${LixoStrings.MATH_GROWTH_MINDSET_BANNER}",
                            style = MaterialTheme.typography.titleMedium,
                            color = LixoColors.StarGold
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    currentAttempt.activeHints.forEachIndexed { idx, hintText ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${idx + 1}. $hintText",
                                style = MaterialTheme.typography.bodyLarge,
                                color = LixoColors.SoftTextWhite,
                                modifier = Modifier.weight(1f)
                            )
                            if (showSwedishTtsButtons) {
                                Spacer(Modifier.width(8.dp))
                                TtsIconButton(
                                    textToSpeak = hintText,
                                    ttsEngine = ttsEngine
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Multiple-Choice Tactile Answer Buttons
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                problem.choices.forEach { choice ->
                    BouncyButton(
                        onClick = {
                            val (updatedState, isCorrect) = mathEngine.checkAnswer(currentAttempt, choice)
                            val copyList = attemptStates.toMutableList()
                            copyList[currentIndex] = updatedState
                            attemptStates = copyList

                            if (isCorrect) {
                                showEncouragementBanner = false
                                if (updatedState.wrongAttempts > 0 || updatedState.hintsRevealed > 0) {
                                    onLogMathError(
                                        problem.type,
                                        problem.promptSv,
                                        updatedState.wrongAttempts,
                                        updatedState.hintsRevealed
                                    )
                                }
                                if (currentIndex < attemptStates.size - 1) {
                                    currentIndex++
                                }
                            } else {
                                showEncouragementBanner = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = choice,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            BouncyButton(
                onClick = {
                    val copyList = attemptStates.toMutableList()
                    copyList[currentIndex] = mathEngine.requestHint(currentAttempt)
                    attemptStates = copyList
                },
                containerColor = LixoColors.SecondaryBlue,
                lipColor = LixoColors.SecondaryBlueLip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("💡 ${LixoStrings.MATH_HINT_BTN}", color = Color.White)
            }
        }

        // End-of-Level Celebration Card
        completedResult?.let { (score, completion) ->
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(LixoColors.CardSurfaceElevated)
                    .border(3.dp, LixoColors.StarGold, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = LixoStrings.ACTIVITY_FINISH_CELEBRATION_TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LixoColors.StarGold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = (1..3).joinToString(" ") { if (it <= score.starsEarned) "⭐" else "☆" },
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+${completion.netNewStarsAwarded} ${LixoStrings.MAP_STARS_LABEL}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LixoColors.SoftTextWhite
                )

                if (completion.newlyUnlockedItems.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    completion.newlyUnlockedItems.forEach { item ->
                        Text(
                            text = "🎁 ${item.nameSv} (${LixoStrings.rarityName(item.rarity)})",
                            style = MaterialTheme.typography.titleMedium,
                            color = LixoColors.PrimaryActionGreen
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                BouncyButton(
                    onClick = onCloseToMap,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(LixoStrings.ACTIVITY_BACK_TO_MAP_BTN, color = Color.White)
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
            color = Color(0xFF2C3E50),
            radius = radius,
            center = center,
            style = Stroke(width = 8f)
        )

        // 12 hour tick marks
        for (h in 0 until 12) {
            val angleRad = ((h * 30.0 - 90.0) * PI / 180.0).toFloat()
            val inner = radius * 0.80f
            val outer = radius * 0.92f
            drawLine(
                color = Color(0xFF2C3E50),
                start = Offset(center.x + cos(angleRad) * inner, center.y + sin(angleRad) * inner),
                end = Offset(center.x + cos(angleRad) * outer, center.y + sin(angleRad) * outer),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
        }

        // Hour hand
        val hourAngleRad = (((hour % 12 + minute / 60f) * 30f - 90f) * (PI / 180.0)).toFloat()
        drawLine(
            color = Color(0xFFE74C3C),
            start = center,
            end = Offset(
                center.x + cos(hourAngleRad) * radius * 0.50f,
                center.y + sin(hourAngleRad) * radius * 0.50f
            ),
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )

        // Minute hand
        val minAngleRad = ((minute * 6f - 90f) * (PI / 180.0)).toFloat()
        drawLine(
            color = Color(0xFF2980B9),
            start = center,
            end = Offset(
                center.x + cos(minAngleRad) * radius * 0.75f,
                center.y + sin(minAngleRad) * radius * 0.75f
            ),
            strokeWidth = 6f,
            cap = StrokeCap.Round
        )

        drawCircle(color = Color(0xFF2C3E50), radius = 6f, center = center)
    }
}
