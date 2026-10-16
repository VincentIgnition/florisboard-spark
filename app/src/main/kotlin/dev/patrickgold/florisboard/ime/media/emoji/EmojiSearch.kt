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

package dev.patrickgold.florisboard.ime.media.emoji

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SCORE_WHOLE_NAME = 100
private const val SCORE_KEYWORD_EXACT = 12
private const val SCORE_NAME_WORD_EXACT = 10
private const val SCORE_KEYWORD_PREFIX = 6
private const val SCORE_NAME_WORD_PREFIX = 5
private const val SCORE_NAME_CONTAINS = 2
private const val SCORE_KEYWORD_CONTAINS = 1
private const val SCORE_RECENT = 3
/** Matching a term inside a longer word ("art" in "heart") only counts from this many letters on. */
private const val MIN_CONTAINS_LENGTH = 3

/** Splits a name or keyword into lowercase words, so "thumbs up: light skin tone" gives "thumbs", "up", ... */
private fun words(text: String): List<String> {
    return text.lowercase().split(' ', ':', '-', ',', '.', '’', '\'', '(', ')').filter { it.isNotEmpty() }
}

/** How well one search term matches [emoji], 0 if not at all. */
private fun termScore(emoji: Emoji, nameWords: List<String>, term: String): Int {
    var best = 0
    for (keyword in emoji.keywords) {
        val k = keyword.lowercase()
        val score = when {
            k == term -> SCORE_KEYWORD_EXACT
            k.startsWith(term) -> SCORE_KEYWORD_PREFIX
            term.length >= MIN_CONTAINS_LENGTH && k.contains(term) -> SCORE_KEYWORD_CONTAINS
            else -> 0
        }
        if (score > best) best = score
    }
    for (word in nameWords) {
        val score = when {
            word == term -> SCORE_NAME_WORD_EXACT
            word.startsWith(term) -> SCORE_NAME_WORD_PREFIX
            term.length >= MIN_CONTAINS_LENGTH && word.contains(term) -> SCORE_NAME_CONTAINS
            else -> 0
        }
        if (score > best) best = score
    }
    return best
}

/**
 * Ranks [emojis] for a search [query] by their CLDR names and keywords. Every word of the query has to match, so
 * "red heart" finds the red heart and not every heart. Exact keyword and name matches rank above words that only
 * start with the query, which rank above matches inside a word. Emojis in [recent] get a small boost, and ties
 * keep the order of [emojis], which follows the emoji palette.
 *
 * @param recent Values of recently used emojis.
 */
fun rankEmojis(
    emojis: List<Emoji>,
    query: String,
    limit: Int,
    recent: Set<String> = emptySet(),
): List<Emoji> {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty() || limit <= 0) return emptyList()
    val terms = words(normalized).ifEmpty { listOf(normalized) }
    val scored = ArrayList<Pair<Emoji, Int>>()
    for (emoji in emojis) {
        val name = emoji.name.lowercase()
        val nameWords = words(name)
        var total = 0
        var matchesAll = true
        for (term in terms) {
            val score = termScore(emoji, nameWords, term)
            if (score == 0) {
                matchesAll = false
                break
            }
            total += score
        }
        if (!matchesAll) continue
        if (name == normalized) total += SCORE_WHOLE_NAME
        if (emoji.value in recent) total += SCORE_RECENT
        scored.add(emoji to total)
    }
    // Stable sort: equal scores keep the palette order
    return scored
        .sortedWith(compareByDescending<Pair<Emoji, Int>> { it.second }.thenBy { it.first.name.length })
        .take(limit)
        .map { it.first }
}

suspend fun List<Emoji>.searchByInput(
    query: String,
    limit: Long,
): List<Emoji> = searchByInput(query, limit) { it }

suspend fun <R> List<Emoji>.searchByInput(
    query: String,
    limit: Long,
    transform: (Emoji) -> R,
): List<R> {
    val emojis = this
    return withContext(Dispatchers.Default) {
        rankEmojis(emojis, query, limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).map(transform)
    }
}
