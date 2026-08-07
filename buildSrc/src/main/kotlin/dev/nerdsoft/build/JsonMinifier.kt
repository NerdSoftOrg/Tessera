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
            .forEach { minifyFile(it) }
    }

    private fun minifyFile(file: File) {
        val parsed: JsonElement = runCatching {
            file.reader(Charsets.UTF_8).use { gson.fromJson(it, JsonElement::class.java) }
        }.getOrNull() ?: return

        file.writer(Charsets.UTF_8).use { gson.toJson(parsed, it) }
    }
}
