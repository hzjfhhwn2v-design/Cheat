plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
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
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
}

sourceSets.main {
    // Shared Fabric adapter and platform-independent core; src/ only holds what differs on 1.21.
    java.srcDir("../fabric-common/src/main/java")
    resources.srcDir("../fabric-common/src/main/resources")
    java.srcDir("../core/src/main/java")
    resources.srcDir("../core/src/main/resources")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Loom needs Gradle to run on Java 25; the mod itself targets Java 21.
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftRange", minecraftRange)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_range" to minecraftRange, "java_version" to "21")
    }
}
