package com.asterix.voiceplayer.command

/**
 * Turns spoken text and user-typed aliases into comparable forms.
 *
 * - [tokens]: lowercase words, punctuation removed, letter/digit runs split ("song1" -> "song", "1").
 * - [aliasKey]: canonical key used for matching and duplicate detection. Number words are folded
 *   into digits and everything is concatenated, so "Song1", "song 1" and "song one" all map to "song1".
 * - [spokenWords]: how an alias is pronounced, used to build the recognizer grammar ("song1" -> "song one").
 */
object TextNormalizer {

    const val UNKNOWN_TOKEN = "[unk]"

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ONES_WORDS = ONES.entries.associate { it.value to it.key }
    private val TEENS_WORDS = TEENS.entries.associate { it.value to it.key }
    private val TENS_WORDS = TENS.entries.associate { it.value to it.key }

    /** All number words, exposed so the recognizer grammar can include them. */
    val NUMBER_WORDS: Set<String> = ONES.keys + TEENS.keys + TENS.keys + "hundred"

    private val SPLIT_LETTER_DIGIT = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
    private val NON_WORD = Regex("[^a-z0-9]+")

    fun tokens(text: String): List<String> {
        val lowered = text.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(UNKNOWN_TOKEN, " \u0001 ")
        return lowered.split(Regex("\\s+"))
            .flatMap { raw ->
                if (raw == "\u0001") listOf(UNKNOWN_TOKEN)
                else raw.replace(NON_WORD, " ").trim().split(' ')
                    .flatMap { it.split(SPLIT_LETTER_DIGIT) }
            }
            .filter { it.isNotBlank() }
    }

    /** Canonical matching key for a token list; number words become digits. */
    fun keyOf(tokens: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val run = parseNumber(tokens, i)
            if (run != null) {
                sb.append(run.first)
                i += run.second
            } else {
                sb.append(tokens[i])
                i++
            }
        }
        return sb.toString()
    }

    fun aliasKey(alias: String): String = keyOf(tokens(alias))

    /**
     * Parses a number starting at [start]. Returns the digit string and how many tokens it used,
     * or null when the token is not a number. Handles 0-999 in words ("one hundred and five",
     * "twenty one") and literal digit tokens.
     */
    internal fun parseNumber(tokens: List<String>, start: Int): Pair<String, Int>? {
        val first = tokens[start]
        if (first.all { it.isDigit() }) return first to 1

        var i = start
        var value = 0
        var matched = false

        val hundreds = ONES[tokens[i]]
        if (hundreds != null && hundreds > 0 && tokens.getOrNull(i + 1) == "hundred") {
            value = hundreds * 100
            i += 2
            matched = true
            if (tokens.getOrNull(i) == "and" && belowHundred(tokens, i + 1) != null) i++
        }
        val below = if (i < tokens.size) belowHundred(tokens, i) else null
        if (below != null) {
            // Only add the trailing part to a hundreds value if it is non-zero ("one hundred five").
            if (!matched || below.first != 0) {
                value += below.first
                i += below.second
                matched = true
            }
        }
        return if (matched) value.toString() to (i - start) else null
    }

    private fun belowHundred(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val t = tokens.getOrNull(i) ?: return null
        TEENS[t]?.let { return it to 1 }
        TENS[t]?.let { tens ->
            val ones = tokens.getOrNull(i + 1)?.let { ONES[it] }
            return if (ones != null && ones > 0) (tens + ones) to 2 else tens to 1
        }
        ONES[t]?.let { return it to 1 }
        return null
    }

    /** Words a person would say for this alias, e.g. "alarm_12" -> [alarm, twelve]. */
    fun spokenWords(alias: String): List<String> =
        tokens(alias).flatMap { token ->
            if (token.all { it.isDigit() }) digitsToWords(token) else listOf(token)
        }

    internal fun digitsToWords(digits: String): List<String> {
        if (digits.length > 1 && digits.startsWith("0") || digits.length > 3) {
            return digits.map { ONES_WORDS.getValue(it - '0') }
        }
        return numberToWords(digits.toInt())
    }

    internal fun numberToWords(n: Int): List<String> {
        require(n in 0..999)
        if (n == 0) return listOf("zero")
        val words = mutableListOf<String>()
        val h = n / 100
        val rest = n % 100
        if (h > 0) words += listOf(ONES_WORDS.getValue(h), "hundred")
        when {
            rest == 0 -> Unit
            rest < 10 -> words += ONES_WORDS.getValue(rest)
            rest < 20 -> words += TEENS_WORDS.getValue(rest)
            else -> {
                words += TENS_WORDS.getValue(rest / 10 * 10)
                if (rest % 10 > 0) words += ONES_WORDS.getValue(rest % 10)
            }
        }
        return words
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
