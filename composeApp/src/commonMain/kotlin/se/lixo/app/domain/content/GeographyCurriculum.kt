package se.lixo.app.domain.content

import kotlin.random.Random
import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.CampaignLevel
import se.lixo.app.domain.model.FlagQuestion
import se.lixo.app.domain.model.HomeworkTask
import se.lixo.app.domain.model.Subject
import se.lixo.app.domain.model.TaskType

/**
 * Static, deterministic curriculum for the 30 geography campaign levels ("Världsresan", plan
 * 037), occupying orders 161..190 — right after the 160-order math/Swedish/English campaign (see
 * [se.lixo.app.domain.content.MathCurriculum] / [se.lixo.app.domain.model.BalanceConfig.CAMPAIGN_MAX_ORDER]).
 *
 * Each level introduces a chunk of new countries (fame order, see [GeographyCountries.RANKED])
 * plus review countries from recent levels, totalling [QUESTIONS_PER_LEVEL] questions. Every
 * question is a `FlagQuestion` with 3 deterministic distractors, harder (look-alike flags) at
 * higher `difficulty`. All randomness is seeded by `order` (and question position), so rebuilding
 * the curriculum always yields byte-identical results — this is a code-generated curriculum, not
 * read from `seed_campaign_content.json`.
 */
object GeographyCurriculum {
    const val LEVEL_COUNT = 30
    const val QUESTIONS_PER_LEVEL = 8
    const val FIRST_ORDER = 161
    const val COURSE_ID = "course_geo_1"
    const val BIOME_SV = "Världsresan"

    /** Number of newly introduced countries per level (1..30), summing to 194. */
    private val CHUNK_SIZES: List<Int> = listOf(8) + List(12) { 7 } + List(17) { 6 }

    private val CHUNK_START: List<Int> = CHUNK_SIZES.runningFold(0) { acc, size -> acc + size }

    init {
        require(CHUNK_SIZES.sum() == GeographyCountries.RANKED.size) {
            "chunk sizes must cover all ${GeographyCountries.RANKED.size} countries"
        }
    }

    fun orderForIndex(idx: Int): Int = FIRST_ORDER - 1 + idx

    fun difficulty(idx: Int): Int = (idx - 1) / 5 + 1

    /** The countries newly introduced at level [idx] (1-based), in [GeographyCountries.RANKED] order. */
    fun newCountries(idx: Int): List<Country> {
        val start = CHUNK_START[idx - 1]
        val size = CHUNK_SIZES[idx - 1]
        return GeographyCountries.RANKED.subList(start, start + size)
    }

    /** All countries introduced up to and including level [idx]. */
    fun knownPool(idx: Int): List<Country> = GeographyCountries.RANKED.subList(0, CHUNK_START[idx])

