plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
}

val modVersion: String by project
val minecraftVersion: String by project
val minecraftRange: String by project
val loaderVersion: String by project
val fabricVersion: String by project

version = modVersion
group = "dev.flipscout"

base {
    archivesName.set("flipscout-fabric-$minecraftVersion")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

sourceSets.main {
    // The platform-independent code lives in ../core.
    java.srcDir("../core/src/main/java")
    resources.srcDir("../core/src/main/resources")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftRange", minecraftRange)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_range" to minecraftRange, "java_version" to "21")
    }
}
