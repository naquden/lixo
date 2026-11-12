package se.lixo.app.ui.screens.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import se.lixo.app.domain.engine.ProgressionAndGiftEngine
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.ChildProfile
import se.lixo.app.domain.model.LevelProgress
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.content.ActiveContent
import se.lixo.app.domain.content.ContentEvent
import se.lixo.app.platform.TextToSpeechEngine
import se.lixo.app.ui.components.EventBanner
import se.lixo.app.ui.components.EventItemsSheet
import se.lixo.app.ui.components.avatar.AvatarViewport
import se.lixo.app.ui.components.celebration.FlitChestMeter
import se.lixo.app.ui.theme.LixoBottomSheet
import se.lixo.app.ui.theme.LixoButton
import se.lixo.app.ui.theme.LixoButtonVariant
import se.lixo.app.ui.theme.LixoCard
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcon
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoSpacing
import se.lixo.app.ui.theme.LixoStrings
import se.lixo.app.ui.theme.StatPill
import se.lixo.app.ui.theme.TtsIconButton

/**
 * Prefix for the semantics testTag on each [CampaignLevelNode], used by instrumented tests to
 * locate a specific level node and assert its locked/unlocked state (doc/plans/010).
 */
const val MAP_LEVEL_NODE_TAG_PREFIX = "map_level_node_"

/**
 * World-local index to auto-scroll a [WorldPage] to: the position of [currentLevelId] in
 * [levels], or 0 when there's no progress yet or the id isn't found (doc/plans/046).
 */
internal fun mapScrollTargetIndex(levels: List<CampaignLevel>, currentLevelId: String?): Int =
    levels.indexOfFirst { it.id == currentLevelId }.coerceAtLeast(0)

