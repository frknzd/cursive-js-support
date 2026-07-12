package com.cursivejssupport.types

import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.index.JsTypeRef
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * DTO describing one member of an npm export as seen by a type source. Mirrors
 * [com.cursivejssupport.parser.JsMember] but carries an optional live PSI element for
 * navigation. IntelliJ JavaScript PSI types never leak past this stable provider boundary.
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

enum class JsTypeProvenance { INTELLIJ, TYPESCRIPT_DECLARATION, BUNDLED_BROWSER, BUNDLED_GOOG, INFERRED }

data class JsCallSignature(
    val params: List<JsParam>,
    val returns: JsTypeRef,
    val constructable: Boolean = false,
    /** Members of the evaluated return type, retained so conditional/generic TS types do not
     * have to round-trip through the extension's display-string parser. */
    val returnMembers: List<JsMemberDescriptor> = emptyList(),
)

data class JsTypeDescriptor(
    val type: JsTypeRef,
    val displayName: String,
    val members: List<JsMemberDescriptor> = emptyList(),
    val callSignatures: List<JsCallSignature> = emptyList(),
    val constructSignatures: List<JsCallSignature> = emptyList(),
    val nullable: Boolean = type.leafNameds().any { it.name == "null" || it.name == "undefined" },
    val confidence: Double = 1.0,
    val provenance: JsTypeProvenance,
    val declaration: PsiElement? = null,
)

/**
 * A provider of npm-export type information. IntelliJ JavaScript evaluation is primary in 1.0;
 * the bundled `.d.ts` index remains the deterministic browser/Closure fallback.
 */
interface JsTypeSource {

    fun npmExportType(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): JsTypeDescriptor? = npmExportTypeDisplay(file, packageName, exportName)?.let {
        JsTypeDescriptor(
            type = JsTypeRef.parse(it),
            displayName = it,
            members = npmExportMembers(file, packageName, exportName, memberPath).orEmpty(),
            provenance = JsTypeProvenance.TYPESCRIPT_DECLARATION,
        )
    }

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

/** Fan-out over registered type sources. IntelliJ JavaScript support is mandatory in 1.0. */
object JsTypeSources {

    private val EP: ExtensionPointName<JsTypeSource> =
        ExtensionPointName.create("com.cursivejssupport.jsTypeSource")

    private fun sources(): List<JsTypeSource> = EP.extensionList

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

    fun npmExportType(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): JsTypeDescriptor? {
        for (source in sources()) {
            source.npmExportType(file, packageName, exportName, memberPath)?.let { return it }
        }
        return null
    }
}
