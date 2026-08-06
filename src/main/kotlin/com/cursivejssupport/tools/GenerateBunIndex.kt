package com.cursivejssupport.tools

import java.io.File

fun main() = generateBundledEnvironmentIndex(
    sourceDir = File("src/main/resources/js/bun-types"),
    outputFile = File("src/main/resources/js/bun-symbols.json.gz"),
    environment = "bun",
    logicalPrefix = "js/bun-types/",
)
