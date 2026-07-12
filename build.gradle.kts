import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

intellijPlatform {
    buildSearchableOptions = false
    // Pure-Kotlin plugin with no UI forms / Java @NotNull assertions — instrumentation adds
    // nothing and fails against the local IDE install (no Java Compiler dependency for it).
    instrumentCode = false
}

group = "com.cursivejssupport"
version = "0.6.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.jackson.kotlin)
    implementation(libs.edn.java)

    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app")
        testFramework(TestFrameworkType.Platform)
        plugin("com.cursiveclojure.cursive", "2026.1-261")
        bundledPlugin("JavaScript")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("261")
        // Cursive is mandatory, so do not advertise IDE builds unsupported by the
        // stable Cursive dependency. Publish a separate 262 build once Cursive 262
        // is available on the same Marketplace channel.
        untilBuild.set("261.*")
    }
}

tasks.register<JavaExec>("generateBrowserSymbolsIndex") {
    group = "build"
    description = "Regenerate browser-symbols.json.gz from bundled TypeScript lib .d.ts files (requires Node.js)."
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.cursivejssupport.tools.GenerateIndexKt")
    workingDir = rootDir
}
