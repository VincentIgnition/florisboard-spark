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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File

/** The base emojis of the bundled English data, without skin tone variants. */
private val englishEmojis: List<Emoji> by lazy {
    val file = listOf("src/main/assets/ime/media/emoji/en.txt", "app/src/main/assets/ime/media/emoji/en.txt")
        .map { File(it) }
        .first { it.exists() }
    file.readLines()
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") && !it.startsWith("\t") }
        .mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size < 3) null else Emoji(parts[0], parts[1], parts[2].split('|'))
        }
}

private fun search(query: String, limit: Int = 10, recent: Set<String> = emptySet()) =
    rankEmojis(englishEmojis, query, limit, recent).map { it.value }

class EmojiSearchTest : FunSpec({
    test("finds emojis by name") {
        search("thumbs up").first() shouldBe "👍"
        search("red heart").first() shouldBe "❤️"
        search("fire").first() shouldBe "🔥"
    }

    test("finds emojis by keyword and by the start of a word") {
        search("lol") shouldContain "😂"
        search("smil") shouldContain "🙂"
        search("pizza").first() shouldBe "🍕"
    }

    test("every word of the query has to match") {
        search("blue heart").first() shouldBe "💙"
        search("heart", limit = 100).size shouldBe search("heart", limit = 100).toSet().size
    }

    test("recently used emojis rank higher among equal matches") {
        search("heart", recent = setOf("💜")).first() shouldBe "💜"
    }

    test("returns nothing for nonsense or an empty query") {
        search("xyzzyq").shouldBeEmpty()
        search("   ").shouldBeEmpty()
    }
})
