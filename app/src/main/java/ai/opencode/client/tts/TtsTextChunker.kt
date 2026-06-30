package ai.opencode.client.tts

internal object TtsTextChunker {

    fun chunk(text: String, maxLength: Int): List<String> {
        val limit = (maxLength - 1).coerceAtLeast(256)
        if (text.length <= limit) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= limit) {
                chunks.add(remaining)
                break
            }
            val splitAt = findSplitIndex(remaining, limit)
            chunks.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks.filter { it.isNotEmpty() }
    }

    private fun findSplitIndex(text: String, limit: Int): Int {
        val window = text.substring(0, limit)
        val minSplit = (limit * 0.5).toInt()
        for (delimiter in CHUNK_DELIMITERS) {
            val idx = window.lastIndexOf(delimiter)
            if (idx >= minSplit) return idx + delimiter.length
        }
        val space = window.lastIndexOf(' ')
        if (space >= minSplit) return space + 1
        return limit
    }

    private val CHUNK_DELIMITERS = listOf(
        "\n\n",
        "\n",
        "。",
        "！",
        "？",
        "；",
        ". ",
        "! ",
        "? ",
        "; ",
    )
}
