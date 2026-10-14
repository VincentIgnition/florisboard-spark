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

package dev.patrickgold.florisboard.ime.media.gif

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

// Shaped like a GIPHY search response per https://developers.giphy.com/docs/api/schema/, trimmed to a few
// renditions and with sizes as strings, the way GIPHY sends them
private val SAMPLE_RESPONSE = """
{
  "data": [
    {
      "type": "gif",
      "id": "abc123",
      "url": "https://giphy.com/gifs/cat-abc123",
      "title": "Happy Cat GIF",
      "rating": "g",
      "images": {
        "fixed_height_small": {
          "url": "https://media.giphy.com/media/abc123/100.gif",
          "width": "178", "height": "100", "size": "123456",
          "webp": "https://media.giphy.com/media/abc123/100.webp", "webp_size": "45678",
          "mp4": "https://media.giphy.com/media/abc123/100.mp4"
        },
        "fixed_height": {
          "url": "https://media.giphy.com/media/abc123/200.gif",
          "width": "356", "height": "200"
        },
        "downsized": {
          "url": "https://media.giphy.com/media/abc123/giphy-downsized.gif",
          "width": "480", "height": "270", "size": "1500000"
        },
        "original": {
          "width": "480", "height": "270", "mp4": "https://media.giphy.com/media/abc123/giphy.mp4"
        }
      },
      "user": { "username": "someone" }
    },
    {
      "id": "nodownsized",
      "url": "https://giphy.com/gifs/nodownsized",
      "title": "",
      "images": {
        "fixed_height": {
          "url": "https://media.giphy.com/media/nodownsized/200.gif",
          "width": "200", "height": "200"
        }
      }
    },
    {
      "id": "unusable",
      "images": {}
    }
  ],
  "pagination": { "total_count": 3, "count": 3, "offset": 0 },
  "meta": { "status": 200, "msg": "OK" }
}
""".trimIndent()

class GiphyClientParseTest : FunSpec({
    test("parses GIPHY results and skips entries without usable renditions") {
        val items = GiphyClient.parseResponse(SAMPLE_RESPONSE)
        items shouldHaveSize 2

        val first = items[0]
        first.id shouldBe "abc123"
        first.title shouldBe "Happy Cat GIF"
        first.previewWidth shouldBe 178
        first.previewHeight shouldBe 100
        first.fullUrl shouldBe "https://media.giphy.com/media/abc123/giphy-downsized.gif"
        first.pageUrl shouldBe "https://giphy.com/gifs/cat-abc123"
    }

    test("falls back to the fixed height rendition when smaller ones are missing") {
        val item = GiphyClient.parseResponse(SAMPLE_RESPONSE)[1]
        item.previewUrl shouldBe "https://media.giphy.com/media/nodownsized/200.gif"
        item.fullUrl shouldBe "https://media.giphy.com/media/nodownsized/200.gif"
    }

    test("handles an empty result") {
        GiphyClient.parseResponse("""{"data": [], "meta": {"status": 200}}""") shouldHaveSize 0
    }
})

class GiphyNetworkErrorTest : FunSpec({
    test("names the cause of common network failures and keeps the detail") {
        val dns = GiphyClient.describeNetworkError(java.net.UnknownHostException("Unable to resolve host \"api.giphy.com\""))
        dns.startsWith("Couldn't look up GIPHY's address.") shouldBe true
        dns.endsWith("(UnknownHostException: Unable to resolve host \"api.giphy.com\")") shouldBe true

        val blocked = GiphyClient.describeNetworkError(java.net.SocketException("socket failed: EPERM (Operation not permitted)"))
        blocked.startsWith("Android denied FlorisBoard+ network access.") shouldBe true

        val timeout = GiphyClient.describeNetworkError(java.net.SocketTimeoutException("timeout"))
        timeout.startsWith("GIPHY took too long to respond.") shouldBe true

        val ssl = GiphyClient.describeNetworkError(javax.net.ssl.SSLHandshakeException("Trust anchor not found"))
        ssl.startsWith("The secure connection to GIPHY failed.") shouldBe true
    }
})
