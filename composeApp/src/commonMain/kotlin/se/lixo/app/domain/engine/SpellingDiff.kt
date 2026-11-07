package se.lixo.app.domain.engine

/** One character of a comparison target word, flagged whether it was found in the heard text's LCS. */
data class DiffChar(val char: Char, val matched: Boolean)

/**
 * LCS-based: marks each char of [target] as matched if it is part of the longest common
 * subsequence with [heard] (case-insensitive). Used to highlight the letters of a yellow story
 * word that the AI actually heard versus the ones it missed.
 */
fun spellingDiff(target: String, heard: String): List<DiffChar> {
    if (target.isEmpty()) return emptyList()

    val a = target.lowercase()
    val b = heard.lowercase()
    val n = a.length
    val m = b.length

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val matched = BooleanArray(n)
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        if (a[i - 1] == b[j - 1]) {
            matched[i - 1] = true
            i--
            j--
        } else if (dp[i - 1][j] >= dp[i][j - 1]) {
            i--
        } else {
            j--
        }
    }

    return target.mapIndexed { k, c -> DiffChar(c, matched[k]) }
}
