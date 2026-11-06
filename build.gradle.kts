plugins {
    java
}

group = "one.zovo"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Java 21 bytecode runs on Paper 1.21.x (Java 21) and Paper 26.x (Java 25).
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    // The S3 integration test runs only when the run script starts a local S3 server.
    environment("BB_IT_ENDPOINT", System.getenv("BB_IT_ENDPOINT") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

tasks.jar {
    archiveBaseName.set("BucketBackup")
    from("LICENSE")
}
