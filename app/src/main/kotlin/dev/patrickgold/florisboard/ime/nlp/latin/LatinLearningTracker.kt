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

/**
 * Decides which finished words the user actually typed, so only those are learned and not words that merely sit next
 * to the cursor after it is moved.
 *
 * Editor states arrive with gaps: when typing fast, the state right after a space is often skipped and the next one
 * already contains the start of the following word. Every state is therefore checked for a finished word in front
 * of the word being typed, not only states where the cursor sits right after a separator.
 *
 * This class is not thread safe, callers must serialize access.
 */
class LatinLearningTracker {
    // The word being typed as of the previous observed state and the absolute cursor position at its end
    private var pendingWord: String? = null
    private var pendingWordEnd: Int = -1

    // The text of the most recently picked candidate, so it is learned even though it was never typed out fully
    private var acceptedWord: String? = null

    private var lastLearnedKey: String? = null

    /** Called when a candidate is committed, by a tap or by auto-correct. */
    fun onCandidateAccepted(text: String) {
        acceptedWord = text
    }

    /** Called when an auto-correction is undone, so the replaced word is not learned. */
    fun onCandidateReverted() {
        acceptedWord = null
    }

    /** Called for states where the cursor is not at the end of a word, e.g. a selection or mid-word. */
    fun reset() {
        pendingWord = null
    }

    /**
     * Observes an editor state and returns the finished word to learn, if any.
     *
     * @param textBefore The text before the cursor.
     * @param cursor The absolute cursor position.
     * @param hasMoreBefore True if the editor has more text before [textBefore] that was not captured.
     * @param currentWord The partial word directly before the cursor, see [LatinTextAnalysis.currentWord].
     */
    fun observe(textBefore: String, cursor: Int, hasMoreBefore: Boolean, currentWord: String): LatinCompletedWord? {
        val textBeforeWord = textBefore.substring(0, textBefore.length - currentWord.length)
        val learnable = findLearnable(textBeforeWord, cursor - currentWord.length, hasMoreBefore)
        if (currentWord.isEmpty()) {
            pendingWord = null
        } else {
            pendingWord = currentWord
            pendingWordEnd = cursor
        }
        return learnable
    }

    private fun findLearnable(text: String, textEnd: Int, hasMoreBefore: Boolean): LatinCompletedWord? {
        val completed = LatinTextAnalysis.lastCompletedWord(text, hasMoreBefore) ?: return null
        val wordEnd = textEnd - completed.charsAfter
        val key = "$wordEnd:${completed.word}"
        if (key == lastLearnedKey) return null
        val typed = pendingWord
        // The last seen partial word may be a prefix of the finished one if states were skipped, as long as it was
        // being typed at that same spot
        val wasTyped = typed != null && completed.word.startsWith(typed) &&
            pendingWordEnd + (completed.word.length - typed.length) == wordEnd
        val accepted = acceptedWord
        val wasAccepted = accepted != null &&
            LatinSuggestionEngine.normalize(accepted) == LatinSuggestionEngine.normalize(completed.word)
        if (!wasTyped && !wasAccepted) return null
        lastLearnedKey = key
        if (wasAccepted) acceptedWord = null
        return completed
    }
}
