@file:Suppress("AvoidApplyPluginMethod")

import dev.nerdsoft.build.BuildNativeLibraryTask
import dev.nerdsoft.build.JsonMinifier

plugins {
    id("net.neoforged.moddev")
    id("neoforge-mutex")
    id("idea")
}

val modId = property("mod_id") as String

version = "${property("mod_version")}+${sc.current.version}"
base.archivesName = modId

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

allprojects {
    repositories {
        mavenCentral()

        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged"
        }
    }
}

neoForge {
    version = sc.properties["neo_version"] as String

    parchment {
        mappingsVersion = sc.properties["parchment_mappings_version"] as String
        minecraftVersion = sc.properties["parchment_minecraft_version"] as String
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.named("main").get())
        }
    }

    runs {
        all {
            val runDir = rootProject.file("versions/${sc.current.version}/run")
            if (!runDir.exists()) runDir.mkdirs()
            gameDirectory = runDir
        }

        register("client") {
            this.client()
            sourceSet = sourceSets.named("main").get()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }

        register("data") {
            this.data()
            sourceSet = sourceSets.named("main").get()
            programArguments.addAll(
                "--mod", modId,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }
    }
}

sourceSets.named("main") {
    resources {
        srcDir("src/generated/resources")
        srcDir(layout.buildDirectory.dir("generated/natives"))
    }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor.set(JvmVendorSpec.ADOPTIUM)
        languageVersion.set(JavaLanguageVersion.of(requiredJava.majorVersion))
    }
}

if (project.hasProperty("release")) {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-g:none")
    }
}

val explicitTarget = providers.gradleProperty("tessera.native.target").orElse("").get()

val targetClassifier = if (explicitTarget.isNotBlank()) {
    when {
        explicitTarget.contains("pc-windows") -> "windows-x86_64"
        explicitTarget.contains("linux") && explicitTarget.startsWith("aarch64") -> "linux-aarch64"
        explicitTarget.contains("linux") -> "linux-x86_64"
        else -> explicitTarget.replace("-", "_")
    }
} else {
    val isFatBuild = providers.gradleProperty("tessera.fatJar")
        .map { it.toBoolean() }
        .orElse(false)
        .get()

    if (isFatBuild) {
        ""
    } else {
        val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows" else "linux"
        val arch = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }) "aarch64" else "x86_64"
        "$os-$arch"
    }
}

tasks {
    val skipNativeBuild = providers.gradleProperty("tessera.skipNativeBuild")
        .map { it.toBoolean() }
        .orElse(false)

    val targetsToBuild = if (explicitTarget.isNotBlank()) {
        listOf(explicitTarget)
    } else {
        listOf(
            "x86_64-pc-windows-msvc",
            "x86_64-unknown-linux-gnu",
            "aarch64-unknown-linux-gnu"
        )
    }

    val nativeBuildTasks = targetsToBuild.map { target ->
        val sanitizedName = target.replace("-", "_").replace(".", "_")
        register<BuildNativeLibraryTask>("buildNativeLibrary_${sanitizedName}") {
            group = "tessera"
            description = "Builds the compression bridge for target $target"

            rustToolchain.set(sc.properties["deps_rust_toolchain"] as String)
            bridgeProjectDir.set(rootProject.layout.projectDirectory.dir("native/bridge"))
            outputDir.set(layout.buildDirectory.dir("generated/natives"))
            targetTriple.set(target)

            val isHostWindows = System.getProperty("os.name").lowercase().contains("win")
            val isLinuxTarget = target.contains("linux")
            val defaultUseZig = isHostWindows && isLinuxTarget

            val useZigOption = providers.gradleProperty("tessera.useZigbuild")
                .map { it.toBoolean() }
                .orElse(defaultUseZig)
                .get()

            useZigbuild.set(useZigOption)

            bc7encRdoCommit.set(sc.properties["deps_bc7enc_rdo_commit"] as String)

            onlyIf { !skipNativeBuild.get() }
        }
    }

    val buildNativeLibrary = register("buildNativeLibrary") {
        group = "tessera"
        description = "Builds native libraries for all configured targets"
        dependsOn(nativeBuildTasks)
    }

    processResources {
        fun MutableMap<String, String>.register(key: String, prop: String) {
            val value: String = sc.properties[prop] as String
            inputs.property(key, value)
            put(key, value)
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        dependsOn(buildNativeLibrary)

        val props = buildMap {
            register("loader_version_range", "loader_version_range")
            register("mod_license", "mod_license")
            register("mod_id", "mod_id")
            register("mod_version", "mod_version")
            register("mod_name", "mod_name")
            register("mod_authors", "mod_authors")
            register("mod_description", "mod_description")
            register("mod_issues", "mod_issues")
            register("neo_version_range", "neo_version_range")
            register("minecraft_version_range", "minecraft_version_range")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        doLast {
            JsonMinifier.minifyInPlace(destinationDir, setOf(".json", ".mcmeta"))
        }
    }

    withType<Jar>().configureEach {
        archiveClassifier.set(targetClassifier)

        entryCompression = ZipEntryCompression.DEFLATED
        dependsOn(processResources)

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/maven/**")
        exclude("**/*.kotlin_module")

        exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/INDEX.LIST")

        exclude("**/*.kotlin_builtins")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Build mod jar and copy result to `build/libs/{mod version}/`"

        dependsOn("jar")
        from(project.tasks.named("jar"))
        inputs.property("version", project.property("mod_version"))
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod_version")}"))
    }
}
