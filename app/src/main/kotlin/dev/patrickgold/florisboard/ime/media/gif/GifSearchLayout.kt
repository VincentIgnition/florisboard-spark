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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.io.LocalStorageController
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.ImeEditor
import dev.patrickgold.florisboard.ime.keyboard3.ImeState
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.ui.ImeKeyButton
import dev.patrickgold.florisboard.ime.keyboard3.ui.ImeKeyboardBox
import dev.patrickgold.florisboard.ime.media.emoji.getSurroundingText
import dev.patrickgold.florisboard.ime.media.emoji.toK3TextRange
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import org.k3lp.lib.text.asK3String
import org.k3lp.runtime.K3TextRange
import java.io.File
import java.lang.ref.WeakReference

private const val SEARCH_DEBOUNCE_MS = 450L
private const val MIN_QUERY_LENGTH = 2
private const val RESULTS_ROW_HEIGHT_DP = 110
private const val GIF_MIME_TYPE = "image/gif"

private sealed interface GifPanelState {
    data object MissingKey : GifPanelState
    data object Loading : GifPanelState
    data class Results(val items: List<GifItem>) : GifPanelState
    data class Message(val text: String) : GifPanelState
    data class NetworkBlocked(val text: String) : GifPanelState
}

/**
 * Returns why this app can't use the network, or null if it can as far as Android tells it.
 *
 * GrapheneOS and similar systems deliberately present a revoked Network permission as the network being down rather
 * than as a missing permission, so "no network from this app's point of view" is the signal that matters. A real
 * outage looks the same, which the message accounts for.
 */
private fun networkAccessProblem(context: Context): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
        return "FlorisBoard+'s Network permission is off. Turn it on in its app info to search GIFs."
    }
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val capabilities = try {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    } catch (e: SecurityException) {
        return null
    }
    if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return "FlorisBoard+ has no network access. If your phone is online, its Network permission is probably " +
            "off. Turn it on in its app info to search GIFs."
    }
    return null
}

private fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun gifCacheDir(context: Context) = File(context.cacheDir, "gifs")

private fun rememberGifImageLoader(context: Context): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()
}

/**
 * GIF search panel opened from the emoji panel. Typing goes into the panel's own search box through a second
 * keyboard instance, the same approach as the emoji search.
 */