/**
 * Duolingo-style Campaign Map with four subject "worlds" (plan: "Ämnesväljare med fyra världar"):
 * - Clean top HUD with avatar badge, stars, and streak.
 * - WorldSelector: tactile 3D world picker synced with a HorizontalPager.
 * - Per-world snaking path of circular 3D lesson nodes with stars, locks, and mascot placement.
 * - Interactive bottom sheet popup on node tap for starting the level without cluttering the map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CampaignMapScreen(
    profile: ChildProfile,
    levels: List<CampaignLevel>,
    progressByLevelId: Map<String, LevelProgress>,
    content: ActiveContent,
    progressionEngine: ProgressionAndGiftEngine,
    ttsEngine: TextToSpeechEngine,
    showSwedishTtsButtons: Boolean,
    selectedSubject: Subject,
    onSelectSubject: (Subject) -> Unit,
    onSelectLevel: (CampaignLevel) -> Unit,
    onSwitchProfileClick: () -> Unit,
    activeEvents: List<ContentEvent> = emptyList(),
    unlockedItemIds: Set<String> = emptySet()
) {
    var selectedLevelForSheet by remember { mutableStateOf<CampaignLevel?>(null) }
    var showChestInfo by remember { mutableStateOf(false) }
    var showEventSheet by remember { mutableStateOf(false) }
    val bannerEvent = activeEvents.firstOrNull { it.banner != null }

    val worlds = SubjectWorlds
    val initialPage = worlds.indexOfFirst { it.subject == selectedSubject }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { worlds.size }

    // Keep pager in sync when the selected subject changes from outside (e.g. after finishing a level).
    LaunchedEffect(selectedSubject) {
        val targetPage = worlds.indexOfFirst { it.subject == selectedSubject }
        if (targetPage >= 0 && targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Report the settled page back up so LixoApp always knows the active world.
    LaunchedEffect(pagerState.settledPage) {
        val settledWorld = worlds.getOrNull(pagerState.settledPage)?.subject
        if (settledWorld != null && settledWorld != selectedSubject) {
            onSelectSubject(settledWorld)
        }
    }

    val currentWorld = worlds.getOrNull(pagerState.currentPage) ?: worlds.first()
    val animatedBackground by animateColorAsState(
        targetValue = currentWorld.tintColor,
        animationSpec = tween(durationMillis = 400),
        label = "lixo_map_background"
    )

    // Track which worlds have already played their staggered node pop-in once settled on.
    val worldsAlreadyRevealed = remember { mutableStateMapOf<Subject, Boolean>() }
    LaunchedEffect(pagerState.settledPage) {
        val settledSubject = worlds.getOrNull(pagerState.settledPage)?.subject
        if (settledSubject != null) {
            worldsAlreadyRevealed[settledSubject] = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top HUD Bar: Avatar profile selector (left) | Stars, Streak & Flitkista (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LixoColors.Background)
                    .border(width = 1.dp, color = LixoColors.Border)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(LixoColors.Surface)
                        .border(1.dp, LixoColors.Border, RoundedCornerShape(14.dp))
                        .clickable { onSwitchProfileClick() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    LixoIcon(icon = LixoIcons.Person, tint = LixoColors.Blue, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LixoColors.TextPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill(
                        icon = LixoIcons.Star,
                        value = "${profile.totalStars}",
                        tint = LixoColors.GoldLip
                    )
                    StatPill(
                        icon = LixoIcons.Flame,
                        value = "${profile.currentStreakDays}",
                        tint = LixoColors.Orange
                    )
                    FlitChestMeter(
                        halfPoints = profile.chestProgressHalfPoints,
                        thresholdHalf = content.dropRules.chestCostHalfPoints,
                        size = 32.dp,
                        onClick = { showChestInfo = true }
                    )
                }
            }

            bannerEvent?.let { event ->
                EventBanner(
                    content = content,
                    event = event,
                    onClick = { showEventSheet = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // World selector: three tactile 3D "buttons" with a sliding indicator pill.
            WorldSelector(
                worlds = worlds,
                selectedIndex = pagerState.currentPage,
                levels = levels,
                progressByLevelId = progressByLevelId,
                onWorldClick = { index ->
                    onSelectSubject(worlds[index].subject)
                }
            )

            // Header card for the active world - animates color + title on swipe.
            LixoCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                backgroundColor = currentWorld.faceColor,
                borderColor = currentWorld.lipColor,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${LixoStrings.WORLD_SELECTOR_HEADER_PREFIX} ${pagerState.currentPage + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.height(2.dp))
                        AnimatedContent(
                            targetState = currentWorld.title,
                            transitionSpec = {
                                (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                            },
                            label = "lixo_world_title"
                        ) { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = currentWorld.tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    if (showSwedishTtsButtons) {
                        TtsIconButton(
                            textToSpeak = "${LixoStrings.WORLD_SELECTOR_HEADER_PREFIX} ${pagerState.currentPage + 1}: ${currentWorld.title}",
                            ttsEngine = ttsEngine,
                            tint = currentWorld.faceColor
                        )
                    }
                }
            }

            // Swipeable world pages.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val world = worlds[page]
                val pageOffset = pagerState.currentPageOffsetFraction + (pagerState.currentPage - page)
                val alreadyRevealed = worldsAlreadyRevealed[world.subject] == true

                WorldPage(
                    world = world,
                    levels = levels.filter { it.subject == world.subject }.sortedBy { it.order },
                    progressByLevelId = progressByLevelId,
                    progressionEngine = progressionEngine,
                    profile = profile,
                    content = content,
                    isActivePage = pagerState.settledPage == page,
                    playPopInAnimation = !alreadyRevealed,
                    pageOffset = pageOffset,
                    onNodeClick = { level -> selectedLevelForSheet = level }
                )
            }
        }

        // Duolingo-style Level Details Bottom Sheet
        LixoBottomSheet(
            visible = selectedLevelForSheet != null,
            onDismiss = { selectedLevelForSheet = null },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedLevelForSheet?.let { level ->
                val progress = progressByLevelId[level.id]
                val stars = progress?.starsEarned ?: 0

                // Header row with title + close button
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = level.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = LixoColors.TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${LixoStrings.subjectName(level.subject)} · ${level.timeRequirementSeconds}s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showSwedishTtsButtons) {
                            TtsIconButton(
                                textToSpeak = level.title,
                                ttsEngine = ttsEngine
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(LixoColors.Surface)
                                .clickable { selectedLevelForSheet = null },
                            contentAlignment = Alignment.Center
                        ) {
                            LixoIcon(
                                icon = LixoIcons.Close,
                                tint = LixoColors.TextSecondary,
                                size = 18.dp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Star rating & badges row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(LixoColors.Surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (i in 1..3) {
                            LixoIcon(
                                icon = LixoIcons.Star,
                                tint = if (i <= stars) LixoColors.Gold else LixoColors.BorderDarker,
                                size = 22.dp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    if (progress?.completedTimedChallenge == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LixoIcon(icon = LixoIcons.Bolt, tint = LixoColors.Orange, size = 18.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = LixoStrings.MAP_TIMED_BADGE,
                                style = MaterialTheme.typography.labelSmall,
                                color = LixoColors.Orange
                            )
                        }
                    }
                }

                // Replay reward hint
                if (stars == 3) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.GoldLip, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = LixoStrings.MAP_MAXED_STARS_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.TextSecondary
                        )
                    }
                } else if (stars in 1..2) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LixoIcon(icon = LixoIcons.Gift, tint = LixoColors.Green, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = LixoStrings.MAP_REPLAY_GIFT_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LixoColors.GreenLip
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Big Tactile Start Button
                LixoButton(
                    onClick = {
                        selectedLevelForSheet = null
                        onSelectLevel(level)
                    },
                    variant = if (stars > 0) LixoButtonVariant.SECONDARY else LixoButtonVariant.PRIMARY,
                    height = 54.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (stars > 0) "SPELA IGEN" else "STARTA BANA",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }

        // Flitkista tooltip sheet, opened by tapping the HUD chest meter.
        LixoBottomSheet(
            visible = showChestInfo,
            onDismiss = { showChestInfo = false },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlitChestMeter(
                    halfPoints = profile.chestProgressHalfPoints,
                    thresholdHalf = content.dropRules.chestCostHalfPoints,
                    size = 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = LixoStrings.CHEST_METER_INFO_TITLE,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = LixoColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = LixoStrings.CHEST_METER_INFO,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LixoColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            LixoButton(
                onClick = { showChestInfo = false },
                variant = LixoButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = LixoStrings.SHEET_CLOSE_BTN,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }

        bannerEvent?.let { event ->
            EventItemsSheet(
                visible = showEventSheet,
                content = content,
                event = event,
                unlockedItemIds = unlockedItemIds,
                onDismiss = { showEventSheet = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Three tactile 3D "buttons", one per world, with a sliding selection indicator pill and
 * per-world star progress. The selected card scales up with a spring and bounces its icon.
 */
