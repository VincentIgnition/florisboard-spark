/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Persisted per-user language data: words the user typed, which word tends to follow which, preferred casing for
 * learned words and words the user explicitly removed from suggestions.
 */
@Serializable
data class LatinUserModelData(
    val version: Int = 1,
    val unigrams: Map<String, Int> = emptyMap(),
    val bigrams: Map<String, Map<String, Int>> = emptyMap(),
    val casing: Map<String, String> = emptyMap(),
    val blocked: List<String> = emptyList(),
)

/**
 * Suggestions for the current input.
 *
 * @property words The candidates in display order.
 * @property autoCorrection The word to replace the input with when the user ends the word, or null to keep it.
 */
data class LatinSuggestionResult(
    val words: List<String>,
    val autoCorrection: String?,
)

/**
 * A word that was just finished by typing a separator after it.
 *
 * @property previous The word before it, [LatinTextAnalysis.SENTENCE_START] at a sentence start, or null if unknown.
 * @property word The finished word, exactly as typed.
 * @property charsAfter How many separator characters sit between the end of the word and the cursor.
 */
data class LatinCompletedWord(
    val previous: String?,
    val word: String,
    val charsAfter: Int,
)

/**
 * Helpers for reading words out of the text before the cursor. Shared by the suggestion provider (to know what is
 * being typed) and the editor (to know how many characters a tapped suggestion replaces), so both always agree.
 */
object LatinTextAnalysis {
    const val SENTENCE_START = "<s>"

    private const val APOSTROPHE = '\''
    private const val RIGHT_SINGLE_QUOTE = '’'

    fun isWordChar(c: Char): Boolean {
        return c.isLetter() || c == APOSTROPHE || c == RIGHT_SINGLE_QUOTE
    }

    private fun isSentenceEnd(c: Char): Boolean {
        return c == '.' || c == '!' || c == '?' || c == '…'
    }

    private fun trimQuotes(word: CharSequence): String {
        return word.toString().trim(APOSTROPHE, RIGHT_SINGLE_QUOTE)
    }

    /**
     * Returns the partial word directly before the cursor, or an empty string if the cursor does not touch a word.
     * Leading apostrophes are excluded so that an opening quote is never treated as part of the word.
     */
    fun currentWord(textBefore: CharSequence): String {
        var i = textBefore.length
        while (i > 0 && isWordChar(textBefore[i - 1])) {
            i--
        }
        return textBefore.subSequence(i, textBefore.length).toString().trimStart(APOSTROPHE, RIGHT_SINGLE_QUOTE)
    }

    /**
     * Returns the word before a word boundary at the end of [textBeforeWord].
     *
     * @param textBeforeWord Text that ends right where the word of interest starts.
     * @param hasMoreBefore True if the editor has more text before [textBeforeWord] that was not captured.
     */
    fun previousWord(textBeforeWord: CharSequence, hasMoreBefore: Boolean): String? {
        var i = textBeforeWord.length
        if (i == 0) {
            return if (hasMoreBefore) null else SENTENCE_START
        }
        var sawWhitespace = false
        var sawNewline = false
        while (i > 0 && textBeforeWord[i - 1].isWhitespace()) {
            if (textBeforeWord[i - 1] == '\n') sawNewline = true
            sawWhitespace = true
            i--
        }
        if (!sawWhitespace) {
            return null
        }
        if (sawNewline) {
            return SENTENCE_START
        }
        if (i == 0) {
            return if (hasMoreBefore) null else SENTENCE_START
        }
        val c = textBeforeWord[i - 1]
        if (isSentenceEnd(c)) {
            return SENTENCE_START
        }
        if (!isWordChar(c)) {
            return null
        }
        var j = i
        while (j > 0 && isWordChar(textBeforeWord[j - 1])) {
            j--
        }
        if (j == 0 && hasMoreBefore) {
            return null
        }
        val word = LatinSuggestionEngine.normalize(trimQuotes(textBeforeWord.subSequence(j, i)))
        return word.ifEmpty { null }
    }

