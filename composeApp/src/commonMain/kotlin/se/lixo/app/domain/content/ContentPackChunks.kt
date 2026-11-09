package se.lixo.app.domain.content

/**
 * Splits a content pack's canonical JSON into Firestore-sized chunks (024) and verifies a
 * downloaded set of chunks against the published `sha256`. [split] cuts only at character
 * boundaries (never inside a UTF-16 surrogate pair) while keeping each chunk's **UTF-8** byte
 * size at or under [MAX_CHUNK_BYTES], matching `tools/content_pipeline/pack/upload_pack.py`'s
 * `split_chunks`, which must produce byte-identical chunk text for the same input.
 */
object ContentPackChunks {

    /** Keeps each `content_chunks/{schema}_{version}_{i}` document comfortably under 1 MiB. */
    const val MAX_CHUNK_BYTES = 900_000

    /** `ContentPackValidator.MAX_BYTES` (6 MiB) / [MAX_CHUNK_BYTES], rounded up. */
    const val MAX_CHUNKS = 8

    /** Splits [json] into chunks of at most [maxBytes] UTF-8 bytes each, cutting only between characters. */
    fun split(json: String, maxBytes: Int = MAX_CHUNK_BYTES): List<String> {
        if (json.isEmpty()) return listOf("")

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < json.length) {
            val c = json[i]
            val isHighSurrogate = c.isHighSurrogate() && i + 1 < json.length && json[i + 1].isLowSurrogate()
            val charLen = if (isHighSurrogate) 2 else 1
            val codeUnits = if (isHighSurrogate) intArrayOf(c.code, json[i + 1].code) else intArrayOf(c.code)
            val byteCost = utf8ByteCost(codeUnits, isHighSurrogate)

            if (currentBytes + byteCost > maxBytes && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
                currentBytes = 0
            }

            current.append(c)
            if (isHighSurrogate) current.append(json[i + 1])
            currentBytes += byteCost
            i += charLen
        }
        if (current.isNotEmpty() || chunks.isEmpty()) {
            chunks.add(current.toString())
        }
        return chunks
    }

    private fun utf8ByteCost(codeUnits: IntArray, isSurrogatePair: Boolean): Int {
        if (isSurrogatePair) return 4
        val code = codeUnits[0]
        return when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            else -> 3
        }
    }

    /**
     * Joins [chunks] in order and returns the result only if its SHA-256 (over the UTF-8 bytes
     * of the joined string) matches [expectedSha256] (case-insensitive); otherwise null.
     */
    fun joinVerified(chunks: List<String>, expectedSha256: String): String? {
        val joined = chunks.joinToString(separator = "")
        return if (Sha256.hex(joined).equals(expectedSha256, ignoreCase = true)) joined else null
    }
}
