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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private val dictionary: Map<String, Int> by lazy {
    val file = listOf("src/main/assets/ime/dict/data.json", "app/src/main/assets/ime/dict/data.json")
        .map { File(it) }
        .first { it.exists() }
    Json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), file.readText())
}

private fun assetFile(name: String): File {
    return listOf("src/main/assets/ime/dict/$name", "app/src/main/assets/ime/dict/$name").map { File(it) }.first { it.exists() }
}

private val bigrams: LatinBigramModel by lazy { LatinBigramModel.parse(assetFile("bigrams.tsv").readText()) }

private val unverifiedWords: Set<String> by lazy {
    assetFile("unverified.txt").readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
}

private val validWords: Set<String> by lazy {
    assetFile("valid.txt").readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
}

private fun newEngine() = LatinSuggestionEngine(dictionary, bigrams, unverifiedWords, validWords)

private fun LatinSuggestionEngine.top(input: String, previous: String? = null, count: Int = 5) =
    suggest(input, previous, count, allowPossiblyOffensive = false)

class LatinTextAnalysisTest : FunSpec({
    test("current word is the letters touching the cursor") {
        LatinTextAnalysis.currentWord("Hello wor") shouldBe "wor"
        LatinTextAnalysis.currentWord("it's") shouldBe "it's"
        LatinTextAnalysis.currentWord("said 'quo") shouldBe "quo"
        LatinTextAnalysis.currentWord("hello ") shouldBe ""
        LatinTextAnalysis.currentWord("") shouldBe ""
        LatinTextAnalysis.currentWord("abc123") shouldBe ""
    }

    test("previous word respects sentence boundaries") {
        LatinTextAnalysis.previousWord("Hello ", hasMoreBefore = false) shouldBe "hello"
        LatinTextAnalysis.previousWord("", hasMoreBefore = false) shouldBe LatinTextAnalysis.SENTENCE_START
        LatinTextAnalysis.previousWord("", hasMoreBefore = true).shouldBeNull()
        LatinTextAnalysis.previousWord("Done. ", hasMoreBefore = false) shouldBe LatinTextAnalysis.SENTENCE_START
        LatinTextAnalysis.previousWord("first line\n", hasMoreBefore = false) shouldBe LatinTextAnalysis.SENTENCE_START
        LatinTextAnalysis.previousWord("well, ", hasMoreBefore = false) shouldBe "well"
        LatinTextAnalysis.previousWord("(", hasMoreBefore = false).shouldBeNull()
        LatinTextAnalysis.previousWord("glued", hasMoreBefore = false).shouldBeNull()
        LatinTextAnalysis.previousWord("cut ", hasMoreBefore = true).shouldBeNull()
        LatinTextAnalysis.previousWord("I'm ", hasMoreBefore = false) shouldBe "i'm"
    }

    test("completed word is found after a separator") {
        val completed = LatinTextAnalysis.lastCompletedWord("I like pizza ", hasMoreBefore = false)
        completed.shouldNotBeNull()
        completed.previous shouldBe "like"
        completed.word shouldBe "pizza"
        completed.charsAfter shouldBe 1

        LatinTextAnalysis.lastCompletedWord("pizza.", hasMoreBefore = false)?.charsAfter shouldBe 1
        LatinTextAnalysis.lastCompletedWord("Pizza ", hasMoreBefore = false)?.previous shouldBe
            LatinTextAnalysis.SENTENCE_START
        LatinTextAnalysis.lastCompletedWord("pizza", hasMoreBefore = false).shouldBeNull()
        LatinTextAnalysis.lastCompletedWord("abc1", hasMoreBefore = false).shouldBeNull()
    }
})

