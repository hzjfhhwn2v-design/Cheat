plugins {
    // 26.x ships unobfuscated: no mappings and no remapping step.
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val modVersion = providers.gradleProperty("modVersion").get()
val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val minecraftRange = providers.gradleProperty("minecraftRange").get()
val loaderVersion = providers.gradleProperty("loaderVersion").get()
val fabricVersion = providers.gradleProperty("fabricVersion").get()

version = modVersion
group = "dev.flipscout"

base {
    archivesName.set("flipscout-fabric-$minecraftVersion")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

sourceSets.main {
    // Shared Fabric adapter and platform-independent core; src/ only holds what differs on 26.x.
    java.srcDir("../fabric-common/src/main/java")
    // Opening a screen changed name in 26.2.
    java.srcDir(if (minecraftVersion.startsWith("26.1")) "src/screens-26.1/java" else "src/screens-26.2/java")
    resources.srcDir("../fabric-common/src/main/resources")
    java.srcDir("../core/src/main/java")
    resources.srcDir("../core/src/main/resources")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Minecraft 26.x runs on Java 25.
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftRange", minecraftRange)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_range" to minecraftRange, "java_version" to "25")
    }
}

