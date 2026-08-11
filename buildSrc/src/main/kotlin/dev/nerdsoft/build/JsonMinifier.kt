package dev.nerdsoft.build

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.io.File

object JsonMinifier {

    private val gson = Gson()

    @Suppress("unused")
    fun minifyInPlace(root: File, extensions: Set<String>) {
        if (!root.exists()) return

        root.walkTopDown()
            .filter { it.isFile && extensions.any { ext -> it.name.endsWith(ext) } }
            .toList()
            .parallelStream()
            .forEach { minifyFile(it) }
    }

    private fun minifyFile(file: File) {
        runCatching {
            val parsed: JsonElement = file.bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, JsonElement::class.java)
            }

            file.bufferedWriter(Charsets.UTF_8).use {
                gson.toJson(parsed, it)
            }
        }.onFailure {
            System.err.println("[Tessera Build] Failed minifying JSON: ${file.absolutePath} -> ${it.message}")
        }
    }
}