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
import io.kotest.matchers.shouldBe

/**
 * Feeds the tracker editor states as text before the cursor, the way the suggestion provider sees them, and collects
 * the words it decides to learn.
 */
private class Typist(private val prefix: String = "", private val prefixOffset: Int = 0) {
    val tracker = LatinLearningTracker()
    val learned = mutableListOf<String>()

    fun state(text: String) {
        val textBefore = prefix + text
        val currentWord = LatinTextAnalysis.currentWord(textBefore)
        val cursor = prefixOffset + textBefore.length
        tracker.observe(textBefore, cursor, prefixOffset > 0, currentWord)?.let { learned.add(it.word) }
    }

    /** Every keystroke observed, the ideal case. */
    fun typeAll(text: String) {
        for (i in 1..text.length) state(text.substring(0, i))
    }
}

class LatinLearningTrackerTest : FunSpec({
    test("learns words when every state is observed") {
        val typist = Typist()
        typist.typeAll("hello there world ")
        typist.learned shouldBe listOf("hello", "there", "world")
    }

    test("learns words when the state right after the space is skipped") {
        val typist = Typist()
        typist.state("h")
        typist.state("hel")
        typist.state("hello")
        // "hello " never observed, the next state already starts the next word
        typist.state("hello t")
        typist.state("hello the")
        typist.state("hello there")
        typist.state("hello there w")
        typist.learned shouldBe listOf("hello", "there")
    }

    test("learns a word when its last letters were also skipped") {
        val typist = Typist()
        typist.state("pa")
        typist.state("parag")
        // "paragraph" and "paragraph " skipped
        typist.state("paragraph i")
        typist.learned shouldBe listOf("paragraph")
    }

    test("learns words deep into a long paragraph where only part of the text is visible") {
        val typist = Typist(prefix = "lots of earlier text ", prefixOffset = 4000)
        typist.typeAll("keeps going ")
        typist.learned shouldBe listOf("keeps", "going")
    }

    test("does not learn words the cursor is merely moved next to") {
        val typist = Typist()
        typist.state("already written ")
        typist.state("already written words ")
        typist.learned shouldBe emptyList()
    }

    test("does not learn the same word twice from repeated states") {
        val typist = Typist()
        typist.typeAll("again ")
        typist.state("again ")
        typist.state("again a")
        typist.learned shouldBe listOf("again")
    }

    test("learns a tapped or auto-corrected candidate") {
        val typist = Typist()
        typist.typeAll("teh")
        typist.tracker.onCandidateAccepted("the")
        typist.state("the ")
        typist.learned shouldBe listOf("the")
    }

    test("does not learn an auto-correction the user undid") {
        val typist = Typist()
        typist.typeAll("teh")
        typist.tracker.onCandidateAccepted("the")
        typist.tracker.onCandidateReverted()
        typist.tracker.reset()
        typist.state("the ")
        typist.learned shouldBe emptyList()
    }
})
