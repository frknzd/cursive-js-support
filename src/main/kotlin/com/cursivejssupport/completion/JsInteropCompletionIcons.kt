package com.cursivejssupport.completion

import com.intellij.icons.AllIcons

/** Icons for JS/browser and npm completion rows. */
object JsInteropCompletionIcons {

    fun forJsMemberKind(kind: String) = when (kind) {
        "method" -> AllIcons.Nodes.Method
        "property" -> AllIcons.Nodes.Field
        else -> AllIcons.Nodes.Class
    }

    fun forGlobalVariable() = AllIcons.Nodes.Variable

    fun forGlobalFunction() = AllIcons.Nodes.Function

    fun forNpmDefaultExport() = AllIcons.Nodes.Class

    fun forNpmNamedExport() = AllIcons.Nodes.Function

    fun forNpmNamespaceAlias() = AllIcons.Nodes.Module

    /** `js/` root (global JS namespace in CLJS). */
    fun forJsInteropRoot() = AllIcons.Nodes.Package
}
