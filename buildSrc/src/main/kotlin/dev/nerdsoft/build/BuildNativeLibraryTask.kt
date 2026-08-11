package dev.nerdsoft.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecOperations
import java.io.File
import java.util.*
import javax.inject.Inject

/**
 * Builds the Rust/C++ compression bridge for a single target platform and
 * stages the resulting shared library under `outputDir/natives/<platform>/`.
 *
 * macOS is intentionally unsupported: Apple caps OpenGL at 4.1 and does not
 * reliably expose the BC7 (GL_ARB_texture_compression_bptc) or BC1/DXT
 * (GL_EXT_texture_compression_s3tc) extensions the native bridge targets.
 * NativeLibraryLoader.platformDirectory() on the Java side already returns
 * null for macOS and never attempts to load a native library there, so
 * building a macos-* artifact would just be dead weight in the jar. This
 * task refuses to build for apple-darwin targets rather than silently
 * producing an unused .dylib.
 */
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

    @get:Input
    @get:Optional
    abstract val bc7encRdoCommit: Property<String>

    /**
     * When true, use `cargo zigbuild` instead of plain `cargo build`. Needed
     * whenever the target triple differs from the host platform (e.g.
     * cross-compiling to Linux from Windows), since cc-rs otherwise expects
     * a native cross toolchain (e.g. x86_64-linux-gnu-g++) that usually
     * isn't installed. Defaults to false; set true explicitly for
     * cross-target builds.
     */
    @get:Input
    @get:Optional
    abstract val useZigbuild: Property<Boolean>

    @TaskAction
    fun build() {
        val bridgeDir = bridgeProjectDir.get().asFile
        val triple = targetTriple.orNull?.takeIf { it.isNotBlank() }

        if (triple != null && isAppleDarwin(triple)) {
            throw IllegalArgumentException(
                "Refusing to build the native bridge for '$triple': macOS is not supported. " +
                        "OpenGL is capped at 4.1 on macOS and does not reliably expose the BC7/BC1 " +
                        "compression extensions this bridge targets. NativeLibraryLoader never attempts " +
                        "to load a native library on macOS, so a macos-* artifact would go unused. " +
                        "If this ever changes, both this check and " +
                        "NativeLibraryLoader.platformDirectory() must be updated together."
            )
        }
        if (triple == null && hostOs() == "macos") {
            throw IllegalStateException(
                "Refusing to build the native bridge on macOS: this platform is not supported. " +
                        "See the class-level doc comment on BuildNativeLibraryTask for details."
            )
        }

        val useZig = useZigbuild.getOrElse(false)

        val cargoArgs = buildList {
            add("cargo")
            val toolchain = rustToolchain.orNull?.trim()
            if (!toolchain.isNullOrEmpty()) {
                add("+$toolchain")
            }
            if (useZig) {
                add("zigbuild")
            } else {
                add("build")
            }
            add("--release")
            if (triple != null) {
                add("--target")
                add(triple)
            }
        }

        execOperations.exec {
            workingDir = bridgeDir
            commandLine = cargoArgs
            val commit = bc7encRdoCommit.orNull?.trim()
            if (!commit.isNullOrEmpty()) {
                environment("TESSERA_BC7ENC_RDO_COMMIT", commit)
            }

            // target-cpu=native is only meaningful (and safe) when building
            // for the host's own CPU; skip it for cross builds where the
            // build machine's CPU features don't necessarily match the
            // target's.
            if (triple == null) {
                environment("RUSTFLAGS", "-C target-cpu=native")
            }
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

    private fun isAppleDarwin(triple: String): Boolean = triple.contains("apple-darwin")

    private fun libraryFileName(os: String): String = when (os) {
        "windows" -> "tessera_bridge.dll"
        else -> "libtessera_bridge.so"
    }

    private fun platformDirFromTriple(triple: String): String = when {
        triple.contains("pc-windows") -> "windows-x86_64"
        triple.contains("linux") && triple.startsWith("aarch64") -> "linux-aarch64"
        else -> "linux-x86_64"
    }

    private fun osFromTriple(triple: String): String = when {
        triple.contains("pc-windows") -> "windows"
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
