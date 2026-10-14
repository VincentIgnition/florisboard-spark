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

private fun newEngine() = LatinSuggestionEngine(dictionary)

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
        LatinTextAnalysis.previousWord("well, ", hasMoreBefore = false).shouldBeNull()
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
        engine.predictNext("pizza", 3, false) shouldBe emptyList()
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
