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
version = "0.6.1"

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
        // No upper bound: keep the plugin available on all future IDE builds.
        untilBuild.unset()
    }
}

tasks.register<JavaExec>("generateBrowserSymbolsIndex") {
    group = "build"
    description = "Regenerate browser-symbols.json.gz from bundled TypeScript lib .d.ts files (requires Node.js)."
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.cursivejssupport.tools.GenerateIndexKt")
    workingDir = rootDir
}