    /**
     * If the cursor sits right after a separator that ended a word, returns that word and its context.
     */
    fun lastCompletedWord(textBefore: CharSequence, hasMoreBefore: Boolean): LatinCompletedWord? {
        var i = textBefore.length
        while (i > 0 && !isWordChar(textBefore[i - 1])) {
            i--
        }
        if (i == textBefore.length || i == 0) {
            return null
        }
        val separator = textBefore[i]
        if (!separator.isWhitespace() && separator !in ".,!?;:)\"”…") {
            return null
        }
        var j = i
        while (j > 0 && isWordChar(textBefore[j - 1])) {
            j--
        }
        if (j == 0 && hasMoreBefore) {
            return null
        }
        val word = trimQuotes(textBefore.subSequence(j, i))
        if (word.isEmpty()) {
            return null
        }
        val previous = previousWord(textBefore.subSequence(0, j), hasMoreBefore)
        return LatinCompletedWord(previous, word, textBefore.length - i)
    }
}

/**
 * Statistical next-word and current-word suggestion engine for Latin script languages.
 *
 * Combines a static frequency dictionary with a learned per-user model. Ranking blends word frequency, learned
 * usage, which word usually follows the previous one, and a keyboard-aware typo distance.
 *
 * This class is not thread safe, callers must serialize access.
 */
class LatinSuggestionEngine(dictionary: Map<String, Int>) {
    companion object {
        const val MAX_WORD_LENGTH = 32

        private const val PREFIX_SCAN_LIMIT = 12_000
        private const val MAX_TYPO_INPUT_LENGTH = 20

        private const val EXACT_BONUS = 0.25
        private const val COMPLETION_PENALTY_PER_CHAR = 0.03
        private const val COMPLETION_PENALTY_MAX = 0.3
        private const val TYPO_PENALTY = 0.45
        private const val FUZZY_PREFIX_PENALTY = 0.1
        private const val BIGRAM_WEIGHT = 1.2
        private const val USER_WEIGHT = 0.6

        /** An auto-correction may score at most this much below the overall top suggestion. */
        private const val AUTO_CORRECT_MAX_RANK_GAP = 0.2
        /** The chosen auto-correction must beat the next best correction by at least this much. */
        private const val AUTO_CORRECT_MIN_MARGIN = 0.03

        private const val TRANSPOSE_COST = 0.75
        private const val ADJACENT_SUBSTITUTE_COST = 0.5
        private const val APOSTROPHE_EDIT_COST = 0.1
        /** Skipping a letter ("probly") is a more common slip than hitting a wrong or extra key. */
        private const val OMISSION_COST = 0.6

        private const val MAX_USER_UNIGRAMS = 20_000
        private const val MAX_USER_BIGRAM_ROOTS = 10_000
        private const val MAX_BIGRAMS_PER_ROOT = 64
        private const val MAX_COUNT = 100_000

        /** Words the user must type this many times before a word unknown to the dictionary gets suggested. */
        const val USER_WORD_MIN_COUNT = 2

        private val QWERTY_ROWS = listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5)

        private val ADJACENT: Array<BooleanArray> = Array(26) { BooleanArray(26) }.also { matrix ->
            val positions = HashMap<Char, Pair<Double, Double>>()
            for ((rowIndex, row) in QWERTY_ROWS.withIndex()) {
                val (keys, offset) = row
                for ((col, key) in keys.withIndex()) {
                    positions[key] = Pair(col + offset, rowIndex.toDouble())
                }
            }
            for ((a, pa) in positions) {
                for ((b, pb) in positions) {
                    if (a == b) continue
                    val dx = pa.first - pb.first
                    val dy = pa.second - pb.second
                    if (sqrt(dx * dx + dy * dy) <= 1.25) {
                        matrix[a - 'a'][b - 'a'] = true
                    }
                }
            }
        }

        private val POSSIBLY_OFFENSIVE = hashSetOf(
            "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "motherfucker", "motherfucking",
            "shit", "shits", "shitty", "shitting", "bullshit", "bitch", "bitches", "bitchy", "cunt", "cunts",
            "asshole", "assholes", "dick", "dicks", "dickhead", "cock", "cocks", "pussy", "pussies", "whore",
            "whores", "slut", "sluts", "twat", "wanker", "bastard", "bastards", "fag", "fags", "faggot",
            "faggots", "nigger", "niggers", "nigga", "niggas", "retard", "retards", "retarded", "spic", "chink",
            "kike", "tranny", "dyke", "cum", "jizz", "tits", "titties", "boner", "piss", "pissed",
        )

