/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.AutoCorrectionCandidate
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.k3lp.runtime.K3Content
import java.io.File

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        private const val DICTIONARY_ASSET = "ime/dict/data.json"
        private const val BIGRAMS_ASSET = "ime/dict/bigrams.tsv"
        private const val UNVERIFIED_ASSET = "ime/dict/unverified.txt"
        private const val VALID_ASSET = "ime/dict/valid.txt"
        private const val USER_MODEL_DIR = "nlp"
        private const val USER_MODEL_FILE = "latin_user_model.json"
        private const val SAVE_DELAY_MS = 5_000L

        /**
         * Longest the editor waits for the dictionary when a word is ended. The wait happens while a key press is
         * being handled, so past this the correction is skipped rather than freezing the keyboard.
         */
        private const val EDITOR_LOCK_TIMEOUT_MS = 120L
        /**
         * Longest a spell check request waits for the dictionary. Browsers send whole paragraphs at once, and
         * those checks must never pile up in front of the typing itself.
         */
        private const val SPELL_LOCK_TIMEOUT_MS = 400L
        private const val SPELL_LOCK_POLL_MS = 8L
    }

    /** The auto-correction the suggestion bar computed for an exact editor state, reused when the word is ended. */
    private class CachedAutoCorrection(
        val textBefore: String,
        val cursor: Int,
        val correction: LatinAutoCorrection?,
    )

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore
    private val json = Json { ignoreUnknownKeys = true }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guarded by engineLock
    private val engineLock = Mutex()
    // Volatile so the editor can check readiness without waiting on engineLock
    @Volatile
    private var engine: LatinSuggestionEngine? = null
    private var wordData: Map<String, Int> = emptyMap()
    private var saveJob: Job? = null

    // Guarded by synchronized(learningTracker), kept off engineLock so candidate taps never wait on a suggestion run
    private val learningTracker = LatinLearningTracker()

    @Volatile
    private var cachedAutoCorrection: CachedAutoCorrection? = null

    /** Drops the cached auto-correction, called when the user undoes one so it is never applied again. */
    fun forgetCachedAutoCorrection() {
        cachedAutoCorrection = null
    }

    /**
     * Runs [block] with the engine for the editor, waiting at most [EDITOR_LOCK_TIMEOUT_MS] for a suggestion run
     * to finish. Returns null if the dictionary is not loaded yet or the wait timed out.
     */
    private suspend fun <T> withEngineForEditor(block: (LatinSuggestionEngine) -> T): T? {
        if (engine == null) {
            flogWarning { "Dictionary not loaded yet, auto-correct skipped" }
            return null
        }
        var finished = false
        val result = withTimeoutOrNull(EDITOR_LOCK_TIMEOUT_MS) {
            engineLock.withLock { block(ensureEngineLocked()) }.also { finished = true }
        }
        if (!finished) flogWarning { "Dictionary busy for ${EDITOR_LOCK_TIMEOUT_MS}ms, auto-correct skipped" }
        return result
    }

    override val providerId = ProviderId

    override suspend fun create() {
        // Loading is deferred to preload() so the keyboard can show before the dictionary is ready
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        engineLock.withLock {
            ensureEngineLocked()
        }
        Unit
    }

    private fun userModelFile(): File {
        return File(File(appContext.filesDir, USER_MODEL_DIR), USER_MODEL_FILE)
    }

    private fun ensureEngineLocked(): LatinSuggestionEngine {
        engine?.let { return it }
        val rawData = appContext.assets.readText(DICTIONARY_ASSET)
        val dictionary = json.decodeFromString(wordDataSerializer, rawData)
        // The next-word model and unverified list improve predictions and corrections, but are optional
        val bigrams = try {
            LatinBigramModel.parse(appContext.assets.readText(BIGRAMS_ASSET))
        } catch (e: Exception) {
            flogError { "Failed to load next-word model: $e" }
            LatinBigramModel.Empty
        } catch (e: OutOfMemoryError) {
            flogError { "Not enough memory for the next-word model: $e" }
            LatinBigramModel.Empty
        }
        val unverified = try {
            appContext.assets.readText(UNVERIFIED_ASSET).lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
        } catch (e: Exception) {
            flogError { "Failed to load unverified word list: $e" }
            emptySet()
        }
        val valid = try {
            appContext.assets.readText(VALID_ASSET).lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
        } catch (e: Exception) {
            flogError { "Failed to load the real word list: $e" }
            emptySet()
        }
        val newEngine = LatinSuggestionEngine(dictionary, bigrams, unverified, valid)
        try {
            val file = userModelFile()
            if (file.exists()) {
                newEngine.importUserData(json.decodeFromString(LatinUserModelData.serializer(), file.readText()))
            }
        } catch (e: Exception) {
            flogError { "Failed to load learned words, starting fresh: $e" }
        }
        wordData = dictionary
        engine = newEngine
        return newEngine
    }

    private fun scheduleSaveLocked() {
        val currentEngine = engine ?: return
        if (!currentEngine.isUserModelDirty) return
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    private suspend fun saveNow() = withContext(NonCancellable + Dispatchers.IO) {
        val data = engineLock.withLock {
            val currentEngine = engine ?: return@withContext
            if (!currentEngine.isUserModelDirty) return@withContext
            currentEngine.isUserModelDirty = false
            currentEngine.exportUserData()
        }
        try {
            val file = userModelFile()
            file.parentFile?.mkdirs()
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(json.encodeToString(LatinUserModelData.serializer(), data))
            if (!tmpFile.renameTo(file)) {
                file.delete()
                tmpFile.renameTo(file)
            }
        } catch (e: Exception) {
            flogError { "Failed to save learned words: $e" }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        // The spell checker runs in the keyboard's process, an error here must not close the keyboard
        val corrections = try {
            // Polled instead of queued on the lock: a queued check would hold up the editor behind it, and
            // browsers send dozens at once for a long paragraph
            var waited = 0L
            while (!engineLock.tryLock()) {
                if (waited >= SPELL_LOCK_TIMEOUT_MS) return SpellingResult.unspecified()
                delay(SPELL_LOCK_POLL_MS)
                waited += SPELL_LOCK_POLL_MS
            }
            try {
                ensureEngineLocked().spellCheck(word, maxSuggestionCount)
            } finally {
                engineLock.unlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flogError { "Spell check failed: ${e.stackTraceToString()}" }
            return SpellingResult.unspecified()
        }
        return when (corrections) {
            null -> SpellingResult.validWord()
            else -> SpellingResult.typo(corrections.toTypedArray())
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // A bug in here must never take the whole keyboard down, losing suggestions for one keystroke is fine
        return try {
            suggestUnsafe(content, maxCandidateCount, allowPossiblyOffensive, isPrivateSession)
        } catch (e: Exception) {
            flogError { "Suggestion failed: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun suggestUnsafe(
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val selection = content.selection
        if (selection.isNotCollapsed()) {
            synchronized(learningTracker) { learningTracker.reset() }
            return emptyList()
        }
        val textBefore = content.surroundingText.textBefore
        val textAfter = content.surroundingText.textAfter
        if (textAfter.isNotEmpty() && LatinTextAnalysis.isWordChar(textAfter[0])) {
            // Cursor is in the middle of a word, replacing only its left half would mangle it
            synchronized(learningTracker) { learningTracker.reset() }
            return emptyList()
        }
        val hasMoreBefore = selection.min > textBefore.length
        val currentWord = LatinTextAnalysis.currentWord(textBefore)
        val learnable = synchronized(learningTracker) {
            learningTracker.observe(textBefore, selection.min, hasMoreBefore, currentWord)
        }

        val autoCorrectEnabled = prefs.correction.autoCorrect.get()
        var autoCorrection: LatinAutoCorrection? = null
        var analyzed = false
        val words = engineLock.withLock {
            val currentEngine = ensureEngineLocked()
            if (learnable != null && !isPrivateSession) {
                if (currentEngine.learn(learnable.previous, learnable.word)) {
                    scheduleSaveLocked()
                }
            }
            if (currentWord.isEmpty()) {
                val previous = LatinTextAnalysis.previousWord(textBefore, hasMoreBefore)
                currentEngine.predictNext(previous, maxCandidateCount, allowPossiblyOffensive)
            } else {
                val textBeforeWord = textBefore.substring(0, textBefore.length - currentWord.length)
                // Same decision the keyboard makes when the word is ended, so the bar previews it exactly and the
                // keyboard can reuse it without searching again
                val analysis = currentEngine.analyze(
                    currentWord, textBeforeWord, hasMoreBefore, maxCandidateCount, allowPossiblyOffensive,
                    withAutoCorrection = autoCorrectEnabled,
                )
                autoCorrection = analysis.autoCorrection
                analyzed = autoCorrectEnabled
                analysis.words
            }
        }
        if (analyzed) {
            cachedAutoCorrection = CachedAutoCorrection(textBefore, selection.min, autoCorrection)
        }

        val candidates = ArrayList<SuggestionCandidate>(words.size + 1)
        autoCorrection?.let {
            candidates.add(AutoCorrectionCandidate(it.replacement, it.replacedText, sourceProvider = this))
        }
        for ((index, word) in words.withIndex()) {
            if (word == autoCorrection?.replacement) continue
            candidates.add(
                WordSuggestionCandidate(
                    text = word,
                    confidence = (1.0 - index * 0.05).coerceAtLeast(0.1),
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = true,
                    sourceProvider = this,
                )
            )
        }
        return candidates.take(maxCandidateCount)
    }

    /**
     * Decides the auto-correction for the word directly before the cursor, called by the editor at the moment the
     * user ends it. Returns null if nothing should change.
     *
     * @param previousCorrection See [LatinSuggestionEngine.autoCorrect].
     */
    suspend fun autoCorrect(
        textBefore: String,
        cursor: Int,
        allowPossiblyOffensive: Boolean,
        previousCorrection: Pair<String, String>?,
        touches: LatinTouchTrace? = null,
    ): AutoCorrectionCandidate? {
        // Called on the main thread when a word is ended: never wait for the dictionary to load, skip this one
        if (engine == null) return null
        return try {
            val currentWord = LatinTextAnalysis.currentWord(textBefore)
            if (currentWord.isEmpty()) return null
            val cached = cachedAutoCorrection
            val correction = if (
                previousCorrection == null && touches == null && cached != null && cached.cursor == cursor &&
                cached.textBefore == textBefore
            ) {
                // The suggestion bar already decided for this exact text. With touch points the decision is made
                // again, since the bar only knows the letters.
                cached.correction
            } else {
                val textBeforeWord = textBefore.substring(0, textBefore.length - currentWord.length)
                val hasMoreBefore = cursor > textBefore.length
                withEngineForEditor {
                    it.autoCorrect(
                        currentWord, textBeforeWord, hasMoreBefore, allowPossiblyOffensive, previousCorrection, touches,
                    )
                }
            } ?: return null
            AutoCorrectionCandidate(correction.replacement, correction.replacedText, sourceProvider = this)
        } catch (e: Exception) {
            flogError { "Auto-correct failed: ${e.stackTraceToString()}" }
            null
        }
    }

    /**
     * Takes a second look at the word before the one being ended, see [LatinSuggestionEngine.lookBack].
     *
     * @param textBeforeWord The text before the earlier word.
     * @param cursor The absolute position of the end of [textBeforeWord].
     */
    suspend fun lookBack(
        textBeforeWord: String,
        cursor: Int,
        visible: String,
        typed: String,
        next: String,
        allowPossiblyOffensive: Boolean,
    ): String? {
        return try {
            val hasMoreBefore = cursor > textBeforeWord.length
            withEngineForEditor {
                it.lookBack(textBeforeWord, hasMoreBefore, visible, typed, next, allowPossiblyOffensive)
            }
        } catch (e: Exception) {
            flogError { "Look-back failed: ${e.stackTraceToString()}" }
            null
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        synchronized(learningTracker) { learningTracker.onCandidateAccepted(candidate.text.toString()) }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate !is AutoCorrectionCandidate) return
        // The user undid the correction, so what they typed is a word they want to keep
        synchronized(learningTracker) { learningTracker.onCandidateReverted() }
        // A correction can span two words ("do home" to "go home"), only the words it changed are kept
        val originals = candidate.originalText.split(' ')
        val replacements = candidate.text.toString().split(' ')
        val kept = if (originals.size == replacements.size) {
            originals.filterIndexed { index, word -> word != replacements[index] }
        } else {
            emptyList()
        }
        if (kept.isEmpty()) return
        engineLock.withLock {
            val currentEngine = ensureEngineLocked()
            if (kept.map { currentEngine.acceptWord(it) }.any { it }) {
                scheduleSaveLocked()
            }
        }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return engineLock.withLock {
            val removed = ensureEngineLocked().block(candidate.text.toString())
            if (removed) scheduleSaveLocked()
            removed
        }
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return engineLock.withLock {
            ensureEngineLocked()
            wordData.keys.toList()
        }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return engineLock.withLock {
            ensureEngineLocked()
            (wordData[word] ?: 0) / 255.0
        }
    }

    override suspend fun destroy() {
        saveJob?.cancel()
        saveNow()
        ioScope.cancel()
    }
}
