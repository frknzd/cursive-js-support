package com.cursivejssupport.tools

import java.io.File

fun main() = generateBundledEnvironmentIndex(
    sourceDir = File("src/main/resources/js/deno-types"),
    outputFile = File("src/main/resources/js/deno-symbols.json.gz"),
    environment = "deno",
    logicalPrefix = "js/deno-types/",
)
