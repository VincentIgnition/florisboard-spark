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

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A GIF as shown in the panel.
 *
 * @property previewUrl Small animated rendition for the results row.
 * @property fullUrl Rendition that gets sent, sized for messaging.
 * @property pageUrl Link to the GIF's page, inserted as text where the app does not accept images.
 */
data class GifItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val previewWidth: Int,
    val previewHeight: Int,
    val fullUrl: String,
    val pageUrl: String,
)

/**
 * Why a GIF request failed, phrased for the user.
 *
 * @property isNetworkProblem True if the request never got a response, as opposed to GIPHY answering with an error.
 */
class GifException(message: String, val isNetworkProblem: Boolean = false) : IOException(message)

/**
 * Minimal client for the GIPHY API (https://developers.giphy.com/docs/api/endpoint/). GIPHY is used because the Tenor
 * API was shut down for third-party apps on June 30, 2026.
 *
 * Free GIPHY keys are limited to 100 requests per hour, so callers should debounce searches.
 */
object GiphyClient {
    private const val BASE_URL = "https://api.giphy.com/v1/gifs"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_QUERY_LENGTH = 50

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val data: List<GifObject> = emptyList())

    @Serializable
    private data class GifObject(
        val id: String = "",
        val title: String = "",
        val url: String = "",
        val images: Images = Images(),
    )

    @Serializable
    private data class Images(
        val fixed_height_small: Rendition? = null,
        val fixed_height: Rendition? = null,
        val downsized: Rendition? = null,
    )

    // GIPHY sends every size as a string
    @Serializable
    private data class Rendition(
        val url: String? = null,
        val webp: String? = null,
        val width: String? = null,
        val height: String? = null,
    )

    suspend fun search(apiKey: String, query: String, rating: String, limit: Int = 24): List<GifItem> {
        val q = URLEncoder.encode(query.trim().take(MAX_QUERY_LENGTH), "UTF-8")
        return request("$BASE_URL/search?api_key=${encodeKey(apiKey)}&q=$q&limit=$limit&rating=$rating&lang=en")
    }

    suspend fun trending(apiKey: String, rating: String, limit: Int = 24): List<GifItem> {
        return request("$BASE_URL/trending?api_key=${encodeKey(apiKey)}&limit=$limit&rating=$rating")
    }

    private fun encodeKey(apiKey: String) = URLEncoder.encode(apiKey.trim(), "UTF-8")

    private suspend fun request(url: String): List<GifItem> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {}
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw GifException("GIPHY rejected the API key. Check it in Settings > Media.")
                429 -> throw GifException("GIPHY's hourly limit for this key was reached. Try again later.")
                else -> throw GifException("GIPHY returned an error ($code).")
            }
            parseResponse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: GifException) {
            throw e
        } catch (e: IOException) {
            throw GifException(describeNetworkError(e), isNetworkProblem = true)
        } finally {
            connection.disconnect()
        }
    }

    private const val NETWORK_BLOCKED_HINT = "If other apps are online, Android is probably blocking FlorisBoard+ " +
        "from the network. Check its app info for mobile data and Wi-Fi access, background data, Data Saver, or a " +
        "network permission toggle."

    /**
     * Turns a network failure into a message that names the likely cause, with the technical detail appended so a
     * report can pin it down.
     */
    internal fun describeNetworkError(e: IOException): String {
        val detail = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
        val message = e.message.orEmpty()
        val reason = when {
            e is java.net.UnknownHostException -> "Couldn't look up GIPHY's address. $NETWORK_BLOCKED_HINT"
            message.contains("EPERM") || message.contains("EACCES") ||
                message.contains("Permission denied", ignoreCase = true) ->
                "Android denied FlorisBoard+ network access. $NETWORK_BLOCKED_HINT"
            e is java.net.SocketTimeoutException -> "GIPHY took too long to respond. Try again."
            e is java.net.ConnectException -> "Couldn't connect to GIPHY. $NETWORK_BLOCKED_HINT"
            e is javax.net.ssl.SSLException -> "The secure connection to GIPHY failed. A VPN, ad blocker or " +
                "wrong system clock can cause this."
            else -> "Couldn't reach GIPHY."
        }
        return "$reason ($detail)"
    }

    internal fun parseResponse(body: String): List<GifItem> {
        return json.decodeFromString(Response.serializer(), body).data.mapNotNull { it.toGifItem() }
    }

    private fun GifObject.toGifItem(): GifItem? {
        val preview = images.fixed_height_small ?: images.fixed_height ?: return null
        // Animated WebP previews are about half the size of GIF, but only animate on Android 9 and newer
        val previewUrl = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) preview.webp else null)
            ?: preview.url ?: return null
        val full = images.downsized?.url ?: images.fixed_height?.url ?: return null
        return GifItem(
            id = id,
            title = title,
            previewUrl = previewUrl,
            previewWidth = preview.width?.toIntOrNull() ?: 100,
            previewHeight = preview.height?.toIntOrNull() ?: 100,
            fullUrl = full,
            pageUrl = url,
        )
    }

    /**
     * Downloads [url] to [target] unless it is already there, and returns [target].
     */
    suspend fun download(url: String, target: File): File = withContext(Dispatchers.IO) {
        if (target.exists() && target.length() > 0) return@withContext target
        target.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw GifException("Couldn't download the GIF (${connection.responseCode}).")
            }
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (e: GifException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw GifException("Couldn't download the GIF. ${describeNetworkError(e)}", isNetworkProblem = true)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Deletes cached GIFs older than [maxAgeMs], so the cache never grows without bound.
     */
    suspend fun pruneCache(dir: File, maxAgeMs: Long = 24L * 60 * 60 * 1000) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.walkBottomUp().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
