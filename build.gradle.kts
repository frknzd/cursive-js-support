import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("dev.detekt")
}

// ---------------------------------------------------------------------------
// Target-platform matrix.
//
// Cursive is strictly version-locked to a single IDE build (Cursive 2026.1-261
// runs only on IDE 261, 2026.2-262 only on 262), so each supported IDE line is
// shipped as its own artifact with a matching Cursive dependency and a
// non-overlapping since/until range. Select a line with:
//
//   ./gradlew build                          # default: 2026.2
//   ./gradlew build -PplatformVersion=2026.1 # older line
//
// The requested IDE is reused from the local install when it matches (fast dev
// loop); otherwise Gradle downloads it. Force a path with -PlocalIdePath=...
// ---------------------------------------------------------------------------
data class PlatformSpec(
    val ideVersion: String,
    val cursiveVersion: String,
    val sinceBuild: String,
    val untilBuild: String,
)

val platformSpecs = mapOf(
    "2026.1" to PlatformSpec("2026.1", "2026.1-261", "261", "261.*"),
    "2026.2" to PlatformSpec("2026.2", "2026.2-262", "262", "262.*"),
)

val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2026.2")
val platform = platformSpecs[platformVersion]
    ?: error("Unsupported platformVersion '$platformVersion'. Choose one of ${platformSpecs.keys}.")

val localIdePath: String? = providers.gradleProperty("localIdePath").orNull
    ?: "/Applications/IntelliJ IDEA.app".takeIf { platformVersion == "2026.2" }

val pluginBaseVersion = "1.0.2"

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            current()
        }
    }
}

group = "com.cursivejssupport"
version = "$pluginBaseVersion-${platform.sinceBuild}"

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
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            create("IU", platform.ideVersion)
        }
        testFramework(TestFrameworkType.Platform)
        plugin("com.cursiveclojure.cursive", platform.cursiveVersion)
        bundledPlugin("JavaScript")
        bundledPlugin("JavaScriptDebugger")
        bundledModule("intellij.platform.scriptDebugger.ui")
    }
}

kotlin {
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("config/detekt/detekt.yml"))
    parallel = true
}

tasks {
    test {
        inputs.files(
            "test-fixtures/npm-interop-corpus/package-lock.json",
            "test-fixtures/npm-interop-corpus/reference-report.cjs",
            "test-fixtures/npm-interop-corpus/node_modules/.package-lock.json",
        )
    }

    patchPluginXml {
        sinceBuild.set(platform.sinceBuild)
        untilBuild.set(platform.untilBuild)
    }
}

tasks.register<JavaExec>("generateBrowserSymbolsIndex") {
    group = "build"
    description = "Regenerate browser-symbols.json.gz from bundled TypeScript lib .d.ts files (requires Node.js)."
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.cursivejssupport.tools.GenerateIndexKt")
    workingDir = rootDir
}

tasks.register("npmInteropAudit") {
    group = "verification"
    description = "Compare npm completion, hover, signatures, members, and navigation data with TypeScript."
    dependsOn("test")
}
