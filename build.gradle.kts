@file:Suppress("RedundantSuppression")

import dev.nerdsoft.build.JsonMinifier
import dev.nerdsoft.build.BuildNativeLibraryTask

plugins {
    id("net.neoforged.moddev")
    id("neoforge-mutex")
}

val modId = property("mod.id") as String

version = "${property("mod.version")}+${sc.current.version}"
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
    version = property("neo.version") as String

    parchment {
        mappingsVersion = property("parchment.mappings.version") as String
        minecraftVersion = property("parchment.minecraft.version") as String
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            gameDirectory = file("../../run/")
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }

        register("data") {
            gameDirectory = file("../../run/")
            data()
            programArguments.addAll(
                "--mod", modId,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }
    }
}

// FIX CRÍTICO: Incluir tanto los recursos generados por DataGen como las DLLs de Rust
sourceSets.main {
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

tasks {
    val skipNativeBuild = providers.gradleProperty("tessera.skipNativeBuild")

    val buildNativeLibrary = register<BuildNativeLibraryTask>("buildNativeLibrary") {
        group = "tessera"
        description = "Builds the Rust/C++ compression bridge for the host platform and stages it under generated/natives"
        rustToolchain.set(providers.gradleProperty("deps.rust.toolchain"))
        bridgeProjectDir.set(rootProject.layout.projectDirectory.dir("native/bridge"))
        outputDir.set(layout.buildDirectory.dir("generated/natives"))
        targetTriple.set(providers.gradleProperty("tessera.native.target").orElse(""))
        onlyIf { !skipNativeBuild.isPresent }
    }

    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            put(key, value)
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        dependsOn(buildNativeLibrary)

        val props = buildMap {
            register("loader_version_range", "loader.version.range")
            register("mod_license", "mod.license")
            register("mod_id", "mod.id")
            register("mod_version", "mod.version")
            register("mod_name", "mod.name")
            register("mod_authors", "mod.authors")
            register("mod_description", "mod.description")
            register("neo_version_range", "neo.version.range")
            register("minecraft_version_range", "minecraft.version.range")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        doLast {
            JsonMinifier.minifyInPlace(destinationDir, setOf(".json", ".mcmeta"))
        }
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Build mod jar and copy result to `build/libs/{mod version}/`"

        dependsOn("jar")
        from(project.tasks.named("jar"))
        inputs.property("version", project.property("mod.version"))
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
