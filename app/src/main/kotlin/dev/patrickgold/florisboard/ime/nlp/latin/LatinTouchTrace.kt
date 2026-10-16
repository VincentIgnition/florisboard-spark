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

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * Where a key press landed, measured in key widths horizontally and key heights vertically, on the same grid as
 * [LatinKeyLayout].
 */
data class LatinKeyTouch(val x: Float, val y: Float)

/**
 * Centers of the letter keys a to z on the keyboard the user is typing on, in the units of [LatinKeyTouch].
 */
class LatinKeyLayout(centers: Map<Char, Pair<Float, Float>>) {
    private val xs = FloatArray(26) { Float.NaN }
    private val ys = FloatArray(26) { Float.NaN }

    init {
        for ((char, center) in centers) {
            val index = char.lowercaseChar() - 'a'
            if (index in 0 until 26) {
                xs[index] = center.first
                ys[index] = center.second
            }
        }
    }

    /** Squared distance from [touch] to the center of the key for [char], or null if that key is not on the layout. */
    fun distanceSquared(touch: LatinKeyTouch, char: Char): Float? {
        val index = char - 'a'
        if (index !in 0 until 26) return null
        val x = xs[index]
        if (x.isNaN()) return null
        val dx = touch.x - x
        val dy = touch.y - ys[index]
        return dx * dx + dy * dy
    }
}

/**
 * The touch points of the word being typed, one per character, null where a character was not typed by a tap on the
 * keyboard (pasted, picked from a popup, typed on a hardware keyboard).
 */
class LatinTouchTrace(
    val layout: LatinKeyLayout,
    val touches: List<LatinKeyTouch?>,
)