class LatinSuggestionEngineTest : FunSpec({
    test("dictionary loads") {
        newEngine().dictionarySize shouldBeGreaterThan dictionary.size - 500
    }

    test("completes common words") {
        val engine = newEngine()
        engine.top("hel") shouldContain "help"
        engine.top("hel") shouldContain "hello"
        engine.top("becau").first() shouldBe "because"
        engine.top("th").first() shouldBe "the"
    }

    test("corrects typos") {
        val engine = newEngine()
        engine.top("teh").first() shouldBe "the"
        engine.top("recieve", count = 3) shouldContain "receive"
        engine.top("thw", count = 3) shouldContain "the"
        engine.top("dont", count = 3) shouldContain "don't"
        engine.top("becuase", count = 3) shouldContain "because"
        engine.top("definately", count = 3) shouldContain "definitely"
        engine.top("probly", count = 3) shouldContain "probably"
        engine.top("restu", count = 5) shouldContain "restaurant"
        engine.top("wnat", count = 3) shouldContain "want"
        engine.top("jsut").first() shouldBe "just"
    }

    test("auto-corrects clear typos") {
        val engine = newEngine()
        fun auto(input: String) = engine.suggestDetailed(input, null, 5, false).autoCorrection
        auto("teh") shouldBe "the"
        auto("recieve") shouldBe "receive"
        auto("dont") shouldBe "don't"
        auto("im") shouldBe "I'm"
        auto("jsut") shouldBe "just"
        auto("wnat") shouldBe "want"
        auto("becuase") shouldBe "because"
        auto("Teh") shouldBe "The"
    }

    test("puts the auto-correction first") {
        val engine = newEngine()
        val result = engine.suggestDetailed("thw", null, 5, false)
        result.autoCorrection.shouldNotBeNull()
        result.words.first() shouldBe result.autoCorrection
    }

    test("never auto-corrects real words, slang, completions or deliberate casing") {
        val engine = newEngine()
        fun auto(input: String) = engine.suggestDetailed(input, null, 5, false).autoCorrection
        auto("the").shouldBeNull()
        auto("well").shouldBeNull()
        auto("ur").shouldBeNull()
        auto("lol").shouldBeNull()
        auto("becau").shouldBeNull()
        auto("dog's").shouldBeNull()
        auto("iPhone").shouldBeNull()
        auto("qzxv").shouldBeNull()
        auto("a").shouldBeNull()
    }

    test("stops auto-correcting a word once the user insists on it") {
        val engine = newEngine()
        engine.suggestDetailed("teh", null, 5, false).autoCorrection shouldBe "the"
        engine.acceptWord("teh")
        engine.suggestDetailed("teh", null, 5, false).autoCorrection.shouldBeNull()
    }

    test("splits two words typed without a space") {
        val engine = newEngine()
        fun auto(input: String, previous: String? = null) =
            engine.suggestDetailed(input, previous, 5, false).autoCorrection
        auto("thecat") shouldBe "the cat"
        auto("goingto") shouldBe "going to"
        auto("iwant") shouldBe "I want"
        auto("becausethe") shouldBe "because the"
        auto("THECAT") shouldBe "THE CAT"
        auto("Thecat", previous = LatinTextAnalysis.SENTENCE_START) shouldBe "The cat"
    }

    test("splits glued phrases the dictionary used to list as words") {
        val engine = newEngine()
        fun auto(input: String) = engine.suggestDetailed(input, null, 5, false).autoCorrection
        auto("ofthe") shouldBe "of the"
        auto("inthe") shouldBe "in the"
        auto("thankyou") shouldBe "thank you"
        auto("noone") shouldBe "no one"
        auto("atleast") shouldBe "at least"
    }

    test("restores apostrophes in split contractions") {
        val engine = newEngine()
        fun auto(input: String) = engine.suggestDetailed(input, null, 5, false).autoCorrection
        auto("dontknow") shouldBe "don't know"
        auto("cantwait") shouldBe "can't wait"
        auto("imgoing") shouldBe "I'm going"
    }

    test("reads a bottom row key next to the space bar as a missed space") {
        val engine = newEngine()
        fun auto(input: String) = engine.suggestDetailed(input, null, 5, false).autoCorrection
        auto("hellobworld") shouldBe "hello world"
        auto("thatnis") shouldBe "that is"
    }

    test("never splits real words, supplemented words or capitalized names") {
        val engine = newEngine()
        fun result(input: String) = engine.suggestDetailed(input, null, 5, false)
        for (word in listOf("carpet", "therapist", "today", "notebook", "into", "nowhere", "something", "become",
            "subreddit", "upvote", "modding", "codebase", "frontend", "skyrim")) {
            result(word).autoCorrection.shouldBeNull()
            result(word).words.none { ' ' in it } shouldBe true
        }
        result("Thecat").autoCorrection.shouldBeNull()
    }

    test("auto-correct capitalizes I and its contractions") {
        val engine = newEngine()
        fun ac(word: String) = engine.autoCorrect(word, "so ", false, false)?.replacement
        ac("i") shouldBe "I"
        ac("im") shouldBe "I'm"
        ac("ive") shouldBe "I've"
        engine.autoCorrect("i", "", false, false)?.replacement shouldBe "I"
        engine.autoCorrect("I", "so ", false, false).shouldBeNull()
    }

    test("auto-correct restores apostrophes in contractions") {
        val engine = newEngine()
        fun ac(word: String) = engine.autoCorrect(word, "so ", false, false)?.replacement
        mapOf(
            "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't",
            "isnt" to "isn't", "thats" to "that's", "whats" to "what's", "youre" to "you're", "theyre" to "they're",
            "shouldnt" to "shouldn't", "couldve" to "could've",
        ).forEach { (typed, expected) -> ac(typed) shouldBe expected }
        // Real words that look like contractions are ambiguous and stay as typed
        listOf("ill", "id", "lets", "its", "were", "well").forEach { ac(it).shouldBeNull() }
    }

    test("auto-correct fixes neighboring key slips, also when they match dictionary noise") {
        val engine = newEngine()
        fun ac(word: String) = engine.autoCorrect(word, "so ", false, false)?.replacement
        mapOf(
            "tge" to "the", "thw" to "the", "yhe" to "the", "rhe" to "the", "tje" to "the", "hapoy" to "happy",
            "becausr" to "because", "yoi" to "you", "goimg" to "going", "workimg" to "working", "lile" to "like",
            "somethimg" to "something", "kniw" to "know", "thst" to "that", "fir" to "for", "anf" to "and",
            "abd" to "and", "wiht" to "with", "taht" to "that", "probelm" to "problem", "liek" to "like",
        ).forEach { (typed, expected) -> ac(typed) shouldBe expected }
    }

    test("auto-correct leaves real words, slang and names alone") {
        val engine = newEngine()
        for (word in listOf("form", "then", "than", "lose", "loose", "quite", "quiet", "cat", "car", "bat",
            "bad", "where", "their", "angel", "angle", "ship", "shop", "ur", "lol", "idk", "tbh", "bro",
            "nite", "msg", "ok", "yeah", "Skyrim", "Astoria", "reddit", "modding")) {
            engine.autoCorrect(word, "the ", false, false).shouldBeNull()
        }
        // "the from" and "the there" are never written, so those get corrected, but not where they fit
        for (word in listOf("from", "there")) {
            engine.autoCorrect(word, "and ", false, false).shouldBeNull()
        }
    }

    test("auto-correct uses the previous word to judge what was meant") {
        val engine = newEngine()
        fun ac(before: String, word: String) = engine.autoCorrect(word, before, false, false)?.replacement
        // The typed word is a real word, but English practically never writes it after the previous one
        ac("it ", "wad") shouldBe "was"
        ac("it ", "id") shouldBe "is"
        ac("the ", "from") shouldBe "form"
        ac("we ", "cam") shouldBe "can"
        // The likely replacement would not fit there, so it is not used
        (ac("the ", "wad") == "was") shouldBe false
        // Real words that fit where they were typed stay, even next to a more common lookalike
        ac("day to ", "day").shouldBeNull()
        ac("I ", "sat").shouldBeNull()
        ac("the ", "nest").shouldBeNull()
        ac("it ", "well").shouldBeNull()
        // Among similar typo fixes, the one that fits after the previous word wins
        ac("in the ", "moening") shouldBe "morning"
        ac("let me ", "kbow") shouldBe "know"
    }

    test("looking back tells its from it's and their, there and they're apart by the next word") {
        val engine = newEngine()
        fun lb(before: String, word: String, next: String) =
            engine.lookBack(before, false, word, word, next, false)
        lb("I think ", "its", "a") shouldBe "it's"
        lb("", "Its", "going") shouldBe "It's"
        lb("I think ", "its", "not") shouldBe "it's"
        lb("I think ", "its", "the") shouldBe "it's"
        lb("the dog wagged ", "it's", "tail").shouldBeNull()
        lb("the company and ", "it's", "own") shouldBe "its"
        lb("", "there", "going") shouldBe "they're"
        lb("I think ", "their", "is") shouldBe "there"
        lb("I love ", "there", "house") shouldBe "their"
        lb("I saw ", "they're", "car") shouldBe "their"
        lb("", "your", "welcome") shouldBe "you're"
        lb("I think ", "your", "not") shouldBe "you're"
        lb("I like ", "you're", "car") shouldBe "your"
        // Right as typed
        lb("", "it's", "not").shouldBeNull()
        lb("", "it's", "way").shouldBeNull()
        lb("the company and ", "its", "own").shouldBeNull()
        lb("I know ", "there", "is").shouldBeNull()
        lb("I love ", "their", "house").shouldBeNull()
        lb("is that ", "your", "car").shouldBeNull()
        lb("", "they're", "going").shouldBeNull()
        lb("", "you're", "welcome").shouldBeNull()
        // Nothing to judge by
        lb("I think ", "its", "xqzv").shouldBeNull()
    }

    test("looking back fixes a word once the next one shows what was meant") {
        val engine = newEngine()
        // "fo" was first corrected to "do", "home" shows it was "go"
        engine.lookBack("I want to ", false, "do", "fo", "home", false) shouldBe "go"
        engine.lookBack("I want to ", false, "fo", "fo", "home", false) shouldBe "go"
        // Real words that fit stay
        engine.lookBack("I ", false, "sat", "sat", "down", false).shouldBeNull()
        engine.lookBack("I want to ", false, "do", "do", "it", false).shouldBeNull()
        engine.lookBack("see you ", false, "soon", "soon", "then", false).shouldBeNull()
        // Names mid-sentence and slang are never touched
        engine.lookBack("I met ", false, "Bo", "Bo", "yesterday", false).shouldBeNull()
        engine.lookBack("see ", false, "ur", "ur", "mom", false).shouldBeNull()
    }

    test("one pass gives the same result as suggest and autoCorrect") {
        val engine = newEngine()
        for ((before, word) in listOf("so " to "tge", "I want to know abo " to "ut", "the " to "hel", "it " to "wad")) {
            val analysis = engine.analyze(word, before, false, 8, false, withAutoCorrection = true)
            analysis.autoCorrection shouldBe engine.autoCorrect(word, before, false, false)
            analysis.words shouldBe engine.suggest(word, LatinTextAnalysis.previousWord(before, false), 8, false)
        }
    }

    test("the word before decides between a missing space and a slip") {
        val engine = newEngine()
        fun ac(before: String, word: String) = engine.autoCorrect(word, before, false, false)?.replacement
        ac("it ", "needa") shouldBe "needs"
        ac("she ", "needa") shouldBe "needs"
        ac("I ", "needa") shouldBe "need a"
    }

    test("real words missing from the dictionary are left alone") {
        val engine = newEngine()
        engine.autoCorrect("wagged", "the dog ", false, false).shouldBeNull()
    }

    test("a typo that slipped through once is still corrected later") {
        val engine = newEngine()
        engine.learn("it", "needa")
        engine.autoCorrect("needa", "it ", false, false)?.replacement shouldBe "needs"
        // Undoing the correction keeps the word for good
        engine.acceptWord("needa")
        engine.autoCorrect("needa", "it ", false, false).shouldBeNull()
    }

    test("its at the start of a sentence means it's") {
        val engine = newEngine()
        fun lb(word: String, next: String) = engine.lookBack("", false, word, word, next, false)
        lb("Its", "time") shouldBe "It's"
        lb("Its", "ok") shouldBe "It's"
        lb("Its", "cool") shouldBe "It's"
        lb("Its", "tail").shouldBeNull()
        engine.lookBack("is ", false, "there", "there", "new", false).shouldBeNull()
    }

    test("fixes fast typing where the thumb lands on k instead of i or o") {
        val engine = newEngine()
        fun ac(before: String, word: String) = engine.autoCorrect(word, before, false, false)?.replacement
        ac("Testing this ", "ks") shouldBe "is"
        ac("to see ", "kf") shouldBe "if"
        ac("but ", "jt") shouldBe "it"
        ac("it seems ", "tk") shouldBe "to"
        ac("seems to ", "nkt") shouldBe "not"
        // One slip beats two, even toward a more common word
        ac("the keyboard ", "clkses") shouldBe "closes"
        // Long words get a wider search
        ac("to not be ", "bapoenij") shouldBe "happening"
    }

    test("a lone letter next to i becomes I when the next word calls for it") {
        val engine = newEngine()
        engine.lookBack("a test message ", false, "k", "k", "am", false) shouldBe "I"
        engine.lookBack("", false, "k", "k", "think", false) shouldBe "I"
        engine.lookBack("", false, "k", "k", "thanks", false).shouldBeNull()
        engine.lookBack("", false, "u", "u", "know", false).shouldBeNull()
    }

    test("where the finger landed decides between neighboring keys") {
        val engine = newEngine()
        // Key centers of a QWERTY layout in key units, rows staggered like the real keyboard
        val rows = listOf("qwertyuiop" to 0.0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val centers = HashMap<Char, Pair<Float, Float>>()
        for ((rowIndex, row) in rows.withIndex()) {
            val (keys, offset) = row
            for ((col, key) in keys.withIndex()) centers[key] = Pair(col + 0.5f + offset, rowIndex + 0.5f)
        }
        val layout = LatinKeyLayout(centers)
        fun center(c: Char) = LatinKeyTouch(centers.getValue(c).first, centers.getValue(c).second)
        fun ac(word: String, touches: List<LatinKeyTouch>) =
            engine.autoCorrect(word, "so ", false, false, touches = LatinTouchTrace(layout, touches))?.replacement
        // "fot" with the f tapped on its right edge, toward g
        val fTowardG = LatinKeyTouch(centers.getValue('f').first + 0.45f, centers.getValue('f').second)
        ac("fot", listOf(fTowardG, center('o'), center('t'))) shouldBe "got"
        // The same letters with the t tapped on its left edge, toward r
        val tTowardR = LatinKeyTouch(centers.getValue('t').first - 0.45f, centers.getValue('t').second)
        ac("fot", listOf(center('f'), center('o'), tTowardR)) shouldBe "for"
        // K tapped on its top edge, toward i and o
        val kTowardI = LatinKeyTouch(centers.getValue('k').first - 0.2f, centers.getValue('k').second - 0.45f)
        ac("ks", listOf(kTowardI, center('s'))) shouldBe "is"
    }

    test("auto-correct undoes an accidental space") {
        val engine = newEngine()
        fun ac(before: String, word: String) = engine.autoCorrect(word, before, false, false)
        ac("I want to know abo ", "ut") shouldBe LatinAutoCorrection("about", "abo ut")
        ac("this is a pro ", "lem") shouldBe LatinAutoCorrection("problem", "pro lem")
        ac("say hel ", "lo") shouldBe LatinAutoCorrection("hello", "hel lo")
        ac("it was thi ", "s") shouldBe LatinAutoCorrection("this", "thi s")
        // Both fragments are real words, so the space was meant
        ac("there is a ", "lot").shouldBeNull()
        ac("see you to ", "day").shouldBeNull()
        ac("a ", "out").shouldBeNull()
    }

    test("an accidental space is undone even after the first fragment was auto-corrected") {
        val engine = newEngine()
        // "abo" was corrected to "ago" when the space was typed, then "ut" followed
        engine.autoCorrect("ut", "I know ago ", false, false, previousCorrection = "ago" to "abo") shouldBe
            LatinAutoCorrection("about", "ago ut")
    }

    test("predicts likely next words before anything is learned") {
        val engine = newEngine()
        engine.predictNext("going", 3, false).first() shouldBe "to"
        engine.predictNext("thank", 3, false).first() shouldBe "you"
        engine.predictNext("want", 3, false).first() shouldBe "to"
        engine.predictNext(LatinTextAnalysis.SENTENCE_START, 3, false).first() shouldBe "I"
        engine.suggest("t", "going", 3, false).first() shouldBe "to"
        engine.suggest("y", "thank", 3, false).first() shouldBe "you"
    }

    test("never suggests stray single letters") {
        val engine = newEngine()
        engine.top("h") shouldNotContain "h"
        engine.top("a").first() shouldBe "a"
    }

    test("keeps exact matches on top") {
        val engine = newEngine()
        engine.top("the").first() shouldBe "the"
        engine.top("in").first() shouldBe "in"
        engine.top("form").first() shouldBe "form"
    }

    test("cleans misspellings and bare contractions out of the dictionary") {
        val engine = newEngine()
        engine.top("dont").first() shouldBe "don't"
        engine.top("im").first() shouldBe "I'm"
        engine.top("recieve").first() shouldBe "receive"
        engine.top("teh").first() shouldBe "the"
        engine.top("well").first() shouldBe "well"
        engine.top("its").first() shouldBe "its"
        engine.top("were").first() shouldBe "were"
        engine.isKnownWord("recieve") shouldBe false
        engine.isKnownWord("lol") shouldBe true
        engine.isKnownWord("dont") shouldBe false
        engine.isKnownWord("dogs") shouldBe true
        engine.isKnownWord("queens") shouldBe true
    }

    test("matches the typed capitalization") {
        val engine = newEngine()
        engine.top("Hel") shouldContain "Hello"
        engine.top("HEL") shouldContain "HELLO"
        engine.top("i").first() shouldBe "I"
        engine.top("im", count = 3) shouldContain "I'm"
    }

    test("learns new words only after they are typed twice") {
        val engine = newEngine()
        engine.learn("my", "zorbix")
        engine.top("zorb") shouldNotContain "zorbix"
        engine.learn("my", "zorbix")
        engine.top("zorb") shouldContain "zorbix"
    }

    test("remembers custom casing for learned words") {
        val engine = newEngine()
        engine.learn("in", "Queens")
        engine.learn("in", "Queens")
        engine.top("quee") shouldContain "Queens"
    }

    test("predicts the next word from learned pairs") {
        val engine = newEngine()
        engine.learn("pizza", "tonight")
        engine.learn("pizza", "tonight")
        engine.learn("pizza", "later")
        engine.predictNext("pizza", 3, false).first() shouldBe "tonight"
        engine.top("", previous = "pizza").first() shouldBe "tonight"
    }

    test("capitalizes predictions at the start of a sentence") {
        val engine = newEngine()
        engine.learn(LatinTextAnalysis.SENTENCE_START, "hey")
        engine.predictNext(LatinTextAnalysis.SENTENCE_START, 3, false).first() shouldBe "Hey"
    }

    test("learned context changes the ranking") {
        val engine = newEngine()
        val before = engine.top("th", previous = "go")
        repeat(5) { engine.learn("go", "thataway") }
        engine.top("th", previous = "go").first() shouldBe "thataway"
        before.first() shouldBe "the"
    }

    test("removed words stay gone until typed again") {
        val engine = newEngine()
        engine.top("hel") shouldContain "hello"
        engine.block("hello")
        engine.top("hel") shouldNotContain "hello"
        engine.learn(null, "hello")
        engine.top("hel") shouldContain "hello"
    }

    test("filters possibly offensive words unless allowed") {
        val engine = newEngine()
        engine.suggest("fuc", null, 8, allowPossiblyOffensive = false) shouldNotContain "fuck"
        engine.suggest("fuc", null, 8, allowPossiblyOffensive = true) shouldContain "fuck"
        engine.suggest("fuck", null, 8, allowPossiblyOffensive = false) shouldContain "fuck"
    }

    test("spell checks") {
        val engine = newEngine()
        engine.spellCheck("receive", 3).shouldBeNull()
        engine.spellCheck("dog's", 3).shouldBeNull()
        engine.spellCheck("recieve", 3).shouldNotBeNull() shouldContain "receive"
    }

    test("user data survives a round trip") {
        val engine = newEngine()
        engine.learn("pizza", "tonight")
        engine.learn("pizza", "tonight")
        engine.block("hello")
        val json = Json.encodeToString(LatinUserModelData.serializer(), engine.exportUserData())
        val restored = newEngine()
        restored.importUserData(Json.decodeFromString(LatinUserModelData.serializer(), json))
        restored.predictNext("pizza", 3, false).first() shouldBe "tonight"
        restored.top("hel") shouldNotContain "hello"
        restored.isUserModelDirty shouldBe false
    }

    test("is fast enough to run on every keystroke") {
        val engine = newEngine()
        val inputs = listOf("t", "th", "the", "hel", "helo", "recie", "definatel", "becuase", "q", "xylo")
        repeat(3) { inputs.forEach { engine.top(it, previous = "the", count = 8) } }
        val start = System.nanoTime()
        repeat(20) { inputs.forEach { engine.top(it, previous = "the", count = 8) } }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / (20 * inputs.size)
        println("Average suggest() time: ${"%.2f".format(avgMs)} ms")
        avgMs shouldBeLessThan 15.0
    }
})
