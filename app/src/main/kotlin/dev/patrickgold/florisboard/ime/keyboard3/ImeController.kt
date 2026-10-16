/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
import dev.patrickgold.florisboard.ime.text.key.KeyCode
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import android.icu.text.BreakIterator
import android.text.TextUtils
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.io.StorageController
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.extension.loadFoundationKeyboard
import dev.patrickgold.florisboard.ime.keyboard3.hint.FlickKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyArrangement
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelCache
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.ime.nlp.AutoCorrectionCandidate
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.EmojiSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.latin.LatinTextAnalysis
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.ime.nlp.latin.LatinKeyLayout
import dev.patrickgold.florisboard.ime.nlp.latin.LatinKeyTouch
import dev.patrickgold.florisboard.ime.nlp.latin.LatinTouchTrace
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

/**
 * Provides the [ImeController] instance this composition tree is associated with.
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

class ImeController(
    storageController: StorageController,
    val clipboardManager: ClipboardManager, // TODO ClipboardController
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()

    /** The most recent auto-correction, undoable by a backspace pressed right after it. */
    private var lastAutoCorrection: AppliedAutoCorrection? = null

    /**
     * The most recent auto-correction ended by a space, kept while the next word is typed so an accidental space
     * right after it can still be undone using what the user actually typed.
     */
    private var recentSpaceAutoCorrection: AppliedAutoCorrection? = null

    /**
     * Where the word restored by the latest undone auto-correction ends, so looking back from the next word never
     * changes it again.
     */
    private var revertedWordEnd: Int = -1
    /** The word restored by the latest undone auto-correction, see [revertedWordEnd]. */
    private var revertedWord: String? = null

    /** A key tap waiting for the text it produces, see [notePendingTap]. */
    private class PendingTap(val char: Char, val touch: LatinKeyTouch, val layout: LatinKeyLayout)
    private var pendingTap: PendingTap? = null

    // Where each letter of the word being typed was tapped, null for letters not typed by a tap
    private val wordTouches = ArrayList<LatinKeyTouch?>()
    private var wordTouchLayout: LatinKeyLayout? = null
    // Cursor position right after the last letter recorded in wordTouches, -1 if none
    private var wordTouchEnd = -1

    /**
     * Called by the touch handling right before a tapped letter key is typed, with where the finger landed. Auto-
     * correct uses it to tell a sloppy tap on the edge of a key from a deliberate one in its middle.
     */
    fun notePendingTap(char: Char, touch: LatinKeyTouch, layout: LatinKeyLayout) {
        pendingTap = PendingTap(char, touch, layout)
    }

    private data class AppliedAutoCorrection(
        val candidate: AutoCorrectionCandidate,
        val separator: String,
        val cursorAfter: Int,
    )

    val activeTouchModelOptions = combine<Any, TouchModelOptions>(
        prefs.keyboard.numberRow.asFlow(),
        prefs.keyboard.fnKeyEnabled.asFlow(),
        prefs.keyboard.fnKeyArrangement.asFlow(),
        prefs.keyboard.longPressKeyHintEnabled.asFlow(),
        prefs.keyboard.longPressKeyHintPlacement.asFlow(),
        prefs.keyboard.multiTapHighlightEnabled.asFlow(),
        prefs.keyboard.flickKeyHintEnabled.asFlow(),
        prefs.keyboard.flickKeyHintPlacement.asFlow(),
        prefs.keyboard.arrowRow.asFlow(),
    ) { values ->
        TouchModelOptions(
            showNumberRow = values[0] as Boolean,
            showArrowRow = values[8] as Boolean,
            fnKeyEnabled = values[1] as Boolean,
            fnKeyArrangement = values[2] as FnKeyArrangement,
            longPressKeyHintEnabled = values[3] as Boolean,
            longPressKeyHintPlacement = values[4] as LongPressKeyHintPlacement,
            multiTapHighlightEnabled = values[5] as Boolean,
            flickKeyHintEnabled = values[6] as Boolean,
            flickKeyHintPlacement = values[7] as FlickKeyHintPlacement,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    // TODO check if we can implement this differently
    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }

        // TODO proper stateful compilation
        scope.launch {
            loadFoundationKeyboard(this@ImeController, storageController.activeStorage.value)
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            // TODO KeyEvent.KEYCODE_SPACE (auto-commit candidate)
            KeyEvent.KEYCODE_DEL -> true
            KeyEvent.KEYCODE_FORWARD_DEL -> true
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun snapshotState(): ImeState = activeState.value

    // TODO evaluate if we can move to a clean coroutine-based approach in FlorisBoard
    //  for now this helper can be used to update the state from non-suspending contexts
    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: FlorisEditorInfo,
        ) {
            val newTouchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    newTouchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            val rememberCapsLockState = prefs.typing.rememberCapsLockState.get() &&
                state.flags.inputShiftState == InputShiftState.CAPS_LOCK

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = when {
                    rememberCapsLockState && newTouchLayerId.isTextLayer() -> ImeLayerIds.Caps
                    else -> newTouchLayerId
                },
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        when {
                            rememberCapsLockState -> InputShiftState.CAPS_LOCK
                            else -> InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        when (newTouchLayerId) {
                            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
                            else -> keyVariation != KeyVariation.PASSWORD &&
                                // TODO review if this is the correct approach for composing region support detection
                                //  important: for codemirror6 in browsers, it is important that composing is disabled,
                                //  else all sorts of weird behavior starts to occur
                                info.isRichInputEditor &&
                                info.inputAttributes.flagTextAutoCorrect
                        }
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            resetContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
            lastAutoCorrection = null
            recentSpaceAutoCorrection = null
            revertedWordEnd = -1
            revertedWord = null
            pendingTap = null
            wordTouches.clear()
            wordTouchEnd = -1
        }

        /** For updates that only report the new cursor, like cursor anchor info, which are never treated as belated. */
        fun handleUpdateSelection(newSelection: K3TextRange) {
            handleUpdateSelection(state.content.selection, newSelection)
        }

        fun handleUpdateSelection(oldSelection: K3TextRange, newSelection: K3TextRange) {
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            if (isBelatedUpdate(oldSelection, newSelection)) {
                flogDebug { "Belated update, skipped" }
                return
            }
            // A failure here runs outside any key press and would otherwise take the whole keyboard down
            try {
                resyncFromEditor(newSelection)
            } catch (e: Exception) {
                flogError { "Selection update failed: ${e.stackTraceToString()}" }
            }
        }

        /**
         * True if a selection update is an intermediate step on the way to the cursor position the keyboard already
         * expects, which happens when the editor falls behind delivering updates for fast typing. Reading the text
         * again for those would cost a round trip to the editor each time for nothing. Same rule as AOSP LatinIME's
         * RichInputConnection.isBelatedExpectedUpdate: the cursor moved from its old position toward the expected
         * one without passing it. An update that starts where the keyboard expects the cursor and moves away is
         * never belated, that is the editor or the user moving it.
         */
        private fun isBelatedUpdate(oldSelection: K3TextRange, newSelection: K3TextRange): Boolean {
            val expected = state.content.selection
            if (expected == oldSelection && newSelection != oldSelection) return false
            if (newSelection.start != newSelection.end) return false
            return (newSelection.start - oldSelection.start).toLong() * (expected.start - newSelection.start) >= 0 &&
                (newSelection.end - oldSelection.end).toLong() * (expected.end - newSelection.end) >= 0
        }

        /**
         * Reads the cursor and the text around it from the editor and replaces the keyboard's copy with it.
         *
         * @param fallbackSelection Used if the editor cannot report cursor and text in one call.
         */
        private fun resyncFromEditor(fallbackSelection: K3TextRange?) {
            // The update can describe an older state than the editor has by now, so cursor and text are read
            // together. Mixing a stale cursor with newer text makes later edits land in the wrong place.
            val startTime = SystemClock.uptimeMillis()
            val snapshot = state.editor.getSurroundingSnapshot(50, 10)
            if (snapshot != null) {
                resetContent(snapshot.first, snapshot.second)
            } else if (fallbackSelection != null) {
                resetContent(fallbackSelection, state.editor.getSurroundingText(50, 10))
            } else {
                resetContent()
            }
            val elapsed = SystemClock.uptimeMillis() - startTime
            if (elapsed >= SLOW_EDITOR_MS) {
                // Shows up in the debug log, a slow editor blocks the keyboard while it waits
                flogError { "Editor took ${elapsed}ms to report its text" }
            }
            expectedContentQueue.push(state.content)
        }

        /** After an unexpected error, gets back in sync with the editor so the next key lands in the right place. */
        private fun recoverFromError(what: String, e: Exception) {
            flogError { "$what failed: ${e.stackTraceToString()}" }
            try {
                resyncFromEditor(null)
            } catch (e2: Exception) {
                flogError { "Recovering from the error failed: ${e2.stackTraceToString()}" }
            }
        }

        override fun emitText(value: K3String) {
            val text = value.toText()
            val tap = pendingTap
            pendingTap = null
            val cursorBefore = state.content.selection.min
            // Auto-correct must never break typing, on any failure the key is simply typed as is
            val applied = try {
                applyAutoCorrectionBefore(text)
            } catch (e: Exception) {
                flogError { "Auto-correct failed: ${e.stackTraceToString()}" }
                null
            }
            try {
                super.emitText(value)
            } catch (e: Exception) {
                recoverFromError("Typing", e)
                lastAutoCorrection = null
                recentSpaceAutoCorrection = null
                return
            }
            expectedContentQueue.push(state.content)
            reevaluateInputShiftState()
            trackWordTouch(text, tap, cursorBefore)
            lastAutoCorrection = applied?.copy(cursorAfter = state.content.selection.min)
            if (applied != null && text == " ") {
                recentSpaceAutoCorrection = lastAutoCorrection
            } else if (text.length == 1 && text[0] in AUTO_CORRECT_SEPARATORS) {
                recentSpaceAutoCorrection = null
            }
        }

        /** Keeps [wordTouches] in step with the word being typed. */
        private fun trackWordTouch(text: String, tap: PendingTap?, cursorBefore: Int) {
            if (text.length == 1 && LatinTextAnalysis.isWordChar(text[0])) {
                // A letter typed anywhere but right after the recorded ones starts a new word
                if (cursorBefore != wordTouchEnd) wordTouches.clear()
                val touch = tap?.takeIf { it.char.lowercaseChar() == text[0].lowercaseChar() }
                wordTouches.add(touch?.touch)
                if (touch != null) wordTouchLayout = touch.layout
                wordTouchEnd = state.content.selection.min
            } else {
                wordTouches.clear()
                wordTouchEnd = -1
            }
        }

        /** The touch points of [currentWord] if every one of its letters was recorded, else null. */
        private fun touchTraceFor(currentWord: String, cursor: Int): LatinTouchTrace? {
            val layout = wordTouchLayout ?: return null
            if (cursor != wordTouchEnd || wordTouches.size != currentWord.length) return null
            if (wordTouches.all { it == null }) return null
            return LatinTouchTrace(layout, wordTouches.toList())
        }

        /**
         * If [separator] ends a word that has a pending auto-correction, replaces the word before the separator is
         * inserted.
         */
        private fun applyAutoCorrectionBefore(separator: String): AppliedAutoCorrection? {
            if (separator.length != 1 || separator[0] !in AUTO_CORRECT_SEPARATORS) return null
            // Why a word was left alone ends up in the debug log, the typed text itself never does
            fun skip(reason: String): AppliedAutoCorrection? {
                flogWarning { "Auto-correct skipped: $reason" }
                return null
            }
            if (!prefs.correction.autoCorrect.get()) return skip("auto-correct is off in settings")
            if (!prefs.suggestion.enabled.get()) return skip("suggestions are off in settings")
            val info = state.editor.info
            val attributes = info.inputAttributes
            attributes.suggestionsBlockedReason?.let { return skip(it) }
            // In fields like browser address bars only a space confirms a correction, so that a "." inside a
            // domain name never rewrites the word before it
            if (attributes.requestsNoSuggestions && separator != " ") return skip("address bar, only space corrects")
            when (state.flags.keyVariation) {
                KeyVariation.PASSWORD, KeyVariation.EMAIL_ADDRESS, KeyVariation.URI ->
                    return skip("field type ${state.flags.keyVariation}")
                else -> {}
            }
            val content = state.content
            val selection = content.selection
            if (selection.isNotCollapsed()) return skip("text is selected")
            val textBefore = content.surroundingText.textBefore
            val textAfter = content.surroundingText.textAfter
            if (textAfter.isNotEmpty() && LatinTextAnalysis.isWordChar(textAfter[0])) {
                return skip("cursor is inside a word")
            }
            val currentWord = LatinTextAnalysis.currentWord(textBefore)
            if (currentWord.isEmpty()) {
                return skip("no word before the cursor (keyboard sees ${textBefore.length} chars, cursor ${selection.min})")
            }
            // The user just undid the correction of this very word, ending it again keeps it as typed
            if (selection.min == revertedWordEnd && currentWord == revertedWord) {
                return skip("this correction was undone")
            }
            val nlpManager = FlorisApplication.getHack().nlpManager.value
            // Hint for undoing an accidental space right after the previous correction, see the field's docs
            val recent = recentSpaceAutoCorrection
            val previousCorrection = recent
                ?.takeIf { it.cursorAfter == selection.min - currentWord.length }
                ?.let { it.candidate.text.toString() to it.candidate.originalText }
            // Decided right now instead of taken from the suggestion bar, which may not have caught up yet
            val ownCorrection = nlpManager.computeAutoCorrection(
                textBefore, selection.min, previousCorrection, touchTraceFor(currentWord, selection.min),
            )
            // Undoing an accidental space already rewrote the word before, there is nothing left to look back at
            val isMerge = ownCorrection != null && ownCorrection.originalText.contains(' ')
            val candidate = if (isMerge) {
                ownCorrection
            } else {
                val nextFinal = ownCorrection?.takeIf { it.originalText == currentWord }?.text?.toString()
                    ?: currentWord
                val lookBack = try {
                    lookBackCandidate(textBefore, selection.min, currentWord, nextFinal, recent)
                } catch (e: Exception) {
                    flogError { "Look-back failed: ${e.stackTraceToString()}" }
                    null
                }
                lookBack ?: ownCorrection
            } ?: return null
            val original = candidate.originalText
            val corrected = candidate.text.toString()
            if (original.isEmpty() || !textBefore.endsWith(original) || corrected == original) return null
            if (selection.min < original.length) return null
            nlpManager.notifySuggestionAccepted(candidate)
            if (!state.editor.commitReplacingBefore(original.length, corrected)) return null
            resetContent(
                K3TextRange(selection.min - original.length + corrected.length),
                K3SurroundingText(
                    textBefore = textBefore.dropLast(original.length) + corrected,
                    textSelected = "",
                    textAfter = textAfter,
                ),
            )
            expectedContentQueue.push(state.content)
            return AppliedAutoCorrection(candidate, separator, cursorAfter = -1)
        }

        /**
         * Asks whether the word before [currentWord] should change now that the word after it is known ("its a"
         * becomes "it's a"). Returns a correction covering both words, or null.
         *
         * @param nextFinal [currentWord] after its own auto-correction.
         * @param recent The auto-correction applied when the earlier word was ended, if any, to learn what the user
         *  actually typed for it.
         */
        private fun lookBackCandidate(
            textBefore: String,
            cursor: Int,
            currentWord: String,
            nextFinal: String,
            recent: AppliedAutoCorrection?,
        ): AutoCorrectionCandidate? {
            val beforeCurrent = textBefore.dropLast(currentWord.length)
            // Only a single plain space between the two words, anything else is a deliberate break
            if (!beforeCurrent.endsWith(" ") || beforeCurrent.endsWith("  ")) return null
            val beforeSpace = beforeCurrent.dropLast(1)
            val visible = LatinTextAnalysis.currentWord(beforeSpace)
            if (visible.isEmpty()) return null
            val visibleEnd = cursor - currentWord.length - 1
            if (visibleEnd == revertedWordEnd) return null
            // The whole earlier word must be in the captured text, not cut off at its start
            val textBeforeVisible = beforeSpace.dropLast(visible.length)
            val visibleStart = visibleEnd - visible.length
            if (textBeforeVisible.isEmpty() && visibleStart > 0) return null
            val typed = recent
                ?.takeIf { it.cursorAfter == cursor - currentWord.length }
                ?.candidate
                ?.takeIf { !it.originalText.contains(' ') || it.text.contains(' ') }
                ?.takeIf { it.text.toString().substringAfterLast(' ') == visible }
                ?.originalText
                ?.substringAfterLast(' ')
                ?: visible
            val nlpManager = FlorisApplication.getHack().nlpManager.value
            return nlpManager.computeLookBack(textBeforeVisible, visibleStart, visible, typed, currentWord, nextFinal)
        }

        /**
         * Undoes [applied] if the cursor still sits right after the corrected word and its separator, restoring the
         * word exactly as the user typed it.
         */
        private fun revertAutoCorrection(applied: AppliedAutoCorrection): Boolean {
            val content = state.content
            val selection = content.selection
            if (selection.isNotCollapsed() || selection.min != applied.cursorAfter) return false
            val textBefore = content.surroundingText.textBefore
            val tail = applied.candidate.text.toString() + applied.separator
            if (!textBefore.endsWith(tail) || selection.min < tail.length) return false
            val original = applied.candidate.originalText
            if (!state.editor.commitReplacingBefore(tail.length, original)) return false
            resetContent(
                K3TextRange(selection.min - tail.length + original.length),
                K3SurroundingText(
                    textBefore = textBefore.dropLast(tail.length) + original,
                    textSelected = "",
                    textAfter = content.surroundingText.textAfter,
                ),
            )
            expectedContentQueue.push(state.content)
            FlorisApplication.getHack().nlpManager.value.notifySuggestionReverted(applied.candidate)
            return true
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            val windowController = FlorisImeService.windowControllerOrNull()
            when (descriptor) {
                // TODO once k3lp supports this remove
                ImeActions.Delete -> emitForwardDelete()
                // TODO evaluate use of modern cursor anchor API instead of sending raw key events
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                // TODO clipboard manager needs a rewrite to match the new state paradigm
                ImeActions.ClipboardCopy -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                        }
                    } else {
                        state.editor.performClipboardCopy()
                    }
                }
                ImeActions.ClipboardCut -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            emitBackspace()
                        }
                    } else {
                        state.editor.performClipboardCut()
                    }
                }
                ImeActions.ClipboardPaste -> {
                    if (state.editor.info.isRichInputEditor) {
                        emitClipboardItem(clipboardManager.primaryClip)
                    } else {
                        state.editor.performClipboardPaste()
                    }
                }
                ImeActions.ClipboardClearHistory -> clipboardManager.clearHistory()
                ImeActions.ClipboardClearFullHistory -> clipboardManager.clearFullHistory()
                ImeActions.ClipboardClearPrimaryClip -> {
                    if (prefs.clipboard.clearPrimaryClipAffectsHistoryIfUnpinned.get()) {
                        clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it, onlyIfUnpinned = true) }
                    }
                    clipboardManager.updatePrimaryClip(null)
                    // TODO we have no ref here:
                    //  appContext.showShortToastSync(R.string.clipboard__cleared_primary_clip)
                }
                ImeActions.SelectAll -> state.editor.performSelectAll()
                ImeActions.ShowImeWindow -> FlorisImeService.showUi()
                ImeActions.HideImeWindow -> FlorisImeService.hideUi()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.Undo -> state.editor.performUndo()
                ImeActions.Redo -> state.editor.performRedo()
                ImeActions.Settings -> FlorisImeService.launchSettings()
                ImeActions.ShowInputMethodPicker -> FlorisImeService.showImePicker()
                ImeActions.SwitchToPrevInputMethod -> FlorisImeService.switchToPrevInputMethod()
                ImeActions.SwitchToNextInputMethod -> FlorisImeService.switchToNextInputMethod()
                ImeActions.ShowSubtypePicker -> {} // TODO
                ImeActions.SwitchToPrevSubtype -> FlorisApplication.getHack().subtypeManager.value.switchToPrevSubtype()
                ImeActions.SwitchToNextSubtype -> FlorisApplication.getHack().subtypeManager.value.switchToNextSubtype()
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.ExternalVoiceInput -> FlorisImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
            reevaluateInputShiftState()
        }

        override fun emitBackspace() {
            val applied = lastAutoCorrection
            lastAutoCorrection = null
            recentSpaceAutoCorrection = null
            val reverted = applied != null && try {
                revertAutoCorrection(applied)
            } catch (e: Exception) {
                flogError { "Undoing auto-correct failed: ${e.stackTraceToString()}" }
                false
            }
            if (reverted) {
                revertedWordEnd = state.content.selection.min
                revertedWord = applied.candidate.originalText.substringAfterLast(' ')
                wordTouches.clear()
                wordTouchEnd = -1
                return
            }
            val cursorBefore = state.content.selection.min
            try {
                super.emitBackspace()
            } catch (e: Exception) {
                recoverFromError("Backspace", e)
                return
            }
            expectedContentQueue.push(state.content)
            // Deleting the last typed letter drops its touch point, anything else forgets them
            if (wordTouches.isNotEmpty() && cursorBefore == wordTouchEnd &&
                state.content.selection.min == cursorBefore - 1
            ) {
                wordTouches.removeAt(wordTouches.lastIndex)
                wordTouchEnd = state.content.selection.min
            } else {
                wordTouches.clear()
                wordTouchEnd = -1
            }
        }

        override fun emitEnter() {
            val info = state.editor.info
            val isShiftPressed = false // TODO inputEventDispatcher.isPressed(KeyCode.SHIFT)
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            // TODO request additional text if too low on context length
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        // TODO unicode
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1, // TODO
                )
            }
        }

        fun emitCandidate(candidate: SuggestionCandidate) {
//            scope.launch {
//                candidate.sourceProvider?.notifySuggestionAccepted(subtypeManager.activeSubtype, candidate)
//            }
            when (candidate) {
                is ClipboardSuggestionCandidate -> emitClipboardItem(candidate.clipboardItem)
                else -> emitCompletion(candidate)
            }
        }

        fun emitClipboardItem(item: ClipboardItem?): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    val text = item.text?.asK3String()
                    if (text != null) {
                        emitText(text)
                        true
                    } else {
                        false
                    }
                }
                ItemType.IMAGE, ItemType.VIDEO -> {
                    state.editor.performClipboardPasteMedia(item)
                }
            }.also {
                if (prefs.clipboard.historyHideOnPaste.get()) {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT)
                    )
                }
            }
        }

        fun emitCompletion(candidate: SuggestionCandidate): Boolean {
            val text = candidate.text.toString()
            if (text.isEmpty() || state.editor.info.isRawInputEditor) return false
            val content = state.content
            val selection = content.selection
            if (selection.isNotCollapsed()) return false
            val textBefore = content.surroundingText.textBefore
            val textAfter = content.surroundingText.textAfter

            val replaceLength: Int
            val suffix: String
            if (candidate is EmojiSuggestionCandidate) {
                replaceLength = emojiQueryLength(textBefore)
                suffix = ""
            } else if (candidate is AutoCorrectionCandidate && textBefore.endsWith(candidate.originalText)) {
                // May span two fragments, e.g. the merge of an accidental space
                replaceLength = candidate.originalText.length
                suffix = if (textAfter.firstOrNull()?.isWhitespace() == true) "" else " "
            } else {
                replaceLength = LatinTextAnalysis.currentWord(textBefore).length
                suffix = if (textAfter.firstOrNull()?.isWhitespace() == true) "" else " "
            }
            if (replaceLength > selection.min || replaceLength > textBefore.length) return false
            // Step over an existing space instead of inserting a second one
            val cursorOffset = if (suffix.isEmpty() && candidate !is EmojiSuggestionCandidate) 1 else 0
            val insertText = text + suffix
            if (!state.editor.commitReplacingBefore(replaceLength, insertText, cursorOffset)) return false

            val newTextBefore = textBefore.dropLast(replaceLength) + insertText + textAfter.take(cursorOffset)
            val newCursor = selection.min - replaceLength + insertText.length + cursorOffset
            resetContent(
                K3TextRange(newCursor),
                K3SurroundingText(
                    textBefore = newTextBefore,
                    textSelected = "",
                    textAfter = textAfter.drop(cursorOffset),
                ),
            )
            expectedContentQueue.push(state.content)
            return true
        }

        /**
         * Length of the ":query" an emoji suggestion was generated from, or 0 if there is none before the cursor.
         */
        private fun emojiQueryLength(textBefore: String): Int {
            val prefix = EmojiSuggestionType.LEADING_COLON.prefix
            var i = textBefore.length
            while (i > 0 && (textBefore[i - 1].isLetterOrDigit() || textBefore[i - 1] == '_' || textBefore[i - 1] == '-')) {
                i--
            }
            return if (prefix.isNotEmpty() && textBefore.startsWith(prefix, i - prefix.length)) {
                textBefore.length - i + prefix.length
            } else {
                textBefore.length - i
            }
        }

        override fun switchTouchLayer(newTouchLayerId: K3LayerId) {
            if (newTouchLayerId.isTextLayer()) {
                super.switchTouchLayer(state.flags.inputShiftState.correspondingLayerId)
            } else {
                super.switchTouchLayer(newTouchLayerId)
            }
        }

        fun cycleInputShiftState(isDoubleTap: Boolean) {
            val shiftKeyBehavior = prefs.typing.shiftKeyBehavior.get()
            val oldInputShiftState = state.flags.inputShiftState
            val newInputShiftState = when (shiftKeyBehavior) {
                ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP -> when {
                    isDoubleTap -> InputShiftState.CAPS_LOCK
                    oldInputShiftState == InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    else -> InputShiftState.UNSHIFTED
                }
                ShiftKeyBehavior.CAPSLOCK_BY_CYCLE -> when (oldInputShiftState) {
                    InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    InputShiftState.SHIFTED_MANUAL -> InputShiftState.CAPS_LOCK
                    InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
                    InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
                }
            }
            state = state.copy(
                touchLayerId = newInputShiftState.correspondingLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun revertCycleInputShiftState(
            newTouchLayerId: K3LayerId,
            newInputShiftState: InputShiftState,
        ) {
            state = state.copy(
                touchLayerId = newTouchLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun reevaluateInputShiftState() {
            val isPeekOngoing = false // TODO
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK || isPeekOngoing) return
            val capsMode = state.content.cursorCapsMode(state.editor.info.inputAttributes)
            val shift = prefs.typing.autoCapitalization.get()
                && capsMode != InputAttributes.CapsMode.NONE
                // && subtypeManager.activeSubtype.primaryLocale.supportsCapitalization
            val inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
            val touchLayerId = when {
                state.touchLayerId.isTextLayer() -> inputShiftState.correspondingLayerId
                else -> state.touchLayerId
            }
            state = state.copy(
                touchLayerId = touchLayerId,
                flags = state.flags.withInputShiftState(inputShiftState),
            )
        }

        override fun resetContent(newSelection: K3TextRange, newSurrounding: K3SurroundingText) {
            super.resetContent(newSelection, newSurrounding)
            reevaluateInputShiftState()
        }

        override fun resetContent() {
            super.resetContent()
            reevaluateInputShiftState()
        }

        fun handleFinishInputView() {
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
                return null
            }
            // TODO rework how we get the primary locale
            val locale = FlorisLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        // Include Emoji indicator in local composing. This is required so that emoji suggestion indicator
                        // can be detected in the composing text.
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()

        private const val AUTO_CORRECT_SEPARATORS = " .,!?;:"
        /** An editor answering slower than this is logged, since the keyboard waits for it. */
        private const val SLOW_EDITOR_MS = 300L
    }
}

enum class OperationUnit {
    CHARACTERS,
    WORDS;
}

enum class OperationScope {
    BEFORE_CURSOR,
    AFTER_CURSOR;
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3Content>()

    fun popUntilOrNull(predicate: (K3Content) -> Boolean): K3Content? {
        while (list.isNotEmpty()) {
            val item = list[0]
            if (predicate(item)) return item
            list.removeAt(0)
        }
        return null
    }

    fun push(item: K3Content) {
        list.add(item)
        // Editors that never report selection updates would otherwise make this grow forever
        if (list.size > MAX_SIZE) {
            list.removeAt(0)
        }
    }

    companion object {
        private const val MAX_SIZE = 64
    }

    fun clear() {
        list.clear()
    }
}

fun K3Content.cursorCapsMode(inputAttributes: InputAttributes): InputAttributes.CapsMode {
    return InputAttributes.CapsMode.fromFlags(
        TextUtils.getCapsMode(surroundingText.textBefore, surroundingText.textBefore.length, inputAttributes.raw)
    )
}
