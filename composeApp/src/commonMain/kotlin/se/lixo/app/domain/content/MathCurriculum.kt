package se.lixo.app.domain.content

import se.lixo.app.domain.model.BalanceConfig
import se.lixo.app.domain.model.MathProblem
import se.lixo.app.domain.model.MathProblemType

/**
 * Static, deterministic math curriculum for the 100 math campaign levels (n1..n100).
 *
 * `n` is the "math index" (1..100), distinct from `order`, the position of the level within the
 * full 160-order campaign. Math is independent from Swedish/English: Swedish (40 levels) and
 * English (20 levels) occupy orders 1..100 (order%5==0 -> ENGLISH, else even -> MATH slot,
 * else SWEDISH), while math itself lives at orders 2,4,6,8,12,... (the even, non-multiple-of-10
 * orders in 1..100) for n1..n40, and at orders 101..160 (order = n+60) for n41..n100. See
 * [orderForMathIndex] / [mathIndexForOrder] for the mapping between `n` and `order`.
 *
 * Every math level draws its questions from a small, hand-curated, fully deterministic pool so
 * Kotlin (shipped) and Python (content pipeline / tests) produce byte-identical output.
 */
object MathCurriculum {
    const val MATH_LEVEL_COUNT = 100

    val BIOMES = listOf(
        "Viskande Skogen",
        "Sifferdalen",
        "Glosornas Hamn",
        "Kristallbergen",
        "Stjärnborgen"
    )

    private enum class Kind(val type: MathProblemType) {
        ADD_SMALL(MathProblemType.ADDITION),
        TEN_FRIEND(MathProblemType.ADDITION),
        DOUBLES(MathProblemType.ADDITION),
        TEN_CROSS(MathProblemType.TEN_CROSSING),
        ADD_PLACE(MathProblemType.ADDITION),
        ADD_CARRY(MathProblemType.ADDITION),
        SUB_SMALL(MathProblemType.SUBTRACTION),
        SUB_BRIDGE(MathProblemType.SUBTRACTION),
        SUB_TENS(MathProblemType.SUBTRACTION),
        SUB_BORROW(MathProblemType.SUBTRACTION),
        CLOCK(MathProblemType.CLOCK),
        MONEY(MathProblemType.MONEY),
        WORD_ADD(MathProblemType.WORD_PROBLEM),
        WORD_SUB(MathProblemType.WORD_PROBLEM),
        WORD_TWO_STEP(MathProblemType.WORD_PROBLEM),
        MIX(MathProblemType.ADDITION) // placeholder, never used to render directly
    }

    private data class Stage(
        val id: String,
        val nFrom: Int,
        val nTo: Int,
        val theme: String,
        val kind: Kind,
        val pool: List<List<Int>> = emptyList(),
        val repStageId: String? = null,
        val mixSourceIds: List<String> = emptyList(),
        val countFn: (Int) -> Int,
        val difficultyFn: (Int) -> Int
    )

    // --- Pool builders -----------------------------------------------------

