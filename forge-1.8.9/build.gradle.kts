plugins {
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

val modVersion: String by project

version = modVersion
group = "dev.flipscout"

base {
    archivesName.set("flipscout-forge-1.8.9")
}

java {
    // Minecraft 1.8.9 runs on Java 8.
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
}

sourceSets.main {
    // The platform-independent code lives in ../core.
    java.srcDir("../core/src/main/java")
    resources.srcDir("../core/src/main/resources")
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("mcmod.info") {
        expand("version" to project.version)
    }
}

tasks.jar {
    // Keep the unmapped jar out of build/libs so only the usable one ends up there.
    archiveClassifier.set("dev")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
}
