/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard3.ImeState
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.latin.LatinTouchTrace
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.inferFlorisApplication
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.kotlin.guardedByLock
import org.florisboard.lib.kotlin.collectIn
import org.florisboard.lib.kotlin.collectLatestIn
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3TextRange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

private const val BLANK_STR_PATTERN = "^\\s*$"

class NlpManager(context: Context) {
    private companion object {
        /** Same as AOSP LatinIME's config_delay_in_milliseconds_to_update_suggestions. */
        const val SUGGESTION_UPDATE_DELAY_MS = 100L
    }

    private val blankStrRegex = Regex(BLANK_STR_PATTERN)

    private val prefs by FlorisPreferenceStore
    val appContext = context.inferFlorisApplication()
    val imeController = appContext.imeController
    private val clipboardManager by context.clipboardManager()
    private val subtypeManager by context.subtypeManager()

    // Suggestions are optional: an error in a provider is logged instead of crashing the keyboard process
    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            flogError { "Suggestion task failed: ${e.stackTraceToString()}" }
        }
    )
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider(context)
    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    // Kept directly so the editor can reach it without the providers lock, which preloading holds for seconds
    private val latinProvider = LatinLanguageProvider(context)
    private val providers = guardedByLock {
        mapOf(
            LatinLanguageProvider.ProviderId to ProviderInstanceWrapper(latinProvider),
            //HanShapeBasedLanguageProvider.ProviderId to ProviderInstanceWrapper(HanShapeBasedLanguageProvider(context)),
        )
    }
    // lock unnecessary because values constant
    private val providersForceSuggestionOn = mutableMapOf<String, Boolean>()

    private val internalSuggestionsGuard = Mutex()
    private var internalSuggestions by Delegates.observable(SystemClock.uptimeMillis() to listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { assembleCandidates() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    val debugOverlaySuggestionsInfos = LruCache<Long, Pair<String, SpellingResult>>(10)
    var debugOverlayVersion = MutableStateFlow(0)

    init {
        clipboardManager.primaryClipFlow.collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
        imeController.activeState
            .map { state -> SuggestionRequest.of(state) }
            .distinctUntilChanged { old, new -> old?.key == new?.key }
            // Like AOSP LatinIME, which posts its suggestion update with a 100 ms delay and replaces it on every key
            // press: while the user types quickly only the state they pause on is worked out. The word they end
            // with a space is corrected on the spot regardless, see computeAutoCorrection.
            .debounce(SUGGESTION_UPDATE_DELAY_MS)
            // While one request runs, only the newest waiting one is kept, so the work never falls behind
            .conflate()
            .collectIn(scope) { request ->
                // Processed in order on purpose: providers learn from the sequence of editor states, and running
                // requests concurrently would let a later keystroke finish before an earlier one. Skipped states
                // are fine, the learning tracker is built for gaps.
                // Caught here so one failure does not end the collection and with it all future suggestions
                try {
                    if (request == null) {
                        clearSuggestions()
                    } else {
                        computeSuggestions(
                            subtype = subtypeManager.activeSubtype,
                            content = request.content,
                            isPrivateSession = request.isPrivateSession,
                            reqTime = SystemClock.uptimeMillis(),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    flogError { "Suggestion update failed: ${e.stackTraceToString()}" }
                }
            }
    }

    /**
     * Snapshot of the editor state relevant for suggestions, or null if suggestions must not be shown for the
     * current editor (passwords, number fields, raw key editors, or fields that opt out of suggestions).
     */
    private class SuggestionRequest(
        val content: K3Content,
        val isPrivateSession: Boolean,
        val key: String,
    ) {
        companion object {
            fun of(state: ImeState): SuggestionRequest? {
                val editor = state.editor
                if (editor.ic.get() == null) return null
                val info = editor.info
                val attributes = info.inputAttributes
                if (attributes.suggestionsBlockedReason != null || state.flags.keyVariation == KeyVariation.PASSWORD) {
                    return null
                }
                // Fields like browser address bars mostly see URLs and one-off searches, which would only pollute
                // the learned words
                val isPrivateSession = state.flags.isIncognitoMode || attributes.requestsNoSuggestions
                val content = state.content
                val surrounding = content.surroundingText
                val key = buildString {
                    append(content.selection.start).append(':').append(content.selection.end).append('|')
                    append(surrounding.textBefore).append('\u0000')
                    append(surrounding.textSelected).append('\u0000')
                    append(surrounding.textAfter).append('|')
                    append(isPrivateSession)
                }
                return SuggestionRequest(content, isPrivateSession, key)
            }
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return PunctuationRule.Fallback
    }

    private suspend fun getSpellingProvider(subtype: Subtype): SpellingProvider {
        return providers.withLock { it[subtype.nlpProviders.spelling] }?.provider as? SpellingProvider
            ?: FallbackNlpProvider
    }

    private suspend fun getSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? SuggestionProvider
            ?: FallbackNlpProvider
    }

    fun preload(subtype: Subtype) {
        scope.launch {
            emojiSuggestionProvider.preload(subtype)
            providers.withLock { providers ->
                subtype.nlpProviders.forEach { _, providerId ->
                    providers[providerId]?.let { provider ->
                        provider.createIfNecessary()
                        provider.preload(subtype)
                    }
                }
            }
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return getSpellingProvider(subtype).spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = false// TODO keyboardManager.activeState.isIncognitoMode,
        )
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): K3TextRange {
        return getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
            subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        )
    }

    fun providerForcesSuggestionOn(subtype: Subtype): Boolean {
        // Using a cache because I have no idea how fast the runBlocking is
        return providersForceSuggestionOn.getOrPut(subtype.nlpProviders.suggestion) {
            runBlocking {
                getSuggestionProvider(subtype).forcesSuggestionOn
            }
        }
    }

    fun isSuggestionOn(): Boolean =
        prefs.suggestion.enabled.get()
            || prefs.emoji.suggestionEnabled.get()
            || providerForcesSuggestionOn(subtypeManager.activeSubtype)

    fun suggest(subtype: Subtype, content: K3Content, isPrivateSession: Boolean = false) {
        val reqTime = SystemClock.uptimeMillis()
        scope.launch {
            computeSuggestions(subtype, content, isPrivateSession, reqTime)
        }
    }

    private suspend fun computeSuggestions(
        subtype: Subtype,
        content: K3Content,
        isPrivateSession: Boolean,
        reqTime: Long,
    ) {
        val emojiSuggestions = when {
            prefs.emoji.suggestionEnabled.get() -> {
                emojiSuggestionProvider.suggest(
                    subtype = subtype,
                    content = content,
                    maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    isPrivateSession = isPrivateSession,
                )
            }
            else -> emptyList()
        }
        val suggestions = when {
            emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                emptyList()
            }
            !prefs.suggestion.enabled.get() && !providerForcesSuggestionOn(subtype) -> {
                emptyList()
            }
            else -> {
                getSuggestionProvider(subtype).suggest(
                    subtype = subtype,
                    content = content,
                    maxCandidateCount = 8,
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    isPrivateSession = isPrivateSession,
                )
            }
        }
        internalSuggestionsGuard.withLock {
            if (internalSuggestions.first <= reqTime) {
                internalSuggestions = reqTime to buildList {
                    addAll(emojiSuggestions)
                    addAll(suggestions)
                }
            }
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to suggestions
        }
    }

    fun clearSuggestions() {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to emptyList()
        }
    }

    /**
     * Tells the candidate's provider it was picked. Must run before the candidate is committed, so the provider
     * already knows about it when the resulting editor change triggers the next suggestion round.
     */
    fun notifySuggestionAccepted(candidate: SuggestionCandidate) {
        val subtype = subtypeManager.activeSubtype
        runBlocking {
            candidate.sourceProvider?.notifySuggestionAccepted(subtype, candidate)
        }
    }

    /**
     * Tells the candidate's provider that its automatic commit was undone by the user.
     */
    /**
     * Decides, right now, how to auto-correct the word directly before the cursor when the user ends it. Runs
     * synchronously so the result never depends on the asynchronous suggestion bar having caught up.
     *
     * @param previousCorrection The visible and original text of an auto-correction applied right before the space
     *  in front of the current word, if any.
     */
    fun computeAutoCorrection(
        textBefore: String,
        cursor: Int,
        previousCorrection: Pair<String, String>?,
        touches: LatinTouchTrace? = null,
    ): AutoCorrectionCandidate? {
        if (subtypeManager.activeSubtype.nlpProviders.suggestion != LatinLanguageProvider.ProviderId) return null
        return runBlocking {
            latinProvider.autoCorrect(
                textBefore = textBefore,
                cursor = cursor,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                previousCorrection = previousCorrection,
                touches = touches,
            )
        }
    }

    /**
     * Takes a second look at the word before the one being ended, now that the word after it is known. Runs
     * synchronously like [computeAutoCorrection].
     *
     * @param textBeforeWord The text before the earlier word.
     * @param cursor The absolute position of the end of [textBeforeWord].
     * @param visible The earlier word as it stands in the editor.
     * @param typed What the user typed for the earlier word, differs from [visible] if it was auto-corrected.
     * @param nextOriginal The word being ended, as it stands in the editor.
     * @param nextFinal The word being ended, after its own auto-correction.
     * @return A correction replacing "[visible] [nextOriginal]" with the fixed earlier word and [nextFinal], or
     *  null to leave the earlier word as it is.
     */
    fun computeLookBack(
        textBeforeWord: String,
        cursor: Int,
        visible: String,
        typed: String,
        nextOriginal: String,
        nextFinal: String,
    ): AutoCorrectionCandidate? {
        if (subtypeManager.activeSubtype.nlpProviders.suggestion != LatinLanguageProvider.ProviderId) return null
        val replacement = runBlocking {
            latinProvider.lookBack(
                textBeforeWord = textBeforeWord,
                cursor = cursor,
                visible = visible,
                typed = typed,
                next = nextFinal,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            )
        } ?: return null
        if (replacement == visible) return null
        return AutoCorrectionCandidate(
            text = "$replacement $nextFinal",
            originalText = "$visible $nextOriginal",
            sourceProvider = latinProvider,
        )
    }

    fun notifySuggestionReverted(candidate: SuggestionCandidate) {
        val subtype = subtypeManager.activeSubtype
        // The cached decision for this text is the correction that was just rejected
        latinProvider.forgetCachedAutoCorrection()
        // Learning that the user kept their word needs the dictionary, which must not hold up the backspace
        scope.launch {
            candidate.sourceProvider?.notifySuggestionReverted(subtype, candidate)
        }
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        return activeCandidates.firstOrNull { it.isEligibleForAutoCommit }
    }

    fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return runBlocking { candidate.sourceProvider?.removeSuggestion(subtype, candidate) == true }.also { result ->
            if (result) {
                scope.launch {
                    // Need to re-trigger the suggestions algorithm
                    if (candidate is ClipboardSuggestionCandidate) {
                        assembleCandidates()
                    } else {
                        suggest(subtypeManager.activeSubtype, imeController.snapshotState().content)
                    }
                }
            }
        }
    }

    fun getListOfWords(subtype: Subtype): List<String> {
        return runBlocking { getSuggestionProvider(subtype).getListOfWords(subtype) }
    }

    fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return runBlocking { getSuggestionProvider(subtype).getFrequencyForWord(subtype, word) }
    }

    private fun assembleCandidates() {
        runBlocking {
            val candidates = when {
                isSuggestionOn() -> {
                    clipboardSuggestionProvider.suggest(
                        subtype = Subtype.DEFAULT,
                        content = imeController.snapshotState().content,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = false // TODO keyboardManager.activeState.isIncognitoMode,
                    ).ifEmpty {
                        buildList {
                            internalSuggestionsGuard.withLock {
                                addAll(internalSuggestions.second)
                            }
                        }
                    }
                }
                else -> emptyList()
            }
            activeCandidates = candidates
            autoExpandCollapseSmartbarActions(candidates, NlpInlineAutofill.suggestions.value)
        }
    }

    fun autoExpandCollapseSmartbarActions(list1: List<*>?, list2: List<*>?) {
        if (!prefs.smartbar.enabled.get()) {// || !prefs.smartbar.sharedActionsAutoExpandCollapse.get()) {
            return
        }
        // TODO: this is a mess and needs to be cleaned up in v0.5 with the NLP development
        /*if (keyboardManager.inputEventDispatcher.isRepeatableCodeLastDown()
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.DELETE)
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.FORWARD_DELETE)
            || keyboardManager.activeState.isActionsOverflowVisible
        ) {
            return // We do not auto switch if a repeatable action key was last pressed or if the actions overflow
                   // menu is visible to prevent annoying UI changes
        }*/
        val isSelection = imeController.snapshotState().content.selection.isNotCollapsed()
        val isExpanded = list1.isNullOrEmpty() && list2.isNullOrEmpty() || isSelection
        scope.launch {
            prefs.smartbar.sharedActionsExpandWithAnimation.set(false)
            prefs.smartbar.sharedActionsExpanded.set(isExpanded)
        }
    }

    fun addToDebugOverlay(word: String, info: SpellingResult) {
        debugOverlaySuggestionsInfos.put(System.currentTimeMillis(), word to info)
        debugOverlayVersion.update { it + 1 }
    }

    fun clearDebugOverlay() {
        debugOverlaySuggestionsInfos.evictAll()
        debugOverlayVersion.update { it + 1 }
    }

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private var isInstanceAlive = AtomicBoolean(false)

        suspend fun createIfNecessary() {
            if (!isInstanceAlive.getAndSet(true)) provider.create()
        }

        suspend fun preload(subtype: Subtype) {
            provider.preload(subtype)
        }

        suspend fun destroyIfNecessary() {
            if (isInstanceAlive.getAndSet(true)) provider.destroy()
        }
    }

    inner class ClipboardSuggestionProvider internal constructor(private val context: Context) : SuggestionProvider {
        private var lastClipboardItemId: Long = -1

        override val providerId = "org.florisboard.nlp.providers.clipboard"

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun preload(subtype: Subtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtype: Subtype,
            content: K3Content,
            maxCandidateCount: Int,
            allowPossiblyOffensive: Boolean,
            isPrivateSession: Boolean,
        ): List<SuggestionCandidate> {
            // Check if enabled
            if (!prefs.clipboard.suggestionEnabled.get()) return emptyList()

            val text = buildString {
                // TODO hack
                append(content.surroundingText.textBefore)
                append(content.surroundingText.textSelected)
                append(content.surroundingText.textAfter)
            }
            val currentItem = validateClipboardItem(clipboardManager.primaryClip, lastClipboardItemId, text)
                ?: return emptyList()

            return buildList {
                val now = System.currentTimeMillis()
                if ((now - currentItem.creationTimestampMs) < prefs.clipboard.suggestionTimeout.get() * 1000) {
                    add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider, context = context))
                    if (currentItem.isSensitive) {
                        return@buildList
                    }
                    if (currentItem.type == ItemType.TEXT) {
                        val text = currentItem.stringRepresentation()
                        val matches = buildList {
                            addAll(NetworkUtils.getEmailAddresses(text))
                            addAll(NetworkUtils.getUrls(text))
                            addAll(NetworkUtils.getPhoneNumbers(text))
                        }
                        matches.forEachIndexed { i, match ->
                            val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                                prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                            }
                            if (match.value != text && isUniqueMatch) {
                                add(ClipboardSuggestionCandidate(
                                    clipboardItem = currentItem.copy(
                                        // TODO: adjust regex of phone number so we don't need to manually strip the
                                        //  parentheses from the match results
                                        text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                            match.value.substring(1, match.value.length - 1)
                                        } else {
                                            match.value
                                        }
                                    ),
                                    sourceProvider = this@ClipboardSuggestionProvider,
                                    context = context,
                                ))
                            }
                        }
                    }
                }
            }
        }

        override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
            }
        }

        override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            return false
        }

        override suspend fun getListOfWords(subtype: Subtype): List<String> {
            return emptyList()
        }

        override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
            return 0.0
        }

        override suspend fun destroy() {
            // Do nothing
        }

        private fun validateClipboardItem(currentItem: ClipboardItem?, lastItemId: Long, contentText: String) =
            currentItem?.takeIf {
                // Check if already used
                it.id != lastItemId
                    // Check if content is empty
                    && contentText.isBlank()
                    // Check if clipboard content has any valid characters
                    && !currentItem.text.isNullOrBlank()
                    && !blankStrRegex.matches(currentItem.text)
            }
    }
}
