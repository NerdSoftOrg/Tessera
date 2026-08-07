package dev.nerdsoft.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.inject.Inject

abstract class BuildNativeLibraryTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val rustToolchain: Property<String>

    @get:InputDirectory
    abstract val bridgeProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val targetTriple: Property<String>

    @TaskAction
    fun build() {
        val bridgeDir = bridgeProjectDir.get().asFile
        val triple = targetTriple.orNull?.takeIf { it.isNotBlank() }

        val cargoArgs = buildList {
            add("cargo")
            val toolchain = rustToolchain.orNull?.trim()
            if (!toolchain.isNullOrEmpty()) {
                add("+$toolchain")
            }
            add("build")
            add("--release")
            if (triple != null) {
                add("--target")
                add(triple)
            }
        }

        execOperations.exec {
            workingDir = bridgeDir
            commandLine = cargoArgs
        }

        val platformDir = triple?.let(::platformDirFromTriple) ?: platformDirFromHost()
        val libraryFileName = libraryFileName(triple?.let(::osFromTriple) ?: hostOs())

        val builtLibrary = locateBuiltLibrary(bridgeDir, triple, libraryFileName)
            ?: throw IllegalStateException(
                "Native bridge build did not produce $libraryFileName under ${bridgeDir.resolve("target")}"
            )

        val destinationDir = outputDir.get().dir("natives/$platformDir").asFile
        destinationDir.mkdirs()
        builtLibrary.copyTo(destinationDir.resolve(libraryFileName), overwrite = true)
    }

    private fun locateBuiltLibrary(bridgeDir: File, triple: String?, libraryFileName: String): File? {
        val targetRoot = bridgeDir.resolve("target")
        val candidate = if (triple != null) {
            targetRoot.resolve(triple).resolve("release").resolve(libraryFileName)
        } else {
            targetRoot.resolve("release").resolve(libraryFileName)
        }
        return candidate.takeIf { it.exists() }
    }

    private fun libraryFileName(os: String): String = when (os) {
        "windows" -> "tessera_bridge.dll"
        "macos" -> "libtessera_bridge.dylib"
        else -> "libtessera_bridge.so"
    }

    private fun platformDirFromTriple(triple: String): String = when {
        triple.contains("pc-windows") -> "windows-x86_64"
        triple.contains("apple-darwin") && triple.startsWith("aarch64") -> "macos-aarch64"
        triple.contains("apple-darwin") -> "macos-x86_64"
        triple.contains("linux") && triple.startsWith("aarch64") -> "linux-aarch64"
        else -> "linux-x86_64"
    }

    private fun osFromTriple(triple: String): String = when {
        triple.contains("pc-windows") -> "windows"
        triple.contains("apple-darwin") -> "macos"
        else -> "linux"
    }

    private fun hostOs(): String {
        val name = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            name.contains("win") -> "windows"
            name.contains("mac") -> "macos"
            else -> "linux"
        }
    }

    private fun platformDirFromHost(): String {
        val os = hostOs()
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val arch64 = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
        return "$os-$arch64"
    }
}
