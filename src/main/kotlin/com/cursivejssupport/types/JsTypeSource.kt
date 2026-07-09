package com.cursivejssupport.types

import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.settings.JsSupportSettings
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * DTO describing one member of an npm export as seen by a type source. Mirrors
 * [com.cursivejssupport.parser.JsMember] but carries an optional live PSI element for
 * navigation. IntelliJ-JS-plugin types must never leak past this boundary — the JavaScript
 * plugin is an optional dependency.
 */
data class JsMemberDescriptor(
    val name: String,
    /** "method" or "property". */
    val kind: String,
    val params: List<JsParam> = emptyList(),
    val returns: String = "any",
    val type: String = "any",
    val doc: String? = null,
    /** Real declaration element (JS PSI) to navigate to; treated opaquely. */
    val navigatable: PsiElement? = null,
)

/**
 * A provider of npm-export type information. The bundled `.d.ts` index is the always-available
 * baseline; when the JavaScript plugin is installed, [com.cursivejssupport.npm.JsPluginTypeSource]
 * registers through the `com.cursivejssupport.jsTypeSource` extension point and answers with
 * IntelliJ's own type evaluation (richer for packages whose typings the hand-rolled parser
 * can't fully digest).
 */
interface JsTypeSource {

    /**
     * Members of the npm export [exportName] of [packageName], after walking [memberPath]
     * (member names hanging off the export). Null when this source can't answer — callers
     * fall through to the next source.
     */
    fun npmExportMembers(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): List<JsMemberDescriptor>?

    /** Display string of the export's type (`ComponentType<Props>`), or null when unknown. */
    fun npmExportTypeDisplay(file: PsiFile, packageName: String, exportName: String): String?
}

/** Fan-out over the registered [JsTypeSource]s, honoring the `useIntellijJsTypes` setting. */
object JsTypeSources {

    private val EP: ExtensionPointName<JsTypeSource> =
        ExtensionPointName.create("com.cursivejssupport.jsTypeSource")

    private fun sources(): List<JsTypeSource> =
        if (JsSupportSettings.getInstance().state.useIntellijJsTypes) EP.extensionList else emptyList()

    fun npmExportMembers(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): List<JsMemberDescriptor>? {
        for (source in sources()) {
            val members = source.npmExportMembers(file, packageName, exportName, memberPath)
            if (!members.isNullOrEmpty()) return members
        }
        return null
    }

    fun npmExportTypeDisplay(file: PsiFile, packageName: String, exportName: String): String? {
        for (source in sources()) {
            source.npmExportTypeDisplay(file, packageName, exportName)?.let { return it }
        }
        return null
    }
}
