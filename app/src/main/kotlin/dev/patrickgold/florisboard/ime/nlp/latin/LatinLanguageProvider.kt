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
        private const val USER_MODEL_DIR = "nlp"
        private const val USER_MODEL_FILE = "latin_user_model.json"
        private const val SAVE_DELAY_MS = 5_000L
    }

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore
    private val json = Json { ignoreUnknownKeys = true }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Everything below is guarded by engineLock, except the @Volatile fields
    private val engineLock = Mutex()
    private var engine: LatinSuggestionEngine? = null
    private var wordData: Map<String, Int> = emptyMap()
    private var saveJob: Job? = null
    private var lastLearnedKey: String? = null

    // The word being typed as of the previous suggest() call and the absolute cursor position at its end. A word is
    // only learned when the user actually typed it here, not when the cursor is merely moved next to old text.
    @Volatile
    private var pendingWord: String? = null
    @Volatile
    private var pendingWordEnd: Int = -1

    // The text of the most recently tapped candidate, so it is learned even though it was never typed out fully.
    @Volatile
    private var acceptedWord: String? = null

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
        val newEngine = LatinSuggestionEngine(dictionary)
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
        val corrections = engineLock.withLock {
            ensureEngineLocked().spellCheck(word, maxSuggestionCount)
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
        val selection = content.selection
        if (selection.isNotCollapsed()) {
            pendingWord = null
            return emptyList()
        }
        val textBefore = content.surroundingText.textBefore
        val textAfter = content.surroundingText.textAfter
        if (textAfter.isNotEmpty() && LatinTextAnalysis.isWordChar(textAfter[0])) {
            // Cursor is in the middle of a word, replacing only its left half would mangle it
            pendingWord = null
            return emptyList()
        }
        val hasMoreBefore = selection.min > textBefore.length
        val currentWord = LatinTextAnalysis.currentWord(textBefore)

        val result = engineLock.withLock {
            val currentEngine = ensureEngineLocked()
            if (currentWord.isEmpty()) {
                if (!isPrivateSession) {
                    learnLocked(currentEngine, textBefore, selection.min, hasMoreBefore)
                }
                pendingWord = null
                val previous = LatinTextAnalysis.previousWord(textBefore, hasMoreBefore)
                LatinSuggestionResult(
                    words = currentEngine.predictNext(previous, maxCandidateCount, allowPossiblyOffensive),
                    autoCorrection = null,
                )
            } else {
                pendingWord = currentWord
                pendingWordEnd = selection.min
                val textBeforeWord = textBefore.substring(0, textBefore.length - currentWord.length)
                val previous = LatinTextAnalysis.previousWord(textBeforeWord, hasMoreBefore)
                currentEngine.suggestDetailed(currentWord, previous, maxCandidateCount, allowPossiblyOffensive)
            }
        }

        val autoCorrection = result.autoCorrection.takeIf { prefs.correction.autoCorrect.get() }
        return result.words.mapIndexed { index, word ->
            if (index == 0 && word == autoCorrection) {
                AutoCorrectionCandidate(
                    text = word,
                    originalWord = currentWord,
                    sourceProvider = this,
                )
            } else {
                WordSuggestionCandidate(
                    text = word,
                    confidence = (1.0 - index * 0.05).coerceAtLeast(0.1),
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = true,
                    sourceProvider = this,
                )
            }
        }
    }

    private fun learnLocked(
        currentEngine: LatinSuggestionEngine,
        textBefore: String,
        cursor: Int,
        hasMoreBefore: Boolean,
    ) {
        val completed = LatinTextAnalysis.lastCompletedWord(textBefore, hasMoreBefore) ?: return
        val wordEnd = cursor - completed.charsAfter
        val typed = pendingWord
        val accepted = acceptedWord
        // Fast typing can skip intermediate editor states, so the last seen partial word may be a prefix of the
        // finished one, as long as it was being typed at that same spot
        val wasTyped = typed != null && completed.word.startsWith(typed) &&
            pendingWordEnd + (completed.word.length - typed.length) == wordEnd
        val wasAccepted = accepted != null &&
            LatinSuggestionEngine.normalize(accepted) == LatinSuggestionEngine.normalize(completed.word)
        if (!wasTyped && !wasAccepted) return
        val key = "$wordEnd:${completed.word}"
        if (key == lastLearnedKey) return
        lastLearnedKey = key
        acceptedWord = null
        if (currentEngine.learn(completed.previous, completed.word)) {
            scheduleSaveLocked()
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        acceptedWord = candidate.text.toString()
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate !is AutoCorrectionCandidate) return
        // The user undid the correction, so what they typed is a word they want to keep
        acceptedWord = null
        engineLock.withLock {
            if (ensureEngineLocked().acceptWord(candidate.originalWord)) {
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