        /**
         * Real words that happen to equal a contraction with its apostrophe removed. All other apostrophe-less
         * contraction spellings ("dont", "im") are dropped from the dictionary so the proper form wins.
         */
        private val CONTRACTION_LOOKALIKES = hashSetOf(
            "ill", "well", "were", "shell", "hell", "its", "lets", "id", "wed", "shed",
        )

        private val CONTRACTION_SUFFIXES = listOf("n't", "'m", "'re", "'ve", "'ll", "'d")

        /** Words where "'s" means "is" or "us" rather than possession, so "its" style bare forms are not plurals. */
        private val S_CONTRACTIONS = hashSetOf(
            "it's", "that's", "what's", "he's", "she's", "there's", "here's", "who's", "let's", "where's",
            "how's", "when's", "why's", "everyone's", "everything's", "nothing's", "something's", "someone's",
        )

        private fun isContraction(word: String): Boolean {
            return word in S_CONTRACTIONS || CONTRACTION_SUFFIXES.any { word.endsWith(it) && word.length > it.length }
        }

        /** Misspellings that are frequent enough in web text to have made it into the bundled dictionary. */
        private val KNOWN_MISSPELLINGS = hashSetOf(
            "teh", "recieve", "recieved", "definately", "alot", "untill", "seperate", "seperately", "occured",
            "thier", "wierd", "accomodate", "acheive", "adress", "arguement", "begining", "beleive", "calender",
            "cemetary", "commited", "concious", "existance", "foriegn", "goverment", "grammer", "harrass",
            "independant", "knowlege", "liason", "millenium", "neccessary", "noticable", "occurence", "persistant",
            "posession", "prefered", "recomend", "refered", "relevent", "religous", "succesful", "tommorow",
            "tomorow", "truely", "wich", "writting", "freind", "freinds", "beggining", "beautifull", "bizzare",
            "embarass", "enviroment", "finaly", "garantee", "happend", "immediatly", "interupt", "neice",
            "occassion", "publically", "realy", "reccomend", "rythm", "sieze", "suprise", "suprised", "tounge",
            "wether", "wierdo", "becuase", "becasue", "probaly", "remeber", "basicly", "comming",
        )

        fun normalize(word: String): String {
            return word.lowercase().replace('’', '\'')
        }

        fun isAdjacent(a: Char, b: Char): Boolean {
            if (a !in 'a'..'z' || b !in 'a'..'z') return false
            return ADJACENT[a - 'a'][b - 'a']
        }

        /** Cost of a character the user typed that the word does not have. */
        private fun extraCost(c: Char): Double {
            return if (c == '\'') APOSTROPHE_EDIT_COST else 1.0
        }

        /** Cost of a character the word has that the user did not type. */
        private fun omissionCost(c: Char): Double {
            return if (c == '\'') APOSTROPHE_EDIT_COST else OMISSION_COST
        }