@Composable
fun GifSearchLayout(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val storageController = LocalStorageController.current
    val imeState by imeController.activeState.collectAsState()
    val prefs by FlorisPreferenceStore

    val apiKey = prefs.gif.giphyApiKey.get().trim()
    val rating = if (prefs.suggestion.blockPossiblyOffensive.get()) "pg-13" else "r"
    val imageLoader = remember { rememberGifImageLoader(context) }
    val scope = rememberCoroutineScope()

    val editor = remember {
        object : ImeEditor(WeakReference(null), FlorisEditorInfo.Unspecified) {
            var value by mutableStateOf(TextFieldValue())

            override fun replaceText(
                range: IntRange,
                text: String,
                newSelection: K3TextRange,
                newComposition: K3TextRange?,
            ) {
                value = value.copy(
                    text = value.text.replaceRange(range, text),
                    selection = newSelection.let { TextRange(it.start, it.end) },
                    composition = newComposition?.let { TextRange(it.start, it.end) },
                )
            }

            override fun setComposition(newComposition: K3TextRange?) {
                value = value.copy(composition = newComposition?.let { TextRange(it.start, it.end) })
            }
        }
    }
    val searchInputMethod = remember {
        ImeController(
            storageController,
            imeController.clipboardManager,
            ImeState(editor = editor),
            imeController.touchModelCache,
        )
    }
    LaunchedEffect(imeState.model) {
        searchInputMethod.updateState {
            switchModel(imeState.model)
        }
    }

    val query = editor.value.text
    var panelState by remember { mutableStateOf<GifPanelState>(GifPanelState.Loading) }
    var trendingCache by remember { mutableStateOf<List<GifItem>?>(null) }

    LaunchedEffect(Unit) {
        GiphyClient.pruneCache(gifCacheDir(context))
    }

    LaunchedEffect(query, apiKey) {
        if (apiKey.isEmpty()) {
            panelState = GifPanelState.MissingKey
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        networkAccessProblem(context)?.let { problem ->
            panelState = GifPanelState.NetworkBlocked(problem)
            return@LaunchedEffect
        }
        try {
            if (trimmed.length < MIN_QUERY_LENGTH) {
                // Trending is fetched once per panel opening to save the key's hourly requests
                val trending = trendingCache ?: GiphyClient.trending(apiKey, rating).also { trendingCache = it }
                panelState = GifPanelState.Results(trending)
            } else {
                // Wait for typing to pause, a free key only allows 100 requests per hour
                delay(SEARCH_DEBOUNCE_MS)
                panelState = GifPanelState.Loading
                val results = GiphyClient.search(apiKey, trimmed, rating)
                panelState = if (results.isEmpty()) {
                    GifPanelState.Message("No GIFs found for \"$trimmed\".")
                } else {
                    GifPanelState.Results(results)
                }
            }
        } catch (e: GifException) {
            panelState = if (e.isNetworkProblem) {
                GifPanelState.NetworkBlocked(e.message ?: "Couldn't reach GIPHY.")
            } else {
                GifPanelState.Message(e.message ?: "Something went wrong.")
            }
        }
    }

    fun sendGif(item: GifItem) {
        scope.launch {
            try {
                val file = GiphyClient.download(item.fullUrl, File(gifCacheDir(context), "${item.id}.gif"))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider.file", file)
                val linkUri = item.pageUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                val sent = imeController.snapshotState().editor.commitImage(
                    uri = uri,
                    mimeType = GIF_MIME_TYPE,
                    description = item.title.ifBlank { "GIF" },
                    linkUri = linkUri,
                )
                if (!sent) {
                    // The app does not accept images here, fall back to the GIF's link like other keyboards do
                    val link = item.pageUrl.ifBlank { item.fullUrl }
                    imeController.updateState {
                        emitText(link.asK3String())
                    }
                }
            } catch (e: GifException) {
                panelState = GifPanelState.Message(e.message ?: "Couldn't send the GIF.")
            }
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier.fillMaxWidth(),
    ) {
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggButton(onClick = onBack) {
                SnyggText(text = "BACK")
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                SnyggText(text = query.ifEmpty { "Search GIPHY" })
            }
            ImeKeyButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                output = ImeActions.Backspace,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RESULTS_ROW_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = panelState) {
                GifPanelState.MissingKey -> SnyggText(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    text = "GIF search needs a free GIPHY API key. Add it in FlorisBoard+ settings under Media.",
                )
                GifPanelState.Loading -> SnyggText(text = "Loading GIFs...")
                is GifPanelState.Message -> SnyggText(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    text = state.text,
                )
                is GifPanelState.NetworkBlocked -> Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SnyggText(text = state.text)
                    SnyggButton(onClick = { openAppInfo(context) }) {
                        SnyggText(text = "Open app info")
                    }
                }
                is GifPanelState.Results -> LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        GifPreview(
                            item = item,
                            imageLoader = imageLoader,
                            onClick = { sendGif(item) },
                        )
                    }
                }
            }
        }
        SnyggText(
            modifier = Modifier.padding(horizontal = 8.dp),
            text = "Powered by GIPHY",
        )
        CompositionLocalProvider(LocalImeController provides searchInputMethod) {
            ImeKeyboardBox()
        }
    }
}

@Composable
private fun GifPreview(
    item: GifItem,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(item.id) {
        // Previews are fetched by the keyboard itself, the app bundles no network stack for the image library
        val extension = if (item.previewUrl.contains(".webp")) "webp" else "gif"
        file = try {
            GiphyClient.download(item.previewUrl, File(gifCacheDir(context), "preview/${item.id}.$extension"))
        } catch (e: GifException) {
            null
        }
    }
    val aspect = item.previewWidth.toFloat() / item.previewHeight.coerceAtLeast(1)
    val width = (RESULTS_ROW_HEIGHT_DP * aspect).coerceIn(60f, 220f).dp
    Box(
        modifier = Modifier
            .height(RESULTS_ROW_HEIGHT_DP.dp)
            .width(width)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        val currentFile = file
        if (currentFile != null) {
            val request = remember(currentFile) {
                ImageRequest.Builder(context)
                    .data(currentFile)
                    // Hardware bitmaps crash inside the keyboard window, see SnyggBox
                    .allowHardware(false)
                    .build()
            }
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(RESULTS_ROW_HEIGHT_DP.dp)
                    .width(width),
            )
        }
    }
}
