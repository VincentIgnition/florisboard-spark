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

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.getSelectedText
import androidx.compose.ui.text.input.getTextAfterSelection
import androidx.compose.ui.text.input.getTextBeforeSelection
import androidx.compose.ui.unit.dp
import androidx.emoji2.text.EmojiCompat
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
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import org.k3lp.lib.text.asK3String
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

private const val MAX_RESULTS = 80
private const val RESULT_ROWS = 2
private const val RESULT_CELL_DP = 46

/**
 * Emoji search panel opened from the emoji panel. Typing goes into the panel's own search box through a second
 * keyboard instance, the same approach as the GIF search. Results come from the CLDR names and keywords of the
 * emoji data, and tapping one types it into the app while the search stays open for more.
 */
@Composable
fun EmojiSearchLayout(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val storageController = LocalStorageController.current
    val imeState by imeController.activeState.collectAsState()
    val prefs by FlorisPreferenceStore
    val preferredSkinTone = prefs.emoji.preferredSkinTone.get()
    val scope = rememberCoroutineScope()

    var emojiData by remember { mutableStateOf(EmojiData.Fallback) }
    LaunchedEffect(Unit) {
        emojiData = EmojiData.get(context, FlorisLocale.ENGLISH)
    }

    // Same check as the emoji palette: only offer emojis this phone can actually draw
    val metadataVersion = imeState.editor.info.emojiCompatMetadataVersion
    val replaceAll = imeState.editor.info.emojiCompatReplaceAll
    val emojiCompatInstance by FlorisEmojiCompat.getAsFlow(replaceAll).collectAsState()
    val systemFontPaint = remember { Paint().apply { typeface = Typeface.DEFAULT } }
    var emojis by remember { mutableStateOf(emptyList<Emoji>()) }
    LaunchedEffect(emojiData, preferredSkinTone, emojiCompatInstance, metadataVersion) {
        val all = emojiData.bySkinTone[preferredSkinTone] ?: emptyList()
        emojis = withContext(Dispatchers.Default) {
            all.filter { emoji ->
                emojiCompatInstance?.getEmojiMatch(emoji.value, metadataVersion) == EmojiCompat.EMOJI_SUPPORTED ||
                    systemFontPaint.hasGlyph(emoji.value)
            }
        }
    }

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
    var historyVersion by remember { mutableIntStateOf(0) }
    val history = remember(historyVersion) { prefs.emoji.historyData.get() }
    var results by remember { mutableStateOf(emptyList<Emoji>()) }
    LaunchedEffect(emojis, query, historyVersion) {
        results = if (query.isBlank()) {
            // Before typing, offer what was used recently
            (history.pinned + history.recent).distinct()
        } else {
            val recent = history.recent.mapTo(HashSet()) { it.value }
            withContext(Dispatchers.Default) { rankEmojis(emojis, query, MAX_RESULTS, recent) }
        }
    }

    fun sendEmoji(emoji: Emoji) {
        scope.launch {
            imeController.updateState {
                emitText(emoji.value.asK3String())
            }
            EmojiHistoryHelper.markEmojiUsed(prefs, emoji)
            historyVersion++
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
                SnyggText(text = query.ifEmpty { "Search emoji" })
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
                .height((RESULT_CELL_DP * RESULT_ROWS).dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                results.isNotEmpty() -> LazyHorizontalGrid(
                    rows = GridCells.Fixed(RESULT_ROWS),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    items(results, key = { it.value }) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(RESULT_CELL_DP.dp)
                                .clickable { sendEmoji(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            EmojiText(
                                text = emoji.value,
                                emojiCompatInstance = emojiCompatInstance,
                            )
                        }
                    }
                }
                query.isBlank() -> SnyggText(text = "Type to search emoji")
                else -> SnyggText(text = "No emoji found for \"${query.trim()}\"")
            }
        }
        CompositionLocalProvider(LocalImeController provides searchInputMethod) {
            ImeKeyboardBox()
        }
    }
}

fun TextRange.toK3TextRange() = K3TextRange(start, end)

fun TextFieldValue.getSurroundingText(charsBefore: Int, charsAfter: Int) = K3SurroundingText(
    textBefore = getTextBeforeSelection(charsBefore).text,
    textSelected = getSelectedText().text,
    textAfter = getTextAfterSelection(charsAfter).text,
)
