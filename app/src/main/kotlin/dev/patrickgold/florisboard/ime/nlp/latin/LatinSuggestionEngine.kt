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
 * An automatic correction to apply when the user ends the word being typed.
 *
 * @property replacement The text that replaces [replacedText].
 * @property replacedText The exact text directly before the cursor that gets replaced. Usually the word being typed,
 *  but two fragments when an accidental space is undone ("abo ut" becomes "about").
 */
data class LatinAutoCorrection(
    val replacement: String,
    val replacedText: String,
)

/**
 * Everything the suggestion bar needs for the word being typed, computed in a single pass.
 *
 * @property words Suggestions, best first.
 * @property autoCorrection What [LatinSuggestionEngine.autoCorrect] would do if the word were ended now.
 */
data class LatinAnalysis(
    val words: List<String>,
    val autoCorrection: LatinAutoCorrection?,
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
        var c = textBeforeWord[i - 1]
        if (isSentenceEnd(c)) {
            return SENTENCE_START
        }
        // A comma, semicolon or colon keeps the context: in "hey, how" the word before "how" is "hey"
        if (c == ',' || c == ';' || c == ':') {
            i--
            if (i == 0) return if (hasMoreBefore) null else SENTENCE_START
            c = textBeforeWord[i - 1]
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
class LatinSuggestionEngine(
    dictionary: Map<String, Int>,
    private val baseBigrams: LatinBigramModel = LatinBigramModel.Empty,
    unverifiedWords: Set<String> = emptySet(),
    validWords: Set<String> = emptySet(),
) {
    companion object {
        const val MAX_WORD_LENGTH = 32

        private const val PREFIX_SCAN_LIMIT = 12_000
        private const val MAX_TYPO_INPUT_LENGTH = 20

        private const val EXACT_BONUS = 0.25
        private const val COMPLETION_PENALTY_PER_CHAR = 0.03
        private const val COMPLETION_PENALTY_MAX = 0.3
        private const val TYPO_PENALTY = 0.8
        private const val FUZZY_PREFIX_PENALTY = 0.1
        private const val BIGRAM_WEIGHT = 1.2
        private const val USER_WEIGHT = 0.6
        /** Weight of the static English next-word model, below the user's own history. */
        private const val BASE_BIGRAM_WEIGHT = 0.5
        /**
         * Penalty weight for words that clearly do not fit after the previous one when ranking completions and typo
         * fixes. Only a bad fit counts here: a good fit is already rewarded through the next-word probability.
         */
        private const val CONTEXT_MISFIT_WEIGHT = 1.0
        /**
         * A word 2^this times more or less likely after the previous word than in general fits fully, or not at all.
         * Ungrammatical pairs like "the was" or "a the" sit around -8, set phrases like "day to day" only near -2.
         */
        private const val CONTEXT_FIT_SCALE = 8.0
        /** Weight of how well a word fits after the previous one when deciding to replace a real word. */
        private const val CONTEXT_FIT_WEIGHT = 0.4
        /**
         * Fitting this badly after the previous word (about 2^5 times rarer there than in general) means English
         * practically never writes the pair ("the was"), so such a word is never used as a replacement for a real
         * word, and a real word typed there becomes suspicious.
         */
        private const val CONTEXT_MISFIT = -0.6
        /** A contraction lookalike is only replaced when its replacement is strongly expected after the previous word. */
        private const val LOOKALIKE_MIN_FIT = 0.4

        /**
         * A dictionary word is still auto-corrected when a far more common word is one slip away ("fir" to "for").
         * Unverified entries such as names and scraped noise need a smaller margin than real words.
         */
        private const val KNOWN_WORD_CORRECTION_MARGIN = 0.45
        private const val UNVERIFIED_WORD_CORRECTION_MARGIN = 0.2
        /** Only one neighboring key, swapped pair or missing apostrophe away counts as a slip for known words. */
        private const val KNOWN_WORD_MAX_DISTANCE = 0.75
        /** The replacement for a known word must itself be a common word. */
        private const val KNOWN_WORD_MIN_REPLACEMENT_SCORE = 0.35

        /** Penalty for reading a typed space as a missed bottom row key ("pro lem" as "problem"). */
        private const val MERGE_KEY_PENALTY = 0.1
        /** A merged word must be at least this common, so fragments never merge into rare words. */
        private const val MERGE_MIN_SCORE = 0.3
        /** Letters next to the space bar that a typed space may stand for. */
        private const val MERGE_SPACE_BAR_KEYS = "cvbnm"

        /** Unverified words rarer than this get the smaller correction margin, common ones count as real words. */
        private const val RARE_UNVERIFIED_SCORE = 0.3

        /**
         * Informal spellings that are in the dictionary on purpose and must never be auto-corrected, even though
         * each is one slip away from a common word ("ur" and "our").
         */
        private val PROTECTED_SLANG = hashSetOf(
            "u", "ur", "ya", "yea", "yeah", "nah", "lol", "lmao", "lmfao", "rofl", "idk", "tbh", "imo", "imho", "omg",
            "btw", "thx", "pls", "plz", "gonna", "wanna", "gotta", "kinda", "sorta", "dunno", "cuz", "bc", "rn", "ngl",
            "fr", "af", "smh", "wtf", "irl", "iirc", "afaik", "ty", "np", "ok", "okay", "hmu", "brb", "gtg", "ikr",
            "jk", "nvm", "ppl", "tho", "thru", "til", "tl", "dr", "ama", "op", "eli", "fyi", "asap", "aight", "ain't",
            "y'all", "lemme", "gimme", "outta", "hella", "yall", "bro", "bruh", "sis", "fam", "vibe", "vibes", "meme",
            "nite", "tonite", "msg", "pic", "pics", "vid", "vids",
        )

        /**
         * Words that are easy to mix up and are told apart by the word that follows them. A contraction is judged
         * by the words it stands for: "it's" by what follows "is" or "has", "they're" and "you're" by what
         * follows "are". On the left, the contraction is judged by its first word.
         */
        private val CONFUSION_SETS: Map<String, List<String>> = listOf(
            listOf("its", "it's"),
            listOf("their", "there", "they're"),
            listOf("your", "you're"),
        ).flatMap { set -> set.map { it to set } }.toMap()
        private val RIGHT_PROXIES = mapOf(
            "it's" to listOf("is", "has"), "they're" to listOf("are"), "you're" to listOf("are"),
        )
        private val LEFT_PROXIES = mapOf("it's" to "it", "they're" to "they", "you're" to "you")
        /**
         * How much more likely each word is in everyday typing than the book-based word pairs suggest, in bits.
         * "it's" is far more common in messages than in books, "its" far less.
         */
        private val CONFUSION_PRIORS = mapOf("it's" to 1.0, "its" to -1.0)
        /** At the start of a sentence "it's" is far more common still, possessive "its" rarely starts one. */
        private val SENTENCE_START_PRIORS = mapOf("it's" to 2.0)
        /** A confusable word is swapped when another one of its set fits this many bits better. */
        private const val CONFUSABLE_SWITCH_BITS = 1.5
        /**
         * A typed apostrophe is deliberate, so a contraction only loses it when it clearly does not fit before the
         * next word ("it's own") and the other word fits far better. "it's way better" stays.
         */
        private const val APOSTROPHE_SWITCH_BITS = 4.0
        private const val APOSTROPHE_MISFIT_BITS = -2.0

        /**
         * Scales used to compare words in bits when looking back: the unigram score spans about 10.5 bits of
         * frequency, and each unit of edit distance is penalized like [TYPO_PENALTY] in the same scale.
         */
        private const val UNIGRAM_BITS = 10.5
        private const val TYPO_BITS = 4.7
        /** A real word typed as is gets looked at again only if it fits this badly before the next word. */
        private const val LOOKBACK_RIGHT_MISFIT_BITS = -3.0
        /** How much better a replacement must fit, in bits, when the word was a correction or unknown. */
        private const val LOOKBACK_SWITCH_BITS = 3.0
        /** How much better a replacement must fit, in bits, when the word was a real word typed on purpose. */
        private const val LOOKBACK_REAL_WORD_SWITCH_BITS = 4.0
        /** A replacement that fits this badly after the previous word is never used when looking back. */
        private const val LOOKBACK_LEFT_MISFIT_BITS = -3.0

        private const val SPELL_CHECK_MAX_DISTANCE = 1.5

        /** How many of the most likely English followers of a word are considered for predictions. */
        private const val PREDICTION_FOLLOWER_LIMIT = 300

        /** Widest typo search for words shorter than [LONG_WORD_LENGTH]. */
        private const val NORMAL_MAX_TYPO_DISTANCE = 2.0
        /** Words this long get a wider typo search, since fast typing slips more keys on them. */
        private const val LONG_WORD_LENGTH = 8
        /** Margin over the runner-up needed to fix a word with more than one or two slips. */
        private const val LONG_TYPO_MIN_MARGIN = 0.1
        /** How strongly the next word must call for "I" before a lone letter becomes one, in bits. */
        private const val LONE_I_MIN_RIGHT_BITS = 3.0

        /** "i" and its contractions, always written with a capital I. */
        private val I_FORMS = hashSetOf("i", "i'm", "i'll", "i'd", "i've")

        /** Likely first words of a sentence, used for predictions before the user has typed anything. */
        private val SENTENCE_STARTERS = listOf("i", "the", "it", "this", "thanks", "so", "what", "we")

        /** An auto-correction may score at most this much below the overall top suggestion. */
        private const val AUTO_CORRECT_MAX_RANK_GAP = 0.2
        /** The chosen auto-correction must beat the next best correction by at least this much. */
        private const val AUTO_CORRECT_MIN_MARGIN = 0.03

        /** A single-slip correction beats a missing-space split when it fits this many bits better after the previous word. */
        private const val SPLIT_CONTEXT_SWITCH_BITS = 3.0

        /** Inputs shorter than this are never split into two words. */
        private const val MIN_SPLIT_INPUT_LENGTH = 4
        /** Score penalty for reading the input as two words with a missing space. */
        private const val SPLIT_PENALTY = 0.1
        /**
         * Extra penalty when a letter next to the space bar is read as a missed space. Kept high so that keeping
         * every typed letter wins whenever it also gives two words ("thecat" is "the cat", not "the at").
         */
        private const val SPLIT_SPACE_MISS_PENALTY = 0.3
        /** Edit distance assigned to splits, so auto-correct treats them like a one-key slip. */
        private const val SPLIT_DISTANCE = 1.0
        /** Each part of a split must be at least this common, so rare or junk words never form a split. */
        private const val SPLIT_MIN_PART_SCORE = 0.25

        /** Bottom row letters that a thumb aiming for the space bar may hit instead. */
        private const val SPACE_BAR_NEIGHBORS = "vbnm"

        /**
         * One and two letter words allowed as part of a split. The dictionary has hundreds of two letter entries
         * that are abbreviations or noise, so these are listed explicitly.
         */
        private val SPLIT_SHORT_WORDS = hashSetOf(
            "a", "i", "of", "to", "in", "is", "it", "on", "be", "as", "at", "he", "by", "or", "an", "my", "we", "so",
            "me", "if", "up", "no", "do", "go", "us", "am", "oh", "hi", "ok",
        )

        /**
         * Two words glued together that the web-scraped dictionary lists as words. Removing them lets the split
         * correction fix them. Glued forms that are real words ("todo", "tome", "whatnot") are not listed.
         */
        private val GLUED_PHRASES = hashSetOf(
            "ofthe", "inthe", "thankyou", "noone", "upto", "howto", "goto", "asif", "itis", "ofa", "ifa", "ina", "ona",
            "toa", "isa", "ita", "ato", "aan", "ais", "ait", "aso", "doa", "upa", "cana", "mya", "anda", "soa", "noa",
            "wea", "beit", "forme", "tous", "atleast", "aswell", "infact", "eachother", "alittle", "incase", "inorder",
            "ontop", "everytime", "iam", "idont", "ofcourse", "inbetween",
        )

        /** Common words missing from the bundled dictionary, which would otherwise be split or corrected. */
        private val SUPPLEMENTAL_WORDS = mapOf(
            "subreddit" to 170, "subreddits" to 165, "upvote" to 170, "upvoted" to 165, "upvotes" to 165,
            "downvote" to 170, "downvoted" to 165, "downvotes" to 165, "modding" to 165, "modder" to 160,
            "modders" to 160, "codebase" to 165, "frontend" to 165,
        )

        private const val TRANSPOSE_COST = 0.75
        /**
         * Touch based substitution cost: [SPATIAL_BASE_COST] plus [SPATIAL_COST_PER_SQUARED_KEY] for every squared key
         * the intended key is farther from the touch than the typed key. A tap dead center on K costs about 0.6 to
         * read as I, a tap on K's top edge about 0.2.
         */
        private const val SPATIAL_BASE_COST = 0.1
        private const val SPATIAL_COST_PER_SQUARED_KEY = 0.4
        private const val SPATIAL_MIN_COST = 0.15
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
        /** Words the user typed this many times, or kept by undoing a correction, are never auto-corrected. */
        const val USER_WORD_PROTECT_COUNT = 3

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
    // Contractions by their apostrophe-less form, e.g. "dont" to "don't", so split parts can be written properly
    private val contractionsByBareForm = HashMap<String, String>()
    private val sortedWords: Array<String>
    private val wordsByLength: Array<ArrayList<String>> = Array(MAX_WORD_LENGTH + 1) { ArrayList() }
    /** Letter sets of [wordsByLength], see [letterMask]. Lets the typo search skip most words without comparing. */
    private lateinit var masksByLength: Array<IntArray>
    private val wordsByFirstChar = HashMap<Char, ArrayList<String>>()
    private val minFreq: Int
    private val freqRange: Double

    private val unverified = HashSet<String>(unverifiedWords)

    /**
     * Real words the dictionary lacks ("wagged"), which autocorrect leaves alone but never suggests. Bare contraction
     * spellings ("cant") and glued phrases stay correctable even if the word list has them.
     */
    private val validOnly: Set<String> by lazy {
        validWords.filterTo(HashSet()) {
            it !in baseFreq && it !in contractionsByBareForm && it !in GLUED_PHRASES && it !in KNOWN_MISSPELLINGS
        }
    }

    private val userUnigrams = HashMap<String, Int>()
    private val userBigrams = HashMap<String, HashMap<String, Int>>()
    private val userCasing = HashMap<String, String>()
    private val blocked = HashSet<String>()

    // Touch points of the query being searched, see suggestDetailed. Only set while that search runs.
    private var spatialQuery: String? = null
    /** Substitution cost per typed position and intended letter a to z, NaN where the touch says nothing. */
    private var spatialCosts: Array<DoubleArray> = emptyArray()
    /** Letters that, going by the touch points, are cheap to have meant at one of the typed positions. */
    private var spatialCheapMask = 0
    /** Per typed letter a to z, the letters it is cheap to have meant instead at some position. */
    private val spatialCheapFor = IntArray(26)

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
                contractionsByBareForm[bare] = word
            }
        }
        for (word in KNOWN_MISSPELLINGS) {
            baseFreq.remove(word)
        }
        for (word in GLUED_PHRASES) {
            baseFreq.remove(word)
        }
        for ((word, freq) in SUPPLEMENTAL_WORDS) {
            baseFreq[word] = max(baseFreq[word] ?: 0, freq)
        }
        sortedWords = baseFreq.keys.toTypedArray().also { it.sort() }
        for (word in sortedWords) {
            wordsByLength[word.length].add(word)
            wordsByFirstChar.getOrPut(word[0]) { ArrayList() }.add(word)
        }
        masksByLength = Array(wordsByLength.size) { length ->
            val words = wordsByLength[length]
            IntArray(words.size) { letterMask(words[it], words[it].length) }
        }
        minFreq = baseFreq.values.minOrNull() ?: 0
        val maxFreq = baseFreq.values.maxOrNull() ?: 1
        freqRange = max(1, maxFreq - minFreq).toDouble()
    }

    val dictionarySize: Int
        get() = baseFreq.size

    fun isKnownWord(word: String): Boolean {
        val normalized = normalize(word)
        return normalized in baseFreq || normalized in validOnly || isUserWord(normalized)
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

    /** Static English P(word | previous), scaled to 0 to 1 on a log curve so rare pairs still count a little. */
    private fun baseBigramScore(previous: String?, word: String): Double {
        val p = baseBigramProbability(previous, word)
        if (p <= 0.0) return 0.0
        return min(1.0, ln(1.0 + 50.0 * p) / ln(51.0))
    }

    private fun baseBigramProbability(previous: String?, word: String): Double {
        if (previous == null || previous == LatinTextAnalysis.SENTENCE_START) return 0.0
        return baseBigrams.probability(previous, word)
    }

    /**
     * How well [word] fits right after [previous], from -1 (English practically never puts it there, "the was")
     * to 1 (far more common there than anywhere else, "it was"). 0 when nothing is known, which is the case for
     * rare words. Pairs the user typed before always count as fitting.
     */
    private fun contextFit(previous: String?, word: String): Double {
        if (previous == null || previous == LatinTextAnalysis.SENTENCE_START) return 0.0
        val lift = baseBigrams.lift(previous, word)
        val fit = if (lift == null) 0.0 else (lift / CONTEXT_FIT_SCALE).coerceIn(-1.0, 1.0)
        return if (bigramScore(previous, word) > 0.0) max(fit, 0.5) else fit
    }

    /**
     * How likely [word] is right after [previous]: its overall frequency, the user's own word pairs, how often
     * English puts it there, and a penalty if it practically never goes there ("the like").
     */
    private fun contextScore(previous: String?, word: String): Double {
        return unigramScore(word) + BIGRAM_WEIGHT * bigramScore(previous, word) +
            BASE_BIGRAM_WEIGHT * baseBigramScore(previous, word) +
            CONTEXT_MISFIT_WEIGHT * min(0.0, contextFit(previous, word) - CONTEXT_MISFIT)
    }

    /** True for words the user typed or kept, which auto-correct must leave alone. */
    /**
     * True for words the user clearly means, typed [USER_WORD_PROTECT_COUNT] times or kept by undoing a correction.
     * A single use does not count, otherwise a typo that once slipped through would be protected for good.
     */
    private fun isUserWord(word: String): Boolean = (userUnigrams[word] ?: 0) >= USER_WORD_PROTECT_COUNT

    private fun isUnverified(word: String): Boolean = word in unverified && !isUserWord(word)

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
    fun distance(a: String, b: String, maxDistance: Double): Double = distance(a, b, b.length, maxDistance)

    /** Like [distance], comparing [a] to only the first [bLength] characters of [b], without copying them. */
    private fun distance(a: String, b: String, bLength: Int, maxDistance: Double): Double {
        val n = a.length
        val m = bLength
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
                val subCost = if (a === spatialQuery) spatialSubstituteCost(i - 1, ca, cb) else substituteCost(ca, cb)
                value = min(value, prev[j - 1] + subCost)
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

    /**
     * Cost of reading the typed character at [index] as [intended], using where the finger actually landed. A tap on
     * the edge of a key toward its neighbor makes the neighbor cheap, a tap in the middle of the key makes any other
     * key more expensive than the plain neighboring-key cost. Same idea as the proximity model of AOSP LatinIME.
     */
    private fun spatialSubstituteCost(index: Int, typed: Char, intended: Char): Double {
        if (typed == intended) return 0.0
        val letter = intended - 'a'
        if (index >= spatialCosts.size || letter !in 0 until 26) return substituteCost(typed, intended)
        val cost = spatialCosts[index][letter]
        return if (cost.isNaN()) substituteCost(typed, intended) else cost
    }

    /** Cost of the typed letter at a position being meant as [intended], given where it was tapped. */
    private fun touchCost(trace: LatinTouchTrace, touch: LatinKeyTouch, typed: Char, intended: Char): Double {
        val toIntended = trace.layout.distanceSquared(touch, intended) ?: return Double.NaN
        val toTyped = trace.layout.distanceSquared(touch, typed) ?: return Double.NaN
        return (SPATIAL_BASE_COST + SPATIAL_COST_PER_SQUARED_KEY * (toIntended - toTyped))
            .coerceIn(SPATIAL_MIN_COST, 1.0)
    }

    /** Fills the touch based cost tables for [query], see [spatialCosts]. */
    private fun prepareSpatial(query: String, trace: LatinTouchTrace) {
        spatialQuery = query
        spatialCheapMask = 0
        spatialCheapFor.fill(0)
        spatialCosts = Array(query.length) { index ->
            val costs = DoubleArray(26) { Double.NaN }
            val touch = trace.touches[index]
            val typed = query[index]
            if (touch != null && typed in 'a'..'z') {
                for (c in 'a'..'z') {
                    if (c == typed) continue
                    val cost = touchCost(trace, touch, typed, c)
                    costs[c - 'a'] = cost
                    if (cost < ADJACENT_SUBSTITUTE_COST) {
                        spatialCheapMask = spatialCheapMask or (1 shl (c - 'a'))
                        spatialCheapFor[typed - 'a'] = spatialCheapFor[typed - 'a'] or (1 shl (c - 'a'))
                    }
                }
            }
            costs
        }
    }

    private fun clearSpatial() {
        spatialQuery = null
        spatialCosts = emptyArray()
        spatialCheapMask = 0
        spatialCheapFor.fill(0)
    }

    /**
     * Lower bound of the cost to replace the query letters in [missing] (a letter set) when the word has the letters
     * [wordMask]: each costs a plain slip, unless the word has a letter the touch made cheap for it.
     */
    private fun spatialReplaceBound(missing: Int, wordMask: Int): Double {
        var bound = 0.0
        var rest = missing
        while (rest != 0) {
            val bit = Integer.numberOfTrailingZeros(rest)
            rest = rest and (rest - 1)
            bound += if (spatialCheapFor[bit] and wordMask != 0) SPATIAL_MIN_COST else ADJACENT_SUBSTITUTE_COST
        }
        return bound
    }

    /** The set of letters a to z in the first [length] characters of [word], one bit per letter. */
    private fun letterMask(word: String, length: Int): Int {
        var mask = 0
        for (i in 0 until length) {
            val c = word[i]
            if (c in 'a'..'z') mask = mask or (1 shl (c - 'a'))
        }
        return mask
    }

    /**
     * Calls [action] for every dictionary word between [minLength] and [maxLength] letters long within [maxDistance]
     * of [query], with its distance. Every letter of the query missing from a word, and every letter of a word
     * missing from the query, takes an edit costing at least [ADJACENT_SUBSTITUTE_COST], and one substitution
     * fixes at most one of each. Words whose letter sets differ too much are skipped without comparing them.
     */
    private inline fun forEachWordWithin(
        query: String,
        maxDistance: Double,
        minLength: Int,
        maxLength: Int,
        action: (word: String, distance: Double) -> Unit,
    ) {
        val queryMask = letterMask(query, query.length)
        val maxMissing = (maxDistance / ADJACENT_SUBSTITUTE_COST).toInt()
        // With touch points, a typed letter can be cheap to replace by a key the finger was close to
        val spatial = query === spatialQuery
        val reachableMask = if (spatial) queryMask or spatialCheapMask else queryMask
        for (length in max(1, minLength)..min(MAX_WORD_LENGTH, maxLength)) {
            val words = wordsByLength[length]
            val masks = masksByLength[length]
            for (i in words.indices) {
                val mask = masks[i]
                val missing = queryMask and mask.inv()
                if (spatial) {
                    if (spatialReplaceBound(missing, mask) > maxDistance) continue
                } else if (Integer.bitCount(missing) > maxMissing) {
                    continue
                }
                if (Integer.bitCount(mask and reachableMask.inv()) > maxMissing) continue
                val word = words[i]
                val d = distance(query, word, maxDistance)
                if (d <= maxDistance) action(word, d)
            }
        }
    }

    private fun maxTypoDistance(length: Int): Double {
        return when {
            length <= 3 -> 1.0
            length <= 6 -> 1.5
            length < LONG_WORD_LENGTH -> 2.0
            // Fast typing on long words often slips three or four keys ("bapoenij" for "happening")
            else -> 2.5
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
        touches: LatinTouchTrace? = null,
    ): LatinSuggestionResult {
        val query = normalize(input)
        if (touches == null || touches.touches.size != query.length || touches.touches.all { it == null }) {
            return suggestDetailed(input, query, previous, maxCount, allowPossiblyOffensive)
        }
        prepareSpatial(query, touches)
        try {
            return suggestDetailed(input, query, previous, maxCount, allowPossiblyOffensive)
        } finally {
            clearSpatial()
        }
    }

    /** [distance] from [query] to [word] using the touch points of [query]. */
    private fun spatialDistance(query: String, word: String, touches: LatinTouchTrace): Double {
        if (touches.touches.size != query.length) return distance(query, word, MAX_WORD_LENGTH.toDouble())
        prepareSpatial(query, touches)
        try {
            return distance(query, word, MAX_WORD_LENGTH.toDouble())
        } finally {
            clearSpatial()
        }
    }

    private fun suggestDetailed(
        input: String,
        query: String,
        previous: String?,
        maxCount: Int,
        allowPossiblyOffensive: Boolean,
    ): LatinSuggestionResult {
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
        fun contextScore(word: String): Double = contextScore(previous, word)
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
            // The wide search for badly mistyped long words is slow, so it only runs when the normal one found nothing
            val firstPassDistance = min(maxDistance, NORMAL_MAX_TYPO_DISTANCE)
            forEachWordWithin(query, firstPassDistance, minLength, maxLength) { word, d ->
                if (d > 0.0) offer(word, contextScore(word) - TYPO_PENALTY * d, d)
            }
            if (maxDistance > firstPassDistance && corrections.isEmpty()) {
                forEachWordWithin(query, maxDistance, minLength, maxLength) { word, d ->
                    if (d > firstPassDistance) offer(word, contextScore(word) - TYPO_PENALTY * d, d)
                }
            }
            if (query.length >= 3) {
                val prefixMaxDistance = maxTypoDistance(query.length - 1)
                val candidates = wordsByFirstChar[query[0]].orEmpty()
                val queryMask = letterMask(query, query.length)
                val maxMissing = (prefixMaxDistance / ADJACENT_SUBSTITUTE_COST).toInt()
                val spatial = query === spatialQuery
                val reachableMask = if (spatial) queryMask or spatialCheapMask else queryMask
                for (word in candidates) {
                    if (word.length <= query.length) continue
                    // One letter more than typed, since the user may have skipped one
                    val prefixLength = min(word.length, query.length + 1)
                    val prefixMask = letterMask(word, prefixLength)
                    // The extra letter may be one the query does not have
                    val missing = queryMask and prefixMask.inv()
                    if (spatial) {
                        if (spatialReplaceBound(missing, prefixMask) > prefixMaxDistance) continue
                    } else if (Integer.bitCount(missing) > maxMissing) {
                        continue
                    }
                    if (Integer.bitCount(prefixMask and reachableMask.inv()) > maxMissing + 1) continue
                    var d = distance(query, word, query.length, prefixMaxDistance)
                    if (word.length > query.length + 1) {
                        // The user may have skipped a letter, so the typed text maps to one more character
                        d = min(d, distance(query, word, query.length + 1, prefixMaxDistance))
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

        // Two words typed without a space between them
        val splits = HashMap<String, LatinSplit>()
        if (query.length >= MIN_SPLIT_INPUT_LENGTH && query.none { it == '\'' } && !isKnownWord(query)) {
            for (split in findSplits(query, previous, allowPossiblyOffensive)) {
                val existing = scores[split.key]
                if (existing == null || split.score > existing) {
                    scores[split.key] = split.score
                    corrections[split.key] = SPLIT_DISTANCE
                    splits[split.key] = split
                }
            }
        }

        val ranked = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .toMutableList()

        val autoCorrectWord = pickAutoCorrection(input, query, previous, ranked, corrections, splits.keys)
            ?.takeIf { it in scores }
        if (autoCorrectWord != null) {
            val entry = ranked.first { it.key == autoCorrectWord }
            ranked.remove(entry)
            ranked.add(0, entry)
        }

        val sentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        fun display(key: String): String {
            val split = splits[key] ?: return applyCase(key, input, sentenceStart)
            // Split positions were found on the lowercased input, which can differ in length for a few characters
            if (input.length != query.length) return applyCase(key, input, sentenceStart)
            return applyCase(split.left, input.substring(0, split.leftInputEnd), sentenceStart) + " " +
                applyCase(split.right, input.substring(split.rightInputStart), false)
        }
        val words = ranked
            .asSequence()
            .take(maxCount)
            .map { display(it.key) }
            .distinct()
            .toList()
        return LatinSuggestionResult(
            words = words,
            autoCorrection = autoCorrectWord?.let { display(it) },
        )
    }

    /**
     * A reading of the input as two words.
     *
     * @property leftInputEnd Where the left word ends in the input.
     * @property rightInputStart Where the right word starts in the input, one past [leftInputEnd] when a letter
     *  next to the space bar was read as the missed space.
     */
    private class LatinSplit(
        val left: String,
        val right: String,
        val leftInputEnd: Int,
        val rightInputStart: Int,
        val score: Double,
    ) {
        val key: String
            get() = "$left $right"
    }

    /**
     * Returns the word a split part stands for and its score, or null if the part is not a plausible word. Parts
     * typed without their apostrophe resolve to the contraction ("dont" to "don't").
     */
    private fun resolveSplitPart(part: String, allowPossiblyOffensive: Boolean): Pair<String, Double>? {
        val word = contractionsByBareForm[part]?.takeIf { isSuggestable(it) } ?: part
        if (word.length <= 2 && word !in SPLIT_SHORT_WORDS) return null
        if (!isSuggestable(word)) return null
        if (!allowPossiblyOffensive && isOffensive(word)) return null
        val score = unigramScore(word)
        return if (word.length > 2 && score < SPLIT_MIN_PART_SCORE) null else word to score
    }

    private fun findSplits(query: String, previous: String?, allowPossiblyOffensive: Boolean): List<LatinSplit> {
        val result = ArrayList<LatinSplit>()
        fun tryAdd(leftEnd: Int, rightStart: Int, penalty: Double) {
            if (leftEnd <= 0 || rightStart >= query.length) return
            val (left, leftScore) = resolveSplitPart(query.substring(0, leftEnd), allowPossiblyOffensive) ?: return
            val (right, rightScore) = resolveSplitPart(query.substring(rightStart), allowPossiblyOffensive) ?: return
            // The weaker word decides how plausible the split is, so one common word cannot carry a rare one
            val score = min(leftScore, rightScore) +
                BIGRAM_WEIGHT * (bigramScore(previous, left) + bigramScore(left, right)) / 2 +
                BASE_BIGRAM_WEIGHT * baseBigramScore(left, right) - penalty
            result.add(LatinSplit(left, right, leftEnd, rightStart, score))
        }
        for (i in 1 until query.length) {
            tryAdd(i, i, SPLIT_PENALTY)
        }
        for (i in 1 until query.length - 1) {
            if (query[i] in SPACE_BAR_NEIGHBORS) {
                tryAdd(i, i + 1, SPLIT_PENALTY + SPLIT_SPACE_MISS_PENALTY)
            }
        }
        return result
    }

    private fun pickAutoCorrection(
        input: String,
        query: String,
        previous: String?,
        ranked: List<Map.Entry<String, Double>>,
        corrections: Map<String, Double>,
        splitKeys: Set<String>,
    ): String? {
        if (ranked.isEmpty() || query.isEmpty()) return null
        // "i", "i'm" and friends only need their capital, which the display casing adds
        if (query in I_FORMS) {
            return if (input.first() == 'i') query else null
        }
        if (query.length < 2) return null
        if (query.any { !LatinTextAnalysis.isWordChar(it) }) return null
        if (query.endsWith("'s") && isKnownWord(query.dropLast(2))) return null
        // Mixed case like "iPhone" or "McDonald" is deliberate, leave it alone
        if (input.drop(1).any { it.isUpperCase() } && !input.all { !it.isLetter() || it.isUpperCase() }) return null
        val isCapitalized = input.first().isUpperCase() && input.any { it.isLowerCase() }
        val isMidSentenceName = isCapitalized && previous != LatinTextAnalysis.SENTENCE_START
        val correctionEntries = ranked.filter { it.key in corrections }
        val best = correctionEntries.firstOrNull() ?: return null
        val bestDistance = corrections.getValue(best.key)

        if (isKnownWord(query)) {
            return pickKnownWordCorrection(query, previous, best.key, bestDistance, isMidSentenceName, splitKeys)
        }

        // A missing space ("needa" as "need a") and a single slip ("needa" as "needs") can both explain the input.
        // The word before decides: "it needa" is "it needs", since "it need" is far less likely than "it needs".
        if (best.key in splitKeys) {
            val left = best.key.substringBefore(' ')
            val leftLift = contextLiftOrZero(previous, left)
            val singleWord = correctionEntries.firstOrNull { entry ->
                entry.key !in splitKeys && corrections.getValue(entry.key) <= KNOWN_WORD_MAX_DISTANCE
            }?.key
            if (singleWord != null && contextLiftOrZero(previous, singleWord) - leftLift >= SPLIT_CONTEXT_SWITCH_BITS) {
                return singleWord
            }
        }

        // Only small slips get fixed silently, anything bigger is likely a name, slang or gibberish
        val maxAutoDistance = when {
            query.length <= 5 -> 1.0
            query.length < LONG_WORD_LENGTH -> 1.5
            else -> 2.5
        }
        if (bestDistance > maxAutoDistance) return null
        // A capitalized word mid-sentence is most likely a name like "Skyrim", never break it in two
        if (best.key in splitKeys && isMidSentenceName) return null
        val topScore = ranked.first().value
        if (best.value < topScore - AUTO_CORRECT_MAX_RANK_GAP) return null
        val runnerUp = correctionEntries.getOrNull(1)
        // A long word with several slips is only fixed when one word clearly stands out
        val minMargin = if (bestDistance > 1.5) LONG_TYPO_MIN_MARGIN else AUTO_CORRECT_MIN_MARGIN
        if (runnerUp != null && best.value - runnerUp.value < minMargin) return null
        return best.key
    }

    /**
     * Decides whether a word that is in the dictionary is still most likely a slip for a much more common word,
     * like "fir" for "for" or noise entries like "tge" for "the". Words the user typed or kept are never touched,
     * and neither are names or real words with a plausible neighbor ("form" and "from" are both common).
     */
    private fun pickKnownWordCorrection(
        query: String,
        previous: String?,
        candidate: String,
        distance: Double,
        isMidSentenceName: Boolean,
        splitKeys: Set<String>,
    ): String? {
        if (isUserWord(query) || isMidSentenceName) return null
        if (query in PROTECTED_SLANG || candidate in splitKeys) return null
        if (distance > KNOWN_WORD_MAX_DISTANCE) return null
        if (unigramScore(candidate) < KNOWN_WORD_MIN_REPLACEMENT_SCORE) return null
        // The previous word predicts what comes next: "it wad" becomes "it was", but "the wad" stays because
        // "the was" is never written. Plain next-word probability would favor common words twice over, so real
        // words are judged by how well each fits after the previous word instead.
        val candidateFit = contextFit(previous, candidate)
        val queryFit = contextFit(previous, query)
        if (candidateFit <= CONTEXT_MISFIT) return null
        // A real word that is known to fit where it was typed was most likely meant ("the nest")
        if (queryFit > 0.0) return null
        if (query in CONTRACTION_LOOKALIKES) {
            // "well" or "its" are usually typed on purpose, unless another word is clearly expected ("it id")
            if (candidateFit < LOOKALIKE_MIN_FIT) return null
        }
        val isRareUnverified = isUnverified(query) && unigramScore(query) < RARE_UNVERIFIED_SCORE
        val margin = if (isRareUnverified) UNVERIFIED_WORD_CORRECTION_MARGIN else KNOWN_WORD_CORRECTION_MARGIN
        var gain = contextScore(previous, candidate) - contextScore(previous, query)
        // A good fit for the replacement only counts once the typed word is suspicious in its own right: rare
        // noise, a lookalike, or a pair English never writes. Otherwise "I sat" would turn into "I say" and
        // "day to day" into "day to say".
        if (isRareUnverified || query in CONTRACTION_LOOKALIKES || queryFit <= CONTEXT_MISFIT) {
            gain += CONTEXT_FIT_WEIGHT * (candidateFit - queryFit)
        }
        return if (gain >= margin) candidate else null
    }

    /**
     * Decides what to replace when the user ends the word being typed with a space or punctuation. Called
     * synchronously at that moment, so the correction never depends on the suggestion bar having caught up.
     *
     * @param currentWord The partial word directly before the cursor.
     * @param textBeforeWord The text before [currentWord].
     * @param hasMoreBefore True if the editor has more text before [textBeforeWord] that was not captured.
     * @param previousCorrection The visible text and original text of an auto-correction applied right before the
     *  space in front of [currentWord], so an accidental space can be undone across it ("abo" corrected to "ago",
     *  then "ut", becomes "about").
     * @param touches Where the keys of [currentWord] were tapped, used to judge which slips are likely.
     */
    fun autoCorrect(
        currentWord: String,
        textBeforeWord: String,
        hasMoreBefore: Boolean,
        allowPossiblyOffensive: Boolean,
        previousCorrection: Pair<String, String>? = null,
        touches: LatinTouchTrace? = null,
    ): LatinAutoCorrection? {
        if (currentWord.isEmpty() || currentWord.length > MAX_WORD_LENGTH) return null
        val merge = findAccidentalSpace(
            currentWord, textBeforeWord, hasMoreBefore, allowPossiblyOffensive, previousCorrection,
        )
        val previous = LatinTextAnalysis.previousWord(textBeforeWord, hasMoreBefore)
        if (merge != null && touches == null) return merge
        val result = suggestDetailed(currentWord, previous, 4, allowPossiblyOffensive, touches)
        val replacement = result.autoCorrection?.takeIf { it != currentWord }
        if (merge != null) {
            // The touch points can show the word itself was just tapped sloppily ("so ks" is "so is", not
            // "socks"), in which case the space was meant
            val left = normalize(merge.replacedText.substringBefore(' '))
            val sloppyTap = replacement != null && !replacement.contains(' ') && isSolidWord(left) &&
                spatialDistance(normalize(currentWord), normalize(replacement), touches!!) < ADJACENT_SUBSTITUTE_COST
            if (!sloppyTap) return merge
        }
        if (replacement == null) return null
        return LatinAutoCorrection(replacement, currentWord)
    }

    /**
     * Like [autoCorrect] and [suggest] together, sharing the expensive search between them. Used for the suggestion
     * bar on every keystroke.
     */
    fun analyze(
        currentWord: String,
        textBeforeWord: String,
        hasMoreBefore: Boolean,
        maxCount: Int,
        allowPossiblyOffensive: Boolean,
        withAutoCorrection: Boolean,
    ): LatinAnalysis {
        val previous = LatinTextAnalysis.previousWord(textBeforeWord, hasMoreBefore)
        val result = suggestDetailed(currentWord, previous, maxCount, allowPossiblyOffensive)
        if (!withAutoCorrection || currentWord.isEmpty() || currentWord.length > MAX_WORD_LENGTH) {
            return LatinAnalysis(result.words, null)
        }
        val correction = findAccidentalSpace(currentWord, textBeforeWord, hasMoreBefore, allowPossiblyOffensive, null)
            ?: result.autoCorrection?.takeIf { it != currentWord }?.let { LatinAutoCorrection(it, currentWord) }
        return LatinAnalysis(result.words, correction)
    }

    /** How well [word] fits after [previous] in bits, 0 when unknown. */
    private fun contextLiftOrZero(previous: String?, word: String): Double {
        if (previous == null || previous == LatinTextAnalysis.SENTENCE_START) return 0.0
        return baseBigrams.lift(previous, word) ?: 0.0
    }

    /** How well [word] fits before [next] in bits, judging contractions by the words they stand for. */
    private fun rightLift(word: String, next: String): Double? {
        val proxies = RIGHT_PROXIES[word] ?: listOf(word)
        var best: Double? = null
        for (proxy in proxies) {
            val lift = baseBigrams.lift(proxy, next) ?: continue
            if (best == null || lift > best) best = lift
        }
        return best
    }

    /** How well [word] fits after [previous] in bits, judging contractions by their first word. */
    private fun leftLift(previous: String?, word: String): Double? {
        if (previous == null || previous == LatinTextAnalysis.SENTENCE_START) return null
        return baseBigrams.lift(previous, LEFT_PROXIES[word] ?: word)
    }

    private fun confusableScore(previous: String?, word: String, next: String): Double {
        val sentenceStartPrior = if (previous == LatinTextAnalysis.SENTENCE_START) SENTENCE_START_PRIORS[word] ?: 0.0 else 0.0
        return (rightLift(word, next) ?: 0.0) + (leftLift(previous, word) ?: 0.0) +
            (CONFUSION_PRIORS[word] ?: 0.0) + sentenceStartPrior
    }

    /**
     * Takes a second look at a finished word once the word after it is known, since the next word often settles
     * what was meant: "its a" is "it's a", "there house" is "their house", and "fo home" is "go home" even if
     * "fo" was first corrected to "do".
     *
     * @param textBeforeWord The text before [visible].
     * @param visible The word as it currently stands in the editor.
     * @param typed What the user actually typed for it, which differs from [visible] if it was auto-corrected.
     * @param next The word after it, as it will stand once its own correction is applied.
     * @return The replacement for [visible], cased like it, or null to leave it.
     */
    fun lookBack(
        textBeforeWord: String,
        hasMoreBefore: Boolean,
        visible: String,
        typed: String,
        next: String,
        allowPossiblyOffensive: Boolean,
    ): String? {
        if (visible.isEmpty() || typed.isEmpty() || next.isEmpty()) return null
        if (visible.length > MAX_WORD_LENGTH || typed.length > MAX_WORD_LENGTH) return null
        val v = normalize(visible)
        val q = normalize(typed)
        val n = normalize(next)
        if (v.any { !LatinTextAnalysis.isWordChar(it) } || q.any { !LatinTextAnalysis.isWordChar(it) }) return null
        if (v in PROTECTED_SLANG || q in PROTECTED_SLANG) return null
        val previous = LatinTextAnalysis.previousWord(textBeforeWord, hasMoreBefore)
        val sentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        // The next word must be one the model knows, else there is nothing to judge by. The exception is "its"
        // starting a sentence, which practically always means "it's" ("Its ok").
        if (!baseBigrams.isKnownFollower(n)) {
            return if (sentenceStart && v == "its" && q == "its") applyCase("it's", visible, true) else null
        }
        // A capitalized word in the middle of a sentence is most likely a name
        val isCapitalized = visible.first().isUpperCase() && visible.any { it.isLowerCase() }
        if (isCapitalized && !sentenceStart) return null

        val confusables = CONFUSION_SETS[v]
        if (confusables != null) {
            if (v != q) return null
            val currentScore = confusableScore(previous, v, n)
            val best = confusables.maxByOrNull { confusableScore(previous, it, n) } ?: return null
            if (best == v) return null
            val gain = confusableScore(previous, best, n) - currentScore
            if (v.contains('\'')) {
                if ((rightLift(v, n) ?: 0.0) > APOSTROPHE_MISFIT_BITS || gain < APOSTROPHE_SWITCH_BITS) return null
            } else if (gain < CONFUSABLE_SWITCH_BITS) {
                return null
            }
            return applyCase(best, visible, sentenceStart)
        }

        // Words the user added to their dictionary are left alone, like everywhere else
        if (v == q && isUserWord(v) && v !in baseFreq) return null
        val wasCorrected = v != q
        // Unverified dictionary entries ("fo") are mostly scraped noise and count as unknown here
        val isKnown = isKnownWord(v) && !isUnverified(v)
        val currentRight = rightLift(v, n)
        val suspicious = wasCorrected || !isKnown ||
            (currentRight != null && currentRight <= LOOKBACK_RIGHT_MISFIT_BITS)
        // A lone letter next to "i" on the keyboard is usually a missed "I", which the next word confirms: "k am"
        // is "I am", while "k thanks" stays
        if (q.length == 1 && v == q && q != "i" && q != "a" && isAdjacent(q[0], 'i')) {
            val right = rightLift("i", n) ?: return null
            val left = leftLift(previous, "i")
            val ownRight = rightLift(q, n) ?: 0.0
            if (right >= LONE_I_MIN_RIGHT_BITS && right - ownRight >= LONE_I_MIN_RIGHT_BITS &&
                (left == null || left > LOOKBACK_LEFT_MISFIT_BITS)
            ) {
                return "I"
            }
            return null
        }
        if (!suspicious || q.length < 2 || q.length > MAX_TYPO_INPUT_LENGTH) return null

        fun score(word: String, distance: Double): Double {
            return UNIGRAM_BITS * unigramScore(word) - TYPO_BITS * distance +
                (leftLift(previous, word) ?: 0.0) + (rightLift(word, n) ?: 0.0)
        }
        val currentScore = score(v, if (wasCorrected) distance(q, v, Double.MAX_VALUE / 2) else 0.0)
        val maxDistance = if (q.length <= 4) 1.0 else 1.5
        var bestWord: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        forEachWordWithin(q, maxDistance, q.length - 1, q.length + 1) { word, d ->
            if (word != v && isSuggestable(word) && !isUnverified(word) &&
                (allowPossiblyOffensive || !isOffensive(word)) &&
                unigramScore(word) >= KNOWN_WORD_MIN_REPLACEMENT_SCORE
            ) {
                val right = rightLift(word, n)
                val left = leftLift(previous, word)
                if (right != null && right >= 0.0 && (left == null || left > LOOKBACK_LEFT_MISFIT_BITS)) {
                    val s = score(word, d)
                    if (s > bestScore) {
                        bestScore = s
                        bestWord = word
                    }
                }
            }
        }
        val best = bestWord ?: return null
        val required = if (wasCorrected || !isKnown) LOOKBACK_SWITCH_BITS else LOOKBACK_REAL_WORD_SWITCH_BITS
        if (bestScore - currentScore < required) return null
        return applyCase(best, visible, sentenceStart)
    }

    /** A word is solid when it is a common, real word in its own right, so it was most likely typed on purpose. */
    private fun isSolidWord(word: String): Boolean {
        if (isUserWord(word)) return true
        if (word.length <= 2) return word in SPLIT_SHORT_WORDS
        return word in baseFreq && !isUnverified(word) && unigramScore(word) >= 0.15
    }

    /**
     * Finds a space typed by accident inside a word, either an extra tap on the space bar ("hel lo") or the space
     * bar hit instead of a bottom row key next to it ("pro lem" for "problem"). Only applies when at least one of
     * the two fragments is not a solid word on its own, so "a lot" or "to day" are never merged.
     */
    private fun findAccidentalSpace(
        right: String,
        textBeforeWord: String,
        hasMoreBefore: Boolean,
        allowPossiblyOffensive: Boolean,
        previousCorrection: Pair<String, String>?,
    ): LatinAutoCorrection? {
        if (!textBeforeWord.endsWith(" ") || textBeforeWord.endsWith("  ")) return null
        val beforeSpace = textBeforeWord.dropLast(1)
        val visibleLeft = LatinTextAnalysis.currentWord(beforeSpace)
        if (visibleLeft.isEmpty() || beforeSpace.length == visibleLeft.length && hasMoreBefore) return null
        // If the left fragment was itself just auto-corrected, merge what the user actually typed
        val typedLeft = previousCorrection?.takeIf { it.first == visibleLeft }?.second ?: visibleLeft
        val left = normalize(typedLeft)
        val rightNorm = normalize(right)
        if (left.any { !it.isLetter() } || rightNorm.any { !it.isLetter() }) return null
        if (isSolidWord(left) && isSolidWord(rightNorm)) return null

        var bestWord: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        fun consider(word: String, penalty: Double) {
            if (word.length < 4 || !isSuggestable(word) || isUnverified(word)) return
            if (!allowPossiblyOffensive && isOffensive(word)) return
            val score = unigramScore(word)
            if (score < MERGE_MIN_SCORE) return
            if (score - penalty > bestScore) {
                bestScore = score - penalty
                bestWord = word
            }
        }
        consider(left + rightNorm, 0.0)
        for (key in MERGE_SPACE_BAR_KEYS) {
            consider(left + key + rightNorm, MERGE_KEY_PENALTY)
        }
        val merged = bestWord ?: return null
        val previous = LatinTextAnalysis.previousWord(beforeSpace.dropLast(visibleLeft.length), hasMoreBefore)
        val display = applyCase(merged, typedLeft, previous == LatinTextAnalysis.SENTENCE_START)
        return LatinAutoCorrection(display, "$visibleLeft $right")
    }

    /**
     * Predicts the next word from what the user typed after [previous] before, blended with how often words follow
     * [previous] in English. At the start of a sentence, common first words fill in until the user's own habits
     * take over.
     */
    fun predictNext(previous: String?, maxCount: Int, allowPossiblyOffensive: Boolean): List<String> {
        if (previous == null || maxCount <= 0) return emptyList()
        val sentenceStart = previous == LatinTextAnalysis.SENTENCE_START
        val scores = HashMap<String, Double>()
        fun offer(word: String, score: Double) {
            if (!isSuggestable(word)) return
            if (!allowPossiblyOffensive && isOffensive(word)) return
            scores[word] = max(scores[word] ?: Double.NEGATIVE_INFINITY, score)
        }
        // A small unigram share breaks ties toward common words without outweighing the context
        userBigrams[previous]?.keys?.forEach { word ->
            offer(word, BIGRAM_WEIGHT * bigramScore(previous, word) + 0.1 * unigramScore(word) + 0.2)
        }
        baseBigrams.followersOf(previous)?.let { followers ->
            // Followers are stored most likely first, the long tail can never reach the top few
            for (index in 0 until min(followers.words.size, PREDICTION_FOLLOWER_LIMIT)) {
                val word = followers.words[index]
                offer(
                    word,
                    BIGRAM_WEIGHT * bigramScore(previous, word) + BASE_BIGRAM_WEIGHT * baseBigramScore(previous, word) +
                        0.1 * unigramScore(word),
                )
            }
        }
        if (sentenceStart) {
            for ((rank, word) in SENTENCE_STARTERS.withIndex()) {
                offer(word, 0.05 - rank * 0.005)
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(maxCount)
            .map { applyCase(it.key, "", sentenceStart) }
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
        // Spell checks arrive in bulk for whole paragraphs, so they only look for the common one or two slips
        val maxDistance = min(SPELL_CHECK_MAX_DISTANCE, maxTypoDistance(query.length))
        val scores = HashMap<String, Double>()
        val minLength = max(1, query.length - 1)
        val maxLength = min(MAX_WORD_LENGTH, query.length + 1)
        forEachWordWithin(query, maxDistance, minLength, maxLength) { candidate, d ->
            if (candidate !in blocked) scores[candidate] = unigramScore(candidate) - TYPO_PENALTY * d
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
        userUnigrams[word] = max(userUnigrams[word] ?: 0, USER_WORD_PROTECT_COUNT)
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
