plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.143" apply false
}

stonecutter active "1.21.1"

stonecutter parameters {
    swaps["mod_version"] = "\"${properties.get<String>("mod_version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    constants["release"] = properties.get<String>("mod_id") != "template"
}
