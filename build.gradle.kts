import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("dev.detekt")
}

intellijPlatform {
    buildSearchableOptions = false
    // Pure-Kotlin plugin with no UI forms / Java @NotNull assertions — instrumentation adds
    // nothing and fails against the local IDE install (no Java Compiler dependency for it).
    instrumentCode = false
    pluginVerification {
        // Cursive and JavaScriptDebugger are version-locked to the target IDE build.
        // Verifying recommended future IDEs produces dependency failures, not compatibility signal.
        ides {
            current()
        }
    }
}

group = "com.cursivejssupport"
version = "1.0.0"

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

tasks.register("npmInteropAudit") {
    group = "verification"
    description = "Compare npm completion, hover, signatures, members, and navigation data with TypeScript."
    dependsOn("test")
}