    /**
     * Builds the [QUESTIONS_PER_LEVEL] deterministic [FlagQuestion]s for level [idx]. Questions
     * cover every new country at this level, filled out with review countries from the previous
     * 3 levels (or the whole earlier pool if that isn't enough). Distractors get harder with
     * [difficulty]: easy levels avoid look-alike flags entirely, hard levels deliberately include
     * 1-2 look-alikes. Everything is seeded off `order` so the result is fully deterministic.
     */
    fun buildQuestions(idx: Int): List<FlagQuestion> {
        val order = orderForIndex(idx)
        val d = difficulty(idx)
        val news = newCountries(idx)
        val known = knownPool(idx)

        val reviewCount = QUESTIONS_PER_LEVEL - news.size
        val reviewPoolStartLevel = maxOf(1, idx - 3)
        var reviewCandidates = if (idx > 1) {
            ((reviewPoolStartLevel until idx).flatMap { newCountries(it) })
        } else {
            emptyList()
        }
        if (reviewCandidates.size < reviewCount) {
            reviewCandidates = knownPool(maxOf(1, idx - 1))
        }
        val reviews = reviewCandidates
            .filter { it.iso2 !in news.map { n -> n.iso2 } }
            .shuffled(Random(order))
            .distinctBy { it.iso2 }
            .take(reviewCount)

        val preShuffle = news + reviews

        val questions = preShuffle.mapIndexed { i, country ->
            val rnd = Random(order * 100 + i)
            val lookalikes = GeographyCountries.lookalikesOf(country.iso2)

            fun pick(pool: List<Country>, n: Int, already: Set<String>): List<String> =
                pool
                    .filter { it.iso2 != country.iso2 && it.iso2 !in already }
                    .map { it.iso2 }
                    .distinct()
                    .shuffled(rnd)
                    .take(n)

            val chosen = mutableListOf<String>()
            when {
                d <= 2 -> {
                    chosen += pick(known.filter { it.iso2 !in lookalikes }, 3, chosen.toSet())
                }
                d in 3..4 -> {
                    chosen += pick(known, 3, chosen.toSet())
                }
                else -> {
                    val hardN = if (d == 5) 1 else 2
                    val hardPool = if (lookalikes.isNotEmpty()) {
                        GeographyCountries.RANKED.filter { it.iso2 in lookalikes }
                    } else {
                        GeographyCountries.RANKED.filter { it.region == country.region }
                    }
                    chosen += pick(hardPool, hardN, chosen.toSet())
                    chosen += pick(known, 3 - chosen.size, chosen.toSet())
                }
            }

            var topUpAttempt = 0
            while (chosen.size < 3 && topUpAttempt < 4) {
                val extra = if (d <= 2) {
                    pick(GeographyCountries.RANKED.filter { it.iso2 !in lookalikes }, 3 - chosen.size, chosen.toSet())
                } else {
                    val fromKnown = pick(known, 3 - chosen.size, chosen.toSet())
                    if (fromKnown.isEmpty()) {
                        pick(GeographyCountries.RANKED, 3 - chosen.size, chosen.toSet())
                    } else {
                        fromKnown
                    }
                }
                if (extra.isEmpty()) break
                chosen += extra
                topUpAttempt++
            }

            country to chosen.take(3)
        }

        val shuffled = questions.shuffled(Random(order + 7_000_000))
        return shuffled.mapIndexed { pos, (country, distractors) ->
            FlagQuestion(
                id = "geo_${order}_$pos",
                countryIso2 = country.iso2,
                distractorIso2 = distractors
            )
        }
    }

    fun buildLevel(idx: Int): CampaignLevel {
        val order = orderForIndex(idx)
        val d = difficulty(idx)
        val firstNewRegion = newCountries(idx).first().region.swedishName
        return CampaignLevel(
            id = "level_$order",
            courseId = COURSE_ID,
            order = order,
            title = "Bana $idx: Flaggor från $firstNewRegion",
            biomeNameSv = BIOME_SV,
            subject = Subject.GEOGRAPHY,
            difficulty = d,
            timeRequirementSeconds = QUESTIONS_PER_LEVEL * (5 + d),
            referenceWpm = BalanceConfig.referenceWpmForDifficulty(d),
            taskId = "task_$order"
        )
    }

    /**
     * Builds all 30 geography (level, task) pairs. [levelRewardByOrder] mirrors the math/English
     * curriculum's reward wiring, but geography has no curated level rewards (plan 037): the map
     * is expected to be empty for orders 161..190, so every `rewardItemId` is `null` and chest
     * onboarding falls back to `rollOnboardingItem`.
     */
    fun buildLevelsAndTasks(levelRewardByOrder: Map<Int, String>): List<Pair<CampaignLevel, HomeworkTask>> =
        (1..LEVEL_COUNT).map { idx ->
            val level = buildLevel(idx)
            val order = orderForIndex(idx)
            val task = HomeworkTask(
                id = level.taskId,
                levelId = level.id,
                type = TaskType.GEOGRAPHY_FLAGS,
                subject = Subject.GEOGRAPHY,
                flagQuestions = buildQuestions(idx),
                rewardItemId = levelRewardByOrder[order]
            )
            level to task
        }
}
