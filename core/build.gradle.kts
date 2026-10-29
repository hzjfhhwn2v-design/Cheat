plugins {
    java
}

group = "dev.flipscout"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Gson comes with Minecraft (2.2.4 on 1.8.9, newer on modern versions).
    // Compiling against the oldest one keeps the core to APIs present everywhere.
    compileOnly("com.google.code.gson:gson:2.2.4")
    testImplementation("com.google.code.gson:gson:2.2.4")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // The Forge 1.8.9 build compiles these sources with Java 8.
    options.release.set(8)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
