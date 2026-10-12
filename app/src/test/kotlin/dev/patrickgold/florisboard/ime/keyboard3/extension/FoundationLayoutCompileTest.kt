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

package dev.patrickgold.florisboard.ime.keyboard3.extension

import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.compileToModel
import java.io.File
import java.net.URI

private val foundationDir: File by lazy {
    listOf(
        "src/main/assets/extensions/org.florisboard.k3.foundation",
        "app/src/main/assets/extensions/org.florisboard.k3.foundation",
    ).map { File(it) }.first { it.exists() }
}

private fun sourceFile(path: String): TextSourceFile {
    return TextSourceFile(object : SourceFileRef {
        override fun toString(): String = path
    }, File(foundationDir, path).readText())
}

// Mirrors loadFoundationKeyboard() in Loader.kt, which runs on the device at startup
@OptIn(WillRequireMigrationToRichErrors::class)
private suspend fun compileLayout(name: String): K3CompileResult {
    val importResolver = K3ImportResolver { path, _ ->
        val relPath = "import/" + runCatching { URI(path).path?.removePrefix("/") ?: "" }.getOrDefault(path)
        sourceFile(relPath)
    }
    val impliedImports = K3ImpliedImports(
        displays = listOf("flex://org.florisboard.k3.foundation/displays-implied.xml"),
        keys = listOf("flex://org.florisboard.k3.foundation/keys-implied.xml"),
    )
    return sourceFile("keyboard/$name").compileToModel(importResolver, impliedImports)
}

class FoundationLayoutCompileTest : FunSpec({
    test("the original QWERTZ layout compiles") {
        val result = compileLayout("qwertz.xml")
        result.reports.shouldBeEmpty()
        result.shouldBeInstanceOf<K3CompileResult.Success>()
    }

    test("the English QWERTY layout compiles without errors") {
        val result = compileLayout("qwerty.xml")
        result.reports.map { it.toString() }.shouldBeEmpty()
        result.shouldBeInstanceOf<K3CompileResult.Success>()
    }

    test("the English layout has QWERTY letter rows") {
        val result = compileLayout("qwerty.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val layers = result.model.layersByForm.touch.first().layers
        fun rows(layerId: org.k3lp.model.layer.K3LayerId) = layers.getValue(layerId).rows.map { row ->
            row.joinToString(" ") { it.value }
        }
        val base = rows(ImeLayerIds.Base)
        base[0] shouldBe "q w e r t y u i o p"
        base[1] shouldBe "a s d f g h j k l"
        base[2] shouldBe "base-to-shift z x c v b n m bksp"
        val shift = rows(ImeLayerIds.Shift)
        shift[0] shouldBe "Q W E R T Y U I O P"
        shift[2] shouldBe "shift-to-base Z X C V B N M bksp"
    }
})
