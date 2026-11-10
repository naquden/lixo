package se.lixo.app.domain.social

/** QR / deep-link prefix for a friend invite (doc/plans/030 §4): `lixo://friend/<code>`. */
private const val FRIEND_CODE_PREFIX = "lixo://friend/"

/** Crockford base32 alphabet (digits + A-Z minus the easily-confused I, L, O, U), 32 symbols. */
const val FRIEND_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

const val FRIEND_CODE_LENGTH = 8

/** The QR payload for [code] (doc/plans/030 §4): the only thing ever encoded in the QR image. */
fun buildFriendPayload(code: String): String = "$FRIEND_CODE_PREFIX$code"

/**
 * Parses [raw] (typed text, a deep link, or a scanned QR payload) into an 8-char invite code, or
 * null if it isn't one (doc/plans/030 §4): strips the `lixo://friend/` prefix case-insensitively,
 * removes `-`/spaces, upper-cases, maps the easily-confused `O`→`0` and `I`/`L`→`1`, then requires
 * exactly [FRIEND_CODE_LENGTH] characters from [FRIEND_CODE_ALPHABET]. Used by the text input, the
 * deep link and the QR scanner alike, so any junk, a bare URL, or the old `user:avatar:stars`
 * payload all return null rather than being silently accepted.
 */
fun parseFriendCode(raw: String): String? {
    var cleaned = raw.trim()
    if (cleaned.lowercase().startsWith(FRIEND_CODE_PREFIX.lowercase())) {
        cleaned = cleaned.substring(FRIEND_CODE_PREFIX.length)
    }
    cleaned = cleaned
        .replace("-", "")
        .replace(" ", "")
        .uppercase()
        .map { ch ->
            when (ch) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> ch
            }
        }
        .joinToString("")
    if (cleaned.length != FRIEND_CODE_LENGTH) return null
    if (!cleaned.all { it in FRIEND_CODE_ALPHABET }) return null
    return cleaned
}