    private fun pairsSum(minSum: Int, maxSum: Int): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 1..maxSum) {
            for (b in a..maxSum) {
                val s = a + b
                if (s in minSum..maxSum) {
                    out.add(listOf(a, b))
                    if (a != b) out.add(listOf(b, a))
                }
            }
        }
        return out
    }

    private fun poolS5(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 1..5) {
            out.add(listOf(a, 10 - a))
            if (a != 10 - a) out.add(listOf(10 - a, a))
        }
        return out
    }

    private fun poolS6(): List<List<Int>> = (2..5).map { listOf(it, it) }

    private fun poolS8(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(9, 8, 7)) {
            for (b in (11 - a)..6) out.add(listOf(a, b))
        }
        return out
    }

    private fun poolS9(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(10, 20, 30, 40)) {
            for (b in listOf(3, 5, 7, 10, 20)) {
                if (a + b <= 50) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS10(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 2..10) {
            for (b in 1 until a) out.add(listOf(a, b))
        }
        return out
    }

    private fun poolS11(): List<List<Int>> = (1..12).map { listOf(it, 0) }

    private fun poolS12(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(12, 23, 31, 44, 52, 65)) {
            for (b in listOf(2, 3, 4)) {
                if (a % 10 + b <= 9) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS14(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (h in 1..12) {
            for (m in listOf(30, 0)) out.add(listOf(h, m))
        }
        return out
    }

    private fun poolS15(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(100, 106, 200, 300, 402)) {
            for (b in listOf(5, 10, 30)) out.add(listOf(a, b))
        }
        return out
    }

    private fun moneyS16(): List<List<Int>> = listOf(
        listOf(5, 1), listOf(5, 2), listOf(2, 2, 1), listOf(10, 1), listOf(10, 2), listOf(5, 5),
        listOf(10, 5), listOf(5, 2, 1), listOf(10, 2, 1), listOf(10, 5, 2), listOf(10, 5, 1), listOf(10, 10)
    )

    private fun poolS17a(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 13..19) {
            for (b in 1..(a - 10)) out.add(listOf(a, b))
        }
        return out
    }

    private fun poolS17b(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 11..18) {
            for (b in (a - 9)..9) {
                if (b > a - 10) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS18(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 6..12) {
            for (b in 3..8) out.add(listOf(a, b))
        }
        return out
    }

    private fun moneyS19(): List<List<Int>> = listOf(
        listOf(20, 5), listOf(20, 10), listOf(20, 20), listOf(50, 5), listOf(50, 10), listOf(20, 10, 5),
        listOf(50, 20), listOf(20, 20, 2), listOf(50, 10, 5), listOf(50, 20, 10), listOf(50, 20, 5, 1), listOf(50, 20, 20, 10)
    )

    private fun poolS20(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in 11..20) {
            for (b in 3..9) out.add(listOf(a, b))
        }
        return out
    }

    private fun poolS22(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(17, 27, 38, 46, 58)) {
            for (b in listOf(5, 7, 14, 15, 26)) {
                if (a % 10 + b % 10 >= 10) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS23(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(35, 45, 57, 68, 79, 86)) {
            for (b in listOf(12, 13, 24, 31)) {
                if (a % 10 >= b % 10) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun moneyS24(): List<List<Int>> = listOf(
        listOf(100, 10), listOf(100, 20), listOf(100, 50), listOf(100, 5, 2), listOf(100, 20, 5), listOf(100, 50, 10),
        listOf(100, 100), listOf(100, 20, 10, 5), listOf(100, 50, 20), listOf(100, 100, 50), listOf(100, 50, 20, 5, 2), listOf(100, 100, 20, 10, 1)
    )

    private fun poolS25(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(120, 134, 211, 342, 403)) {
            for (b in listOf(15, 23, 35, 41)) {
                if (a % 10 + b % 10 <= 9 && (a / 10) % 10 + (b / 10) % 10 <= 9) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS26(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (h in 1..12) {
            for (m in listOf(15, 45)) out.add(listOf(h, m))
        }
        return out
    }

    private fun poolS27(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(145, 167, 238, 356)) {
            for (b in listOf(27, 48, 78, 95)) {
                if (a % 10 + b % 10 >= 10) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS28(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(32, 43, 52, 64, 71, 85)) {
            for (b in listOf(17, 19, 26, 38)) {
                if (a > b && a % 10 < b % 10) out.add(listOf(a, b))
            }
        }
        return out
    }

    private fun poolS29(): List<List<Int>> = listOf(
        listOf(7, 20), listOf(10, 35), listOf(2, 10), listOf(5, 45), listOf(8, 5),
        listOf(11, 50), listOf(3, 25), listOf(6, 40), listOf(9, 15), listOf(12, 55),
        listOf(4, 20), listOf(1, 35), listOf(7, 50), listOf(10, 10), listOf(2, 40),
        listOf(5, 25), listOf(8, 45), listOf(11, 5), listOf(3, 55), listOf(6, 15)
    )

    private fun poolS30(): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        for (a in listOf(20, 30, 40, 50)) {
            for (b in listOf(10, 20, 30)) {
                for (c in listOf(5, 15)) out.add(listOf(a, b, c))
            }
        }
        return out
    }

    // --- Stage table ---------------------------------------------------

    private val stages: List<Stage> = listOf(
        Stage("S1", 1, 3, "Plus med små tal", Kind.ADD_SMALL, pairsSum(2, 5), countFn = { 6 }, difficultyFn = { 1 }),
        Stage("S2", 4, 6, "Plus upp till 6", Kind.ADD_SMALL, pairsSum(4, 6), countFn = { n -> if (n == 6) 8 else 6 }, difficultyFn = { 1 }),
        Stage("S3", 7, 9, "Plus upp till 8", Kind.ADD_SMALL, pairsSum(6, 8), repStageId = "S2", countFn = { 8 }, difficultyFn = { 1 }),
        Stage("S4", 10, 12, "Plus upp till 10", Kind.ADD_SMALL, pairsSum(8, 10), repStageId = "S3", countFn = { 8 }, difficultyFn = { 1 }),
        Stage("S5", 13, 15, "Tiokompisar", Kind.TEN_FRIEND, poolS5(), repStageId = "S4", countFn = { 8 }, difficultyFn = { 1 }),
        Stage("S6", 16, 17, "Dubbelt", Kind.DOUBLES, poolS6(), repStageId = "S5", countFn = { 8 }, difficultyFn = { 1 }),
        Stage("S7", 18, 20, "Plus-repetition", Kind.MIX, mixSourceIds = listOf("S2", "S3", "S4", "S5", "S6"), countFn = { 10 }, difficultyFn = { 1 }),
        Stage("S8", 21, 24, "Över tiotalet", Kind.TEN_CROSS, poolS8(), repStageId = "S4", countFn = { 8 }, difficultyFn = { 2 }),
        Stage("S9", 25, 27, "Hela tiotal", Kind.ADD_PLACE, poolS9(), countFn = { 8 }, difficultyFn = { 2 }),
        Stage("S10", 28, 30, "Första minus", Kind.SUB_SMALL, poolS10(), repStageId = "S4", countFn = { 8 }, difficultyFn = { 2 }),
        Stage("S11", 31, 33, "Lätt klocka: hela timmar", Kind.CLOCK, poolS11(), repStageId = "S10", countFn = { 8 }, difficultyFn = { 2 }),
        Stage("S12", 34, 36, "Tiotal och ental", Kind.ADD_PLACE, poolS12(), repStageId = "S9", countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S13", 37, 39, "Minus-repetition", Kind.MIX, mixSourceIds = listOf("S10", "S4"), countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S14", 40, 42, "Lätt klocka: halvtimmar", Kind.CLOCK, poolS14(), repStageId = "S11", countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S15", 43, 45, "Lätta hundratal", Kind.ADD_PLACE, poolS15(), countFn = { 10 }, difficultyFn = { 3 }),
        Stage("S16", 46, 49, "Små mynt", Kind.MONEY, moneyS16(), repStageId = "S8", countFn = { 8 }, difficultyFn = { n -> if (n <= 47) 2 else 3 }),
        Stage("S17a", 50, 51, "Minus till 20", Kind.SUB_SMALL, poolS17a(), countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S17b", 52, 53, "Minus till 20", Kind.SUB_BRIDGE, poolS17b(), countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S18", 54, 56, "Plus-gåtor", Kind.WORD_ADD, poolS18(), countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S19", 57, 59, "Sedlar", Kind.MONEY, moneyS19(), countFn = { 8 }, difficultyFn = { 3 }),
        Stage("S20", 60, 62, "Minus-gåtor", Kind.WORD_SUB, poolS20(), countFn = { 8 }, difficultyFn = { 3 }),
        Stage(
            "S21", 63, 65, "Stora repetitionen", Kind.MIX,
            mixSourceIds = listOf("S8", "S10", "S11", "S12", "S14", "S15", "S16", "S17b", "S19", "S20"),
            countFn = { 10 }, difficultyFn = { 3 }
        ),
        Stage("S22", 66, 69, "Tiotal med växling", Kind.ADD_CARRY, poolS22(), countFn = { 10 }, difficultyFn = { 4 }),
        Stage("S23", 70, 72, "Tiotal minus", Kind.SUB_TENS, poolS23(), countFn = { 10 }, difficultyFn = { 4 }),
        Stage("S24", 73, 75, "Hundralappen", Kind.MONEY, moneyS24(), countFn = { 10 }, difficultyFn = { 4 }),
        Stage("S25", 76, 79, "Hundratal plus", Kind.ADD_PLACE, poolS25(), countFn = { 10 }, difficultyFn = { 4 }),
        Stage("S26", 80, 83, "Svår klocka: kvart", Kind.CLOCK, poolS26(), repStageId = "S14", countFn = { 10 }, difficultyFn = { 4 }),
        Stage("S27", 84, 87, "Hundratal med växling", Kind.ADD_CARRY, poolS27(), countFn = { 10 }, difficultyFn = { 5 }),
        Stage("S28", 88, 91, "Minus med växling", Kind.SUB_BORROW, poolS28(), countFn = { 10 }, difficultyFn = { 5 }),
        Stage("S29", 92, 95, "Svår klocka: minuter", Kind.CLOCK, poolS29(), countFn = { 10 }, difficultyFn = { 5 }),
        Stage("S30", 96, 98, "Kluriga gåtor", Kind.WORD_TWO_STEP, poolS30(), countFn = { 10 }, difficultyFn = { 5 }),
        Stage(
            "S31", 99, 100, "Mästarprovet", Kind.MIX,
            mixSourceIds = listOf("S22", "S23", "S24", "S25", "S26", "S27", "S28", "S29", "S30"),
            countFn = { 10 }, difficultyFn = { 5 }
        )
    )

    private val stagesById: Map<String, Stage> = stages.associateBy { it.id }

    private fun stageForN(n: Int): Stage = stages.first { n in it.nFrom..it.nTo }

    // --- Level metadata ----------------------------------------------------

    data class MathLevelSpec(
        val n: Int,
        val order: Int,
        val theme: String,
        val title: String,
        val biome: String,
        val courseId: String,
        val difficulty: Int,
        val questionCount: Int,
        val timeRequirementSeconds: Int,
        val referenceWpm: Int
    )

    /**
     * Campaign `order` (1..160) for a given math index `n` (1..100).
     * n1..n40 sit at the even, non-multiple-of-10 orders within 1..100
     * (2,4,6,8,12,14,16,18,22,...,98); n41..n100 sit at orders 101..160 (order = n + 60).
     */
    fun orderForMathIndex(n: Int): Int = if (n <= 40) {
        10 * ((n - 1) / 4) + 2 * ((n - 1) % 4 + 1)
    } else {
        n + 60
    }

    /** Inverse of [orderForMathIndex]: the math index `n` for a campaign `order`, or null if the order isn't a math slot. */
    fun mathIndexForOrder(order: Int): Int? = when {
        order in 101..160 -> order - 60
        order in 1..100 && order % 2 == 0 && order % 10 != 0 -> {
            val k = order / 10
            val m = (order % 10) / 2
            4 * k + m
        }
        else -> null
    }

    fun mathLevelSpec(n: Int): MathLevelSpec {
        val stage = stageForN(n)
        val order = orderForMathIndex(n)
        val difficulty = stage.difficultyFn(n)
        val count = stage.countFn(n)
        val biome = BIOMES[(n - 1) / 20]
        val courseId = when {
            n <= 34 -> "course_math_1"
            n <= 67 -> "course_math_2"
            else -> "course_math_3"
        }
        val title = "Bana $n: ${stage.theme} ($biome)"
        return MathLevelSpec(
            n = n,
            order = order,
            theme = stage.theme,
            title = title,
            biome = biome,
            courseId = courseId,
            difficulty = difficulty,
            questionCount = count,
            timeRequirementSeconds = count * (8 + 2 * difficulty),
            referenceWpm = BalanceConfig.referenceWpmForDifficulty(difficulty)
        )
    }

    // --- Choices -------------------------------------------------------

    fun buildChoices(correct: String, distractors: List<String>, order: Int, i: Int): List<String> {
        val p = (order + i) % 4
        val result = mutableListOf<String>()
        result.addAll(distractors.subList(0, p))
        result.add(correct)
        result.addAll(distractors.subList(p, 3))
        return result
    }

    private fun numericDistractors(ans: Int): List<String> {
        val raw = listOf(ans - 1, ans + 1, ans + 2)
        return raw.map { if (it < 0) (ans + 3).toString() else it.toString() }
    }

    private fun moneyDistractors(ans: Int): List<String> {
        val raw = listOf(ans - 5, ans + 5, ans + 10)
        return raw.map { if (it < 0) (ans + 15).toString() else it.toString() }
    }

    private fun pad2(x: Int): String = x.toString().padStart(2, '0')

    private fun clockDistractors(h: Int, m: Int): List<String> {
        val t0 = (h % 12) * 60 + m
        return listOf(-60, 60, 30).map { off ->
            val t = (t0 + off).mod(720)
            val hour = if (t / 60 == 0) 12 else t / 60
            val minute = t % 60
            "${pad2(hour)}:${pad2(minute)}"
        }
    }

    // --- Clock rendering -------------------------------------------------

    private val numberWords = listOf("ett", "två", "tre", "fyra", "fem", "sex", "sju", "åtta", "nio", "tio", "elva", "tolv")
    private fun w(x: Int): String = numberWords[x - 1]

    private fun cap(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private fun clockName(h: Int, m: Int): String {
        val nh = h % 12 + 1
        return when (m) {
            0 -> "klockan ${w(h)}"
            5 -> "fem över ${w(h)}"
            10 -> "tio över ${w(h)}"
            15 -> "kvart över ${w(h)}"
            20 -> "tjugo över ${w(h)}"
            25 -> "fem i halv ${w(nh)}"
            30 -> "halv ${w(nh)}"
            35 -> "fem över halv ${w(nh)}"
            40 -> "tjugo i ${w(nh)}"
            45 -> "kvart i ${w(nh)}"
            50 -> "tio i ${w(nh)}"
            55 -> "fem i ${w(nh)}"
            else -> ""
        }
    }

    // --- Money rendering -------------------------------------------------

    private fun moneyGroups(values: List<Int>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < values.size) {
            val v = values[i]
            var cnt = 1
            while (i + cnt < values.size && values[i + cnt] == v) cnt++
            out.add(v to cnt)
            i += cnt
        }
        return out
    }

    private fun cntWord(cnt: Int): String = when (cnt) {
        1 -> "en"
        2 -> "två"
        3 -> "tre"
        else -> cnt.toString()
    }

    private fun moneyNoun(v: Int, cnt: Int): String = if (v < 20) {
        if (cnt == 1) "krona" else "kronor"
    } else {
        if (cnt == 1) "lapp" else "lappar"
    }

    private fun moneyGroupText(v: Int, cnt: Int): String = "${cntWord(cnt)} $v-${moneyNoun(v, cnt)}"

    private fun moneyJoinedText(values: List<Int>): String {
        val groups = moneyGroups(values)
        val texts = groups.map { (v, cnt) -> moneyGroupText(v, cnt) }
        return when (texts.size) {
            1 -> texts[0]
            else -> texts.dropLast(1).joinToString(", ") + " och " + texts.last()
        }
    }

    // --- Word problem templates -------------------------------------------

    private data class WordTemplate(val text: String, val unit: String)

    private val wordAddTemplates = listOf(
        WordTemplate("Katten Lixo har {a} blåbär och plockar {b} till. Hur många blåbär har Lixo nu?", "blåbär"),
        WordTemplate("Ugglan har {a} kottar och hittar {b} till i skogen. Hur många kottar har ugglan nu?", "kottar"),
        WordTemplate("Riddaren har {a} guldmynt och får {b} till av draken. Hur många guldmynt har riddaren nu?", "guldmynt"),
        WordTemplate("Prinsessan har {a} pärlor och hittar {b} till på stranden. Hur många pärlor har prinsessan nu?", "pärlor")
    )

    private val wordSubTemplates = listOf(
        WordTemplate("Katten Lixo har plockat {a} blåbär i skogen och bjuder ugglan på {b} stycken. Hur många blåbär har Lixo kvar?", "blåbär"),
        WordTemplate("Ekorren har {a} hasselnötter och äter upp {b}. Hur många hasselnötter har ekorren kvar?", "hasselnötter"),
        WordTemplate("Draken har {a} äpplen och ger bort {b} till hunden. Hur många äpplen har draken kvar?", "äpplen"),
        WordTemplate("Häxan har {a} stjärnor i sin gryta och {b} flyger iväg. Hur många stjärnor finns kvar?", "stjärnor")
    )

    private val wordTwoStepTemplates = listOf(
        WordTemplate("Riddaren har {a} guldmynt. Han hittar {b} till i en skattkista och köper sedan en sköld för {c} guldmynt. Hur många guldmynt har han kvar?", "guldmynt"),
        WordTemplate("Ugglan har {a} kottar. Hon samlar {b} till och ger {c} till ekorren. Hur många kottar har ugglan nu?", "kottar"),
        WordTemplate("Trollkarlen har {a} stjärnor i en burk. Han trollar fram {b} till och {c} flyger iväg. Hur många stjärnor finns i burken nu?", "stjärnor")
    )

    // --- Rendering ----------------------------------------------------

    private data class Rendered(
        val prompt: String,
        val correctAnswer: String,
        val hint1: String,
        val hint2: String,
        val visualClockHour: Int? = null,
        val visualClockMinute: Int? = null,
        val visualMoneyBreakdownKr: List<Int> = emptyList(),
        val distractors: List<String>
    )

    private fun render(kind: Kind, item: List<Int>, j: Int): Rendered {
        return when (kind) {
            Kind.ADD_SMALL -> {
                val (a, b) = item
                val ans = a + b
                Rendered(
                    prompt = "Vad blir $a + $b?",
                    correctAnswer = "$ans",
                    hint1 = "Börja på $a och räkna $b steg framåt på tallinjen.",
                    hint2 = "$a + $b = $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.TEN_FRIEND -> {
                val (a, b) = item
                val ans = b
                Rendered(
                    prompt = "Tiokompisar: $a + ? = 10. Vilket tal fattas?",
                    correctAnswer = "$ans",
                    hint1 = "Räkna från $a upp till 10. Hur många steg blev det?",
                    hint2 = "$a + $b = 10, så det fattas $b!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.DOUBLES -> {
                val a = item[0]
                val ans = 2 * a
                Rendered(
                    prompt = "Dubbelt: Vad blir $a + $a?",
                    correctAnswer = "$ans",
                    hint1 = "Dubbelt betyder samma tal två gånger: $a och $a till.",
                    hint2 = "$a + $a = $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.TEN_CROSS -> {
                val (a, b) = item
                val need = 10 - a
                val rest = b - need
                val ans = a + b
                Rendered(
                    prompt = "Tiotalsövergång: Vad blir $a + $b?",
                    correctAnswer = "$ans",
                    hint1 = "$a behöver $need till för att bli ett helt tiotal (10).",
                    hint2 = "Dela upp $b i $need + $rest: $a + $need = 10, och 10 + $rest = $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.ADD_PLACE -> {
                val (a, b) = item
                val ans = a + b
                Rendered(
                    prompt = "Vad blir $a + $b?",
                    correctAnswer = "$ans",
                    hint1 = "Lägg ihop entalen först: ${a % 10} + ${b % 10} = ${a % 10 + b % 10}.",
                    hint2 = "Lägg sedan ihop tiotalen och hundratalen: $a + $b = $ans.",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.ADD_CARRY -> {
                val (a, b) = item
                val ans = a + b
                Rendered(
                    prompt = "Vad blir $a + $b?",
                    correctAnswer = "$ans",
                    hint1 = "Lägg ihop entalen först: ${a % 10} + ${b % 10} = ${a % 10 + b % 10}. Det blir ett nytt tiotal!",
                    hint2 = "Glöm inte växlingen: $a + $b = $ans.",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.SUB_SMALL -> {
                val (a, b) = item
                val ans = a - b
                Rendered(
                    prompt = "Vad blir $a - $b?",
                    correctAnswer = "$ans",
                    hint1 = "Starta på $a och backa $b steg bakåt.",
                    hint2 = "$a minus $b blir $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.SUB_BRIDGE -> {
                val (a, b) = item
                val ans = a - b
                val o = a - 10
                Rendered(
                    prompt = "Vad blir $a - $b?",
                    correctAnswer = "$ans",
                    hint1 = "Backa först ner till 10: $a - $o = 10.",
                    hint2 = "Backa sedan ${b - o} steg till: 10 - ${b - o} = $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.SUB_TENS -> {
                val (a, b) = item
                val ans = a - b
                Rendered(
                    prompt = "Vad blir $a - $b?",
                    correctAnswer = "$ans",
                    hint1 = "Ta tiotal minus tiotal: ${(a / 10) * 10} - ${(b / 10) * 10} = ${(a / 10 - b / 10) * 10}.",
                    hint2 = "Ta ental minus ental: ${a % 10} - ${b % 10} = ${a % 10 - b % 10}. Svaret blir $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.SUB_BORROW -> {
                val (a, b) = item
                val ans = a - b
                val r = a - (b / 10) * 10
                Rendered(
                    prompt = "Vad blir $a - $b?",
                    correctAnswer = "$ans",
                    hint1 = "Ta först bort tiotalen: $a - ${(b / 10) * 10} = $r.",
                    hint2 = "Ta sedan bort entalen: $r - ${b % 10} = $ans!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.CLOCK -> {
                val (h, m) = item
                val nh = h % 12 + 1
                val pos = m / 5
                val ans = "${pad2(h)}:${pad2(m)}"
                val name = clockName(h, m)
                val hint2 = "${cap(name)} skrivs digitalt som $ans."
                val (prompt, hint1) = when (m) {
                    0 -> "Timvisaren pekar rakt på $h och minutvisaren pekar rakt upp på 12. Vad är klockan?" to
                        "När minutvisaren pekar på 12 är det hel timme (:00)."
                    30 -> "Timvisaren pekar mellan $h och $nh, och minutvisaren pekar rakt ner på 6. Vad är klockan?" to
                        "När minutvisaren pekar på 6 har det gått en halv timme (30 minuter)."
                    15 -> "Timvisaren är strax efter $h och minutvisaren pekar på 3. Vad är klockan?" to
                        "När minutvisaren pekar på 3 har det gått en kvart (15 minuter)."
                    45 -> "Timvisaren närmar sig $nh och minutvisaren pekar på 9. Vad är klockan?" to
                        "När minutvisaren pekar på 9 har det gått 45 minuter, alltså kvart i ${w(nh)}."
                    else -> "Timvisaren är mellan $h och $nh, och minutvisaren pekar på $pos. Vad är klockan?" to
                        "Varje siffra på klockan är 5 minuter. Räkna i femsteg till $pos: det blir $m minuter."
                }
                Rendered(
                    prompt = prompt,
                    correctAnswer = ans,
                    hint1 = hint1,
                    hint2 = hint2,
                    visualClockHour = h,
                    visualClockMinute = m,
                    distractors = clockDistractors(h, m)
                )
            }
            Kind.MONEY -> {
                val ans = item.sum()
                val joined = moneyJoinedText(item)
                val v1 = item[0]
                val v2 = item[1]
                val s2 = v1 + v2
                val hint2 = if (item.size == 2) {
                    "Alltså har du $ans kr tillsammans!"
                } else {
                    val rest = item.drop(2)
                    "Lägg till resten: $s2 kr" + rest.joinToString("") { " + $it kr" } + " = $ans kr!"
                }
                Rendered(
                    prompt = "Du har $joined. Hur många kronor har du tillsammans?",
                    correctAnswer = "$ans",
                    hint1 = "Börja med det största: $v1 kr + $v2 kr = $s2 kr.",
                    hint2 = hint2,
                    visualMoneyBreakdownKr = item,
                    distractors = moneyDistractors(ans)
                )
            }
            Kind.WORD_ADD -> {
                val (a, b) = item
                val ans = a + b
                val tpl = wordAddTemplates[j % 4]
                Rendered(
                    prompt = tpl.text.replace("{a}", "$a").replace("{b}", "$b"),
                    correctAnswer = "$ans",
                    hint1 = "Vi räknar addition: $a plus $b.",
                    hint2 = "$a + $b = $ans ${tpl.unit}!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.WORD_SUB -> {
                val (a, b) = item
                val ans = a - b
                val tpl = wordSubTemplates[j % 4]
                Rendered(
                    prompt = tpl.text.replace("{a}", "$a").replace("{b}", "$b"),
                    correctAnswer = "$ans",
                    hint1 = "Vi räknar subtraktion: $a minus $b.",
                    hint2 = "$a - $b = $ans ${tpl.unit} kvar!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.WORD_TWO_STEP -> {
                val a = item[0]
                val b = item[1]
                val c = item[2]
                val ans = a + b - c
                val tpl = wordTwoStepTemplates[j % 3]
                Rendered(
                    prompt = tpl.text.replace("{a}", "$a").replace("{b}", "$b").replace("{c}", "$c"),
                    correctAnswer = "$ans",
                    hint1 = "Steg 1: $a + $b = ${a + b}.",
                    hint2 = "Steg 2: ${a + b} - $c = $ans ${tpl.unit}!",
                    distractors = numericDistractors(ans)
                )
            }
            Kind.MIX -> error("MIX is not directly renderable")
        }
    }

    // --- Problem building ----------------------------------------------

    fun buildMathProblems(n: Int, order: Int): List<MathProblem> {
        val stage = stageForN(n)
        val k = n - stage.nFrom
        val count = stage.countFn(n)
        val problems = mutableListOf<MathProblem>()
        for (i in 0 until count) {
            val j = k * count + i
            val sourceStage: Stage = when {
                stage.repStageId != null && i < 2 -> stagesById.getValue(stage.repStageId)
                stage.kind == Kind.MIX -> stagesById.getValue(stage.mixSourceIds[i % stage.mixSourceIds.size])
                else -> stage
            }
            val item = sourceStage.pool[j % sourceStage.pool.size]
            val rendered = render(sourceStage.kind, item, j)
            val choices = buildChoices(rendered.correctAnswer, rendered.distractors, order, i)
            val typeId = sourceStage.kind.type.id
            problems.add(
                MathProblem(
                    id = "mp_${order}_${i}_$typeId",
                    type = sourceStage.kind.type,
                    promptSv = rendered.prompt,
                    correctAnswer = rendered.correctAnswer,
                    choices = choices,
                    hintStep1Sv = rendered.hint1,
                    hintStep2Sv = rendered.hint2,
                    visualClockHour = rendered.visualClockHour,
                    visualClockMinute = rendered.visualClockMinute,
                    visualMoneyBreakdownKr = rendered.visualMoneyBreakdownKr
                )
            )
        }
        return problems
    }
}
