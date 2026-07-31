import org.jetbrains.changelog.Changelog
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

// An explicit -PlocalIdePath is honoured as-is (a wrong path should fail loudly); the convenience
// default only applies when that IDE is actually installed, so CI runners fall through to the
// downloaded distribution instead of failing on a macOS-only path.
val defaultLocalIdePath = "/Applications/IntelliJ IDEA.app"
    .takeIf { platformVersion == "2026.2" && file(it).exists() }
val localIdePath: String? = providers.gradleProperty("localIdePath").orNull ?: defaultLocalIdePath

// Single source of truth for the released version — `pluginVersion` in gradle.properties. The
// published artifact appends the IDE line (e.g. 1.3.1-262) so both lines can coexist on the
// Marketplace; `project.version` is never set from gradle.properties directly.
val pluginBaseVersion = providers.gradleProperty("pluginVersion").get()

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            current()
        }
    }

    // Marketplace requires signed uploads. `signPlugin` runs automatically before `publishPlugin`
    // once these are present; locally they are absent and both tasks stay out of the way.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release qualifier picks the matching channel: 1.4.0-beta.1 -> "beta", 1.3.1 ->
        // the default (stable) channel. The IDE-line suffix lives in `version`, not here, so it
        // never leaks into this decision.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

val changelogFile = layout.projectDirectory.file("CHANGELOG.md")

changelog {
    // Without this the header would carry the IDE-line suffix (1.3.1-262) that `version` appends.
    version = pluginBaseVersion
    path = changelogFile.asFile.path
    groups = listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")
    repositoryUrl = "https://github.com/frknzd/cursive-js-support"
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
        // The Marketplace "What's New" section comes straight from CHANGELOG.md — the section for
        // the version being released, falling back to [Unreleased] for local/dev builds.
        //
        // Rendered eagerly rather than through a Provider: both the changelog extension and the
        // `Changelog` it builds hold a Project reference (via `sectionUrlBuilder`), so neither can
        // be stored in the configuration cache. Reading the file through `providers.fileContents`
        // registers it as a configuration input, so editing CHANGELOG.md re-runs this.
        changeNotes = if (!providers.fileContents(changelogFile).asText.isPresent) "" else {
            val log = changelog.instance.get()
            (log.runCatching { get(pluginBaseVersion) }.getOrNull() ?: log.unreleasedItem)
                ?.let { log.renderItem(it.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML) }
                .orEmpty()
        }
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