@Composable
private fun WorldSelector(
    worlds: List<SubjectWorld>,
    selectedIndex: Int,
    levels: List<CampaignLevel>,
    progressByLevelId: Map<String, LevelProgress>,
    onWorldClick: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val totalGapWidth = 8.dp * (worlds.size - 1)
        val cardWidth = (maxWidth - totalGapWidth) / worlds.size
        val indicatorOffset by animateDpAsState(
            targetValue = (cardWidth + 8.dp) * selectedIndex,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "lixo_world_indicator"
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            // Sliding indicator pill underneath the selected card.
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(cardWidth)
                    .height(86.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(LixoColors.Border.copy(alpha = 0.25f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                worlds.forEachIndexed { index, world ->
                    val totalStarsPossible = levels.count { it.subject == world.subject } * 3
                    val starsEarned = levels
                        .filter { it.subject == world.subject }
                        .sumOf { progressByLevelId[it.id]?.starsEarned ?: 0 }

                    WorldSelectorCard(
                        world = world,
                        isSelected = index == selectedIndex,
                        starsEarned = starsEarned,
                        totalStarsPossible = totalStarsPossible,
                        modifier = Modifier.weight(1f),
                        onClick = { onWorldClick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldSelectorCard(
    world: SubjectWorld,
    isSelected: Boolean,
    starsEarned: Int,
    totalStarsPossible: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "lixo_world_card_scale"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "lixo_world_icon_bounce"
    )
    val pressOffset = if (isPressed) 2.dp else 0.dp

    val faceColor = if (isSelected) world.faceColor else LixoColors.Surface
    val lipColor = if (isSelected) world.lipColor else LixoColors.BorderDarker
    val iconTint = if (isSelected) Color.White else LixoColors.TextSecondary
    val textColor = if (isSelected) Color.White else LixoColors.TextSecondary

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .offset(y = pressOffset)
            .clip(RoundedCornerShape(18.dp))
            .background(lipColor)
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(faceColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LixoIcon(
                icon = world.icon,
                tint = iconTint,
                size = 26.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = world.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$starsEarned/$totalStarsPossible ⭐",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * One page of the HorizontalPager: a world's snaking path of lesson nodes.
 * Applies a subtle parallax/3D page-transition effect via [pageOffset].
 */
@Composable
private fun WorldPage(
    world: SubjectWorld,
    levels: List<CampaignLevel>,
    progressByLevelId: Map<String, LevelProgress>,
    progressionEngine: ProgressionAndGiftEngine,
    profile: ChildProfile,
    content: ActiveContent,
    isActivePage: Boolean,
    playPopInAnimation: Boolean,
    pageOffset: Float,
    onNodeClick: (CampaignLevel) -> Unit
) {
    val currentLevel = remember(levels, progressByLevelId) {
        progressionEngine.currentLevelForSubject(
            subject = world.subject,
            levels = levels,
            progressByLevelId = progressByLevelId
        )
    }
    val targetIndex = mapScrollTargetIndex(levels, currentLevel?.id)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (targetIndex - 1).coerceAtLeast(0))

    // Auto-scroll to the next available level whenever this page becomes active (first open,
    // world switch, or returning from a level with new progress). Keyed only on isActivePage
    // and the target id so a manual scroll never snaps back (doc/plans/046).
    LaunchedEffect(isActivePage, currentLevel?.id) {
        if (!isActivePage || levels.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val info = listState.layoutInfo
        val viewport = info.viewportSize.height
        val itemSize = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
            ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
        listState.animateScrollToItem(targetIndex, scrollOffset = -(viewport / 2 - itemSize / 2))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)
                scaleY = 1f - (absOffset * 0.1f)
                alpha = 1f - (absOffset * 0.5f)
                rotationY = pageOffset * -12f
                cameraDistance = 12f * density
            },
        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        itemsIndexed(levels, key = { _, level -> level.id }) { localIndex, level ->
            val isUnlocked = progressionEngine.isLevelUnlocked(
                levelOrder = level.order,
                progressByLevelId = progressByLevelId,
                levels = levels
            )
            val progress = progressByLevelId[level.id]
            val stars = progress?.starsEarned ?: 0
            val isCurrentActiveNode = level.id == currentLevel?.id

            // Gentle sine wave offset (max +/- 56dp), numbered locally within this world's path.
            val waveOffsetDp = (sin(localIndex * 0.7) * 56.0).toInt().dp

            // Decide once, at first composition of this node, whether it should stagger-pop-in.
            // Keying only on level.id (not on playPopInAnimation) stops the effect from restarting
            // and snapping all nodes to full scale when the world "settles" mid-animation.
            val shouldStagger = remember(level.id) { playPopInAnimation }
            val popInScale = remember(level.id) { mutableStateOf(if (shouldStagger) 0f else 1f) }
            val animatedPopInScale by animateFloatAsState(
                targetValue = popInScale.value,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "lixo_node_pop_in"
            )
            LaunchedEffect(level.id) {
                if (shouldStagger) {
                    val firstVisible = listState.firstVisibleItemIndex
                    delay(((localIndex - firstVisible).coerceAtLeast(0) * 35L).coerceAtMost(900L))
                    popInScale.value = 1f
                } else {
                    popInScale.value = 1f
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = waveOffsetDp)
                    .graphicsLayer {
                        scaleX = animatedPopInScale
                        scaleY = animatedPopInScale
                    },
                contentAlignment = Alignment.Center
            ) {
                // Current level mascot placed beside the active node
                if (isCurrentActiveNode && isActivePage) {
                    Box(
                        modifier = Modifier
                            .align(if (waveOffsetDp >= 0.dp) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 8.dp)
                    ) {
                        AvatarViewport(
                            content = content,
                            appearance = profile.appearance,
                            modifier = Modifier.size(90.dp),
                            interactiveRotation = false
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // "START" Speech Bubble above active node
                    if (isCurrentActiveNode) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(LixoColors.Surface)
                                .border(2.dp, world.faceColor, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "START",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = world.lipColor
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // 3D Circular Level Node
                    CampaignLevelNode(
                        level = level,
                        world = world,
                        number = localIndex + 1,
                        isUnlocked = isUnlocked,
                        isCurrent = isCurrentActiveNode,
                        stars = stars,
                        onClick = {
                            if (isUnlocked) {
                                onNodeClick(level)
                            }
                        }
                    )

                    // Stars under completed node
                    if (isUnlocked && stars > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 1..3) {
                                LixoIcon(
                                    icon = LixoIcons.Star,
                                    tint = if (i <= stars) LixoColors.Gold else LixoColors.BorderDarker,
                                    size = 14.dp,
                                    modifier = Modifier.padding(horizontal = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Circular 72dp 3D lesson node with distinct bottom lip. Pulses gently while it's the active
 * node. Shows the world-local level [number]: a shaded 3D digit on unlocked nodes (replacing the
 * subject icon), or a lock plus a small flat number on locked nodes (doc/plans/046). The node's
 * content description is "Bana N" (plus ", låst" when locked); descendant number text is cleared
 * from the merged tree so TalkBack reads it once, not twice (doc/plans/047).
 */
@Composable
private fun CampaignLevelNode(
    level: CampaignLevel,
    world: SubjectWorld,
    number: Int,
    isUnlocked: Boolean,
    isCurrent: Boolean,
    stars: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset = if (isPressed && isUnlocked) 4.dp else 0.dp

    val (faceColor, lipColor, iconColor) = when {
        !isUnlocked -> Triple(LixoColors.LockedFace, LixoColors.LockedLip, LixoColors.TextDisabled)
        stars == 3 -> Triple(LixoColors.Gold, LixoColors.GoldLip, Color.White)
        isCurrent -> Triple(world.faceColor, world.lipColor, Color.White)
        else -> when (level.subject) {
            Subject.SWEDISH -> Triple(LixoColors.Green, LixoColors.GreenLip, Color.White)
            Subject.MATH -> Triple(LixoColors.Blue, LixoColors.BlueLip, Color.White)
            Subject.ENGLISH -> Triple(LixoColors.Purple, LixoColors.PurpleLip, Color.White)
            Subject.GEOGRAPHY -> Triple(LixoColors.Orange, LixoColors.OrangeLip, Color.White)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "lixo_node_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCurrent && isUnlocked) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lixo_node_pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .testTag(MAP_LEVEL_NODE_TAG_PREFIX + level.id)
            .clearAndSetSemantics {
                contentDescription = if (isUnlocked) {
                    "${LixoStrings.MAP_LEVEL_NODE_CD_PREFIX} $number"
                } else {
                    "${LixoStrings.MAP_LEVEL_NODE_CD_PREFIX} $number, ${LixoStrings.MAP_LEVEL_NODE_LOCKED_SUFFIX}"
                }
                if (isUnlocked) {
                    onClick(label = null) { onClick.invoke(); true }
                } else {
                    disabled()
                }
            }
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .clip(CircleShape)
            .background(lipColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = isUnlocked,
                onClick = onClick
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .offset(y = pressOffset)
                .padding(bottom = 5.dp)
                .clip(CircleShape)
                .background(faceColor),
            contentAlignment = Alignment.Center
        ) {
            if (!isUnlocked) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    LixoIcon(
                        icon = LixoIcons.Lock,
                        tint = iconColor,
                        size = 24.dp
                    )
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = LixoColors.TextDisabled,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                Number3D(number = number, faceColor = faceColor, lipColor = lipColor)
            }
        }
    }
}

/**
 * The level number drawn as a shaded 3D block to match the node's own lip/face style, built
 * from layered [Text]s so it works on both Android and iOS (no AGSL/RuntimeShader,
 * doc/plans/046).
 */
@Composable
private fun Number3D(
    number: Int,
    faceColor: Color,
    lipColor: Color,
    modifier: Modifier = Modifier
) {
    val text = number.toString()
    val fontSize = if (number >= 100) 24.sp else 30.sp
    val base = TextStyle(
        fontSize = fontSize,
        lineHeight = fontSize,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center
    )
    val extrusionColor = lerp(lipColor, Color.Black, 0.25f)

    Box(modifier, contentAlignment = Alignment.Center) {
        for (dy in listOf(3.dp, 2.dp, 1.dp)) {
            Text(
                text = text,
                style = base,
                color = extrusionColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(y = dy).clearAndSetSemantics {}
            )
        }
        Text(
            text = text,
            style = base.copy(
                brush = Brush.verticalGradient(listOf(Color.White, lerp(Color.White, faceColor, 0.35f))),
                shadow = Shadow(color = Color.Black.copy(alpha = 0.25f), offset = Offset(0f, 6f), blurRadius = 8f)
            ),
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = text,
            style = base.copy(
                brush = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.45f to Color.Transparent)
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}