        private fun substituteCost(a: Char, b: Char): Double {
            return when {
                a == b -> 0.0
                isAdjacent(a, b) -> ADJACENT_SUBSTITUTE_COST
                else -> 1.0
            }
        }
    }

    private val baseFreq = HashMap<String, Int>(dictionary.size * 2)
    private val sortedWords: Array<String>
    private val wordsByLength: Array<ArrayList<String>> = Array(MAX_WORD_LENGTH + 1) { ArrayList() }
    private val wordsByFirstChar = HashMap<Char, ArrayList<String>>()
    private val minFreq: Int
    private val freqRange: Double

    private val userUnigrams = HashMap<String, Int>()
    private val userBigrams = HashMap<String, HashMap<String, Int>>()
    private val userCasing = HashMap<String, String>()
    private val blocked = HashSet<String>()

    private var rowPrev2 = DoubleArray(MAX_WORD_LENGTH + 2)
    private var rowPrev = DoubleArray(MAX_WORD_LENGTH + 2)
    private var rowCur = DoubleArray(MAX_WORD_LENGTH + 2)

    /** Set whenever the user model changes, cleared by the caller after persisting. */
    var isUserModelDirty: Boolean = false

    init {
        for ((word, freq) in dictionary) {
            val normalized = normalize(word)
            if (normalized.isBlank() || normalized.length > MAX_WORD_LENGTH) continue
            baseFreq[normalized] = max(baseFreq[normalized] ?: 0, freq)
        }
        for (word in baseFreq.keys.filter { isContraction(it) }) {
            val bare = word.replace("'", "")
            if (bare !in CONTRACTION_LOOKALIKES) {
                baseFreq.remove(bare)
            }
        }
        for (word in KNOWN_MISSPELLINGS) {
            baseFreq.remove(word)
        }
        sortedWords = baseFreq.keys.toTypedArray().also { it.sort() }
        for (word in sortedWords) {
            wordsByLength[word.length].add(word)
            wordsByFirstChar.getOrPut(word[0]) { ArrayList() }.add(word)
        }
        minFreq = baseFreq.values.minOrNull() ?: 0
        val maxFreq = baseFreq.values.maxOrNull() ?: 1
        freqRange = max(1, maxFreq - minFreq).toDouble()
    }

    val dictionarySize: Int
        get() = baseFreq.size

    fun isKnownWord(word: String): Boolean {
        val normalized = normalize(word)
        return normalized in baseFreq || (userUnigrams[normalized] ?: 0) > 0
    }

    private fun isSuggestable(word: String): Boolean {
        if (word in blocked) return false
        if (word.length == 1 && word != "a" && word != "i") return false
        return word in baseFreq || (userUnigrams[word] ?: 0) >= USER_WORD_MIN_COUNT
    }

    private fun isOffensive(word: String): Boolean {
        return word in POSSIBLY_OFFENSIVE
    }

    private fun unigramScore(word: String): Double {
        val freq = baseFreq[word]
        val base = if (freq != null) (freq - minFreq) / freqRange else 0.0
        val userCount = userUnigrams[word] ?: 0
        val userBoost = if (userCount > 0) min(1.0, ln(1.0 + userCount) / ln(21.0)) else 0.0
        return base + USER_WEIGHT * userBoost
    }

    private fun bigramScore(previous: String?, word: String): Double {
        if (previous == null) return 0.0
        val followers = userBigrams[previous] ?: return 0.0
        val count = followers[word] ?: return 0.0
        val total = followers.values.sum().toDouble()
        val confidence = min(1.0, ln(1.0 + count) / ln(6.0))
        return (count / total) * confidence
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = sortedWords.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedWords[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Weighted optimal string alignment distance. Neighboring keys are cheap substitutions, swapped letters are
     * cheap transpositions and apostrophes are almost free to add or drop ("dont" -> "don't").
     *
     * @return The distance, or [Double.MAX_VALUE] as soon as it is certain to exceed [maxDistance].
     */
    fun distance(a: String, b: String, maxDistance: Double): Double {
        val n = a.length
        val m = b.length
        if (n > MAX_WORD_LENGTH || m > MAX_WORD_LENGTH) return Double.MAX_VALUE
        var prev2 = rowPrev2
        var prev = rowPrev
        var cur = rowCur
        prev[0] = 0.0
        for (j in 1..m) {
            prev[j] = prev[j - 1] + omissionCost(b[j - 1])
        }
        for (i in 1..n) {
            val ca = a[i - 1]
            cur[0] = prev[0] + extraCost(ca)
            var rowMin = cur[0]
            for (j in 1..m) {
                val cb = b[j - 1]
                var value = min(prev[j] + extraCost(ca), cur[j - 1] + omissionCost(cb))
                value = min(value, prev[j - 1] + substituteCost(ca, cb))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb && ca != cb) {
                    value = min(value, prev2[j - 2] + TRANSPOSE_COST)
                }
                cur[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > maxDistance) {
                rowPrev2 = prev2
                rowPrev = prev
                rowCur = cur
                return Double.MAX_VALUE
            }
            val tmp = prev2
            prev2 = prev
            prev = cur
            cur = tmp
        }
        val result = prev[m]
        rowPrev2 = prev2
        rowPrev = prev
        rowCur = cur
        return result
    }

    private fun maxTypoDistance(length: Int): Double {
        return when {
            length <= 3 -> 1.0
            length <= 6 -> 1.5
            else -> 2.0
        }
    }

    /**
     * Suggests words for the partially typed [input].
     *
     * @param previous The previous word as returned by [LatinTextAnalysis.previousWord].
     */
    fun suggest(
        input: String,
        previous: String?,
        maxCount: Int,
        allowPossiblyOffensive: Boolean,
    ): List<String> {
        return suggestDetailed(input, previous, maxCount, allowPossiblyOffensive).words
    }

    /**
     * Like [suggest], but also decides whether the typed word should be corrected automatically when the user ends
     * it. Only words the engine does not know get corrected, and only to a full-word typo correction that clearly
     * beats the alternatives. When there is one, it is the first entry of [LatinSuggestionResult.words].
     */
    fun suggestDetailed(
        input: String,
        previous: String?,
        maxCount: Int,
        allowPossiblyOffensive: Boolean,
    ): LatinSuggestionResult {
        val query = normalize(input)
        if (query.isEmpty()) {
            return LatinSuggestionResult(predictNext(previous, maxCount, allowPossiblyOffensive), null)
        }
        if (query.length > MAX_WORD_LENGTH || maxCount <= 0) return LatinSuggestionResult(emptyList(), null)

        val scores = HashMap<String, Double>()
        // Full-word typo corrections and their edit distance, the only candidates eligible for auto-correction
        val corrections = HashMap<String, Double>()
        fun offer(word: String, score: Double, correctionDistance: Double? = null) {
            if (!isSuggestable(word)) return
            if (!allowPossiblyOffensive && word != query && isOffensive(word)) return
            val existing = scores[word]
            if (existing == null || score > existing) {
                scores[word] = score
                if (correctionDistance != null) corrections[word] = correctionDistance else corrections.remove(word)
            }
        }
        fun contextScore(word: String): Double {
            return unigramScore(word) + BIGRAM_WEIGHT * bigramScore(previous, word)
        }
        fun completionPenalty(extraChars: Int): Double {
            return min(COMPLETION_PENALTY_MAX, COMPLETION_PENALTY_PER_CHAR * extraChars)
        }

        // Exact matches and completions
        var index = lowerBound(query)
        var scanned = 0
        while (index < sortedWords.size && scanned < PREFIX_SCAN_LIMIT) {
            val word = sortedWords[index]
            if (!word.startsWith(query)) break
            val extra = word.length - query.length
            offer(word, contextScore(word) - completionPenalty(extra) + if (extra == 0) EXACT_BONUS else 0.0)
            index++
            scanned++
        }
        for ((word, count) in userUnigrams) {
            if (count < USER_WORD_MIN_COUNT || word in baseFreq || !word.startsWith(query)) continue
            val extra = word.length - query.length
            offer(word, contextScore(word) - completionPenalty(extra) + if (extra == 0) EXACT_BONUS else 0.0)
        }

        // Typo corrections
        if (query.length in 2..MAX_TYPO_INPUT_LENGTH) {
            val maxDistance = maxTypoDistance(query.length)
            val minLength = max(1, query.length - 2)
            val maxLength = min(MAX_WORD_LENGTH, query.length + 2)
            for (length in minLength..maxLength) {
                for (word in wordsByLength[length]) {
                    val d = distance(query, word, maxDistance)
                    if (d > 0.0 && d <= maxDistance) {
                        offer(word, contextScore(word) - TYPO_PENALTY * d, d)
                    }
                }
            }
            if (query.length >= 3) {
                val prefixMaxDistance = maxTypoDistance(query.length - 1)
                val candidates = wordsByFirstChar[query[0]].orEmpty()
                for (word in candidates) {
                    if (word.length <= query.length) continue
                    var d = distance(query, word.substring(0, query.length), prefixMaxDistance)
                    if (word.length > query.length + 1) {
                        // The user may have skipped a letter, so the typed text maps to one more character
                        d = min(d, distance(query, word.substring(0, query.length + 1), prefixMaxDistance))
                    }
                    if (d > 0.0 && d <= prefixMaxDistance) {
                        val extra = word.length - query.length
                        offer(
                            word,
                            contextScore(word) - TYPO_PENALTY * d - completionPenalty(extra) - FUZZY_PREFIX_PENALTY,
                        )
                    }
                }
            }
            for ((word, count) in userUnigrams) {
                if (count < USER_WORD_MIN_COUNT || word in baseFreq) continue
                val d = distance(query, word, maxDistance)
                if (d > 0.0 && d <= maxDistance) {
                    offer(word, contextScore(word) - TYPO_PENALTY * d, d)
                }
            }
        }

        val ranked = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .toMutableList()

        val autoCorrectWord = pickAutoCorrection(input, query, ranked, corrections)
        if (autoCorrectWord != null) {
            val entry = ranked.first { it.key == autoCorrectWord }
            ranked.remove(entry)
            ranked.add(0, entry)
        }

        val sentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        val words = ranked
            .asSequence()
            .take(maxCount)
            .map { applyCase(it.key, input, sentenceStart) }
            .distinct()
            .toList()
        return LatinSuggestionResult(
            words = words,
            autoCorrection = autoCorrectWord?.let { applyCase(it, input, sentenceStart) },
        )
    }

    private fun pickAutoCorrection(
        input: String,
        query: String,
        ranked: List<Map.Entry<String, Double>>,
        corrections: Map<String, Double>,
    ): String? {
        if (query.length < 2 || ranked.isEmpty()) return null
        if (query.any { !LatinTextAnalysis.isWordChar(it) }) return null
        if (isKnownWord(query)) return null
        if (query.endsWith("'s") && isKnownWord(query.dropLast(2))) return null
        // Mixed case like "iPhone" or "McDonald" is deliberate, leave it alone
        if (input.drop(1).any { it.isUpperCase() } && !input.all { !it.isLetter() || it.isUpperCase() }) return null
        val correctionEntries = ranked.filter { it.key in corrections }
        val best = correctionEntries.firstOrNull() ?: return null
        // Only small slips get fixed silently, anything bigger is likely a name, slang or gibberish
        val maxAutoDistance = if (query.length <= 5) 1.0 else 1.5
        if (corrections.getValue(best.key) > maxAutoDistance) return null
        val topScore = ranked.first().value
        if (best.value < topScore - AUTO_CORRECT_MAX_RANK_GAP) return null
        val runnerUp = correctionEntries.getOrNull(1)
        if (runnerUp != null && best.value - runnerUp.value < AUTO_CORRECT_MIN_MARGIN) return null
        return best.key
    }

    /**
     * Predicts the next word purely from what the user has typed after [previous] before.
     */
    fun predictNext(previous: String?, maxCount: Int, allowPossiblyOffensive: Boolean): List<String> {
        if (previous == null || maxCount <= 0) return emptyList()
        val followers = userBigrams[previous] ?: return emptyList()
        val sentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        return followers.entries
            .asSequence()
            .filter { (word, _) -> isSuggestable(word) }
            .filter { (word, _) -> allowPossiblyOffensive || !isOffensive(word) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { unigramScore(it.key) }
                    .thenBy { it.key }
            )
            .take(maxCount)
            .map { applyCase(it.key, "", sentenceStart) }
            .toList()
    }

    /**
     * Returns null if [word] is spelled correctly, else a (possibly empty) list of corrections.
     */
    fun spellCheck(word: String, maxCount: Int): List<String>? {
        val query = normalize(word)
        if (query.isEmpty() || query.any { !LatinTextAnalysis.isWordChar(it) }) return null
        if (isKnownWord(query)) return null
        if (query.endsWith("'s") && isKnownWord(query.dropLast(2))) return null
        if (query.length > MAX_TYPO_INPUT_LENGTH) return emptyList()
        val maxDistance = maxTypoDistance(query.length)
        val scores = HashMap<String, Double>()
        val minLength = max(1, query.length - 2)
        val maxLength = min(MAX_WORD_LENGTH, query.length + 2)
        for (length in minLength..maxLength) {
            for (candidate in wordsByLength[length]) {
                if (candidate in blocked) continue
                val d = distance(query, candidate, maxDistance)
                if (d <= maxDistance) {
                    scores[candidate] = unigramScore(candidate) - TYPO_PENALTY * d
                }
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(maxCount)
            .map { applyCase(it.key, word, false) }
    }

    /**
     * Records that the user typed [rawWord] after [previous].
     *
     * @return True if the user model changed.
     */
    fun learn(previous: String?, rawWord: String): Boolean {
        val word = normalize(rawWord.trim('\'', '’'))
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return false
        if (word.any { !LatinTextAnalysis.isWordChar(it) }) return false
        blocked.remove(word)
        userUnigrams[word] = min(MAX_COUNT, (userUnigrams[word] ?: 0) + 1)
        val atSentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        if (!atSentenceStart) {
            val display = rawWord.trim('\'', '’').replace('’', '\'')
            if (display != word && display != word.uppercase()) {
                userCasing[word] = display
            } else if (display == word) {
                userCasing.remove(word)
            }
        }
        if (previous != null) {
            val followers = userBigrams.getOrPut(previous) { HashMap() }
            followers[word] = min(MAX_COUNT, (followers[word] ?: 0) + 1)
            if (followers.size > MAX_BIGRAMS_PER_ROOT) {
                val weakest = followers.entries.minWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                followers.remove(weakest.key)
            }
        }
        prune()
        isUserModelDirty = true
        return true
    }

    /**
     * Marks [rawWord] as a word the user wants, so it is suggested and never auto-corrected. Used when the user
     * undoes an auto-correction of it.
     */
    fun acceptWord(rawWord: String): Boolean {
        val word = normalize(rawWord.trim('\'', '’'))
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return false
        if (word.any { !LatinTextAnalysis.isWordChar(it) }) return false
        blocked.remove(word)
        userUnigrams[word] = max(userUnigrams[word] ?: 0, USER_WORD_MIN_COUNT)
        isUserModelDirty = true
        return true
    }

    /**
     * Removes [rawWord] from all suggestions until the user types it again.
     */
    fun block(rawWord: String): Boolean {
        val word = normalize(rawWord)
        if (word.isEmpty()) return false
        blocked.add(word)
        userUnigrams.remove(word)
        userCasing.remove(word)
        for (followers in userBigrams.values) {
            followers.remove(word)
        }
        isUserModelDirty = true
        return true
    }

    private fun prune() {
        if (userUnigrams.size > MAX_USER_UNIGRAMS) {
            val excess = userUnigrams.size - MAX_USER_UNIGRAMS * 9 / 10
            userUnigrams.entries
                .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(excess)
                .map { it.key }
                .forEach {
                    userUnigrams.remove(it)
                    userCasing.remove(it)
                }
        }
        if (userBigrams.size > MAX_USER_BIGRAM_ROOTS) {
            val excess = userBigrams.size - MAX_USER_BIGRAM_ROOTS * 9 / 10
            userBigrams.entries
                .sortedWith(compareBy<Map.Entry<String, HashMap<String, Int>>> { it.value.values.sum() }.thenBy { it.key })
                .take(excess)
                .map { it.key }
                .forEach { userBigrams.remove(it) }
        }
    }

    private fun applyCase(word: String, input: String, sentenceStart: Boolean): String {
        val display = userCasing[word] ?: when {
            word == "i" -> "I"
            word.startsWith("i'") -> "I" + word.substring(1)
            else -> word
        }
        val letters = input.filter { it.isLetter() }
        return when {
            letters.length >= 2 && letters.all { it.isUpperCase() } -> display.uppercase()
            letters.firstOrNull()?.isUpperCase() == true -> display.replaceFirstChar { it.uppercaseChar() }
            input.isEmpty() && sentenceStart -> display.replaceFirstChar { it.uppercaseChar() }
            else -> display
        }
    }

    fun exportUserData(): LatinUserModelData {
        return LatinUserModelData(
            unigrams = HashMap(userUnigrams),
            bigrams = userBigrams.mapValues { (_, followers) -> HashMap(followers) },
            casing = HashMap(userCasing),
            blocked = blocked.sorted(),
        )
    }

    fun importUserData(data: LatinUserModelData) {
        userUnigrams.clear()
        userBigrams.clear()
        userCasing.clear()
        blocked.clear()
        for ((word, count) in data.unigrams) {
            if (word.isNotEmpty() && count > 0) userUnigrams[word] = min(MAX_COUNT, count)
        }
        for ((previous, followers) in data.bigrams) {
            val map = HashMap<String, Int>()
            for ((word, count) in followers) {
                if (word.isNotEmpty() && count > 0) map[word] = min(MAX_COUNT, count)
            }
            if (map.isNotEmpty()) userBigrams[previous] = map
        }
        userCasing.putAll(data.casing)
        blocked.addAll(data.blocked)
        prune()
        isUserModelDirty = false
    }
}
