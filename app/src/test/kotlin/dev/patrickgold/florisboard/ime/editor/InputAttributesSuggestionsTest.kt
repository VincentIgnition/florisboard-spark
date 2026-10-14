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

package dev.patrickgold.florisboard.ime.editor

import android.text.InputType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class InputAttributesSuggestionsTest : FunSpec({
    test("browser address bar gets suggestions but is treated as restricted") {
        // What Chromium's UrlBar declares
        val attributes = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        attributes.suggestionsBlockedReason.shouldBeNull()
        attributes.requestsNoSuggestions shouldBe true
    }

    test("web text field with autocomplete off is treated like any other field") {
        // What Chromium sends for <input type="text" autocomplete="off">
        val raw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        val attributes = InputAttributes.wrap(raw)
        attributes.suggestionsBlockedReason.shouldBeNull()
        attributes.requestsNoSuggestions shouldBe false
    }

    test("multi-line native field asking for no suggestions still learns") {
        val raw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val attributes = InputAttributes.wrap(raw)
        attributes.suggestionsBlockedReason.shouldBeNull()
        attributes.requestsNoSuggestions shouldBe false
    }

    test("ordinary fields get suggestions") {
        val attributes = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT)
        attributes.suggestionsBlockedReason.shouldBeNull()
        attributes.requestsNoSuggestions shouldBe false
    }

    test("password fields never get suggestions") {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { variation ->
            InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or variation).suggestionsBlockedReason.shouldNotBeNull()
        }
    }

    test("number, phone and raw fields never get suggestions") {
        InputAttributes.wrap(InputType.TYPE_CLASS_NUMBER).suggestionsBlockedReason.shouldNotBeNull()
        InputAttributes.wrap(InputType.TYPE_CLASS_PHONE).suggestionsBlockedReason.shouldNotBeNull()
        InputAttributes.wrap(InputType.TYPE_NULL).suggestionsBlockedReason.shouldNotBeNull()
    }
})
