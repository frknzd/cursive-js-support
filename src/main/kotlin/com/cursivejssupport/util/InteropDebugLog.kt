package com.cursivejssupport.util

import com.cursivejssupport.debug.InteropLogView
import com.intellij.openapi.diagnostic.Logger

/**
 * Interop diagnostics: lines are appended to the **JS Interop Log** tool window
 * (**View | Tool Windows | JsInteropLog**) so they are visible without log configuration.
 * Also written to the IntelliJ logger category [CATEGORY] (see `idea.log` if needed).
 */
object InteropDebugLog {
    const val CATEGORY: String = "com.cursivejssupport.interop"

    @JvmField
    val log: Logger = Logger.getInstance(CATEGORY)

    fun info(message: String) {
        InteropLogView.appendLine(message)
        log.info(message)
    }

    fun warn(message: String) {
        InteropLogView.appendLine("[WARN] $message")
        log.warn(message)
    }

    fun debug(message: String) {
        InteropLogView.appendLine(message)
        if (log.isDebugEnabled) {
            log.debug(message)
        }
    }
}
