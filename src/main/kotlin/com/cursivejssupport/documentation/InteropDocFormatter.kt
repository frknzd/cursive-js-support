package com.cursivejssupport.documentation

import com.cursivejssupport.parser.JsMember
import com.intellij.lang.documentation.DocumentationMarkup

/**
 * Renders an [InteropDocSubject] into the HTML subset IntelliJ uses for `generateDoc`. The output
 * is split into three sections:
 *
 * - `DEFINITION_START / DEFINITION_END` — one signature line + one "declared in …" line.
 * - `CONTENT_START / CONTENT_END` — the markdown-formatted JSDoc body.
 * - `SECTIONS_START / SECTION_HEADER_START / SECTION_SEPARATOR / SECTIONS_END` — a parameter
 *   table, a return-type row, and an "Open on MDN" link extracted from the markdown body.
 *
 * Subjects that have no documentation body still get the DEFINITION block, so hovering an unknown
 * type still gives the user a useful signature.
 */
object InteropDocFormatter {

    private val mdnLinkRegex = Regex("""\[MDN Reference]\((https?://[^)\s]+)\)""", RegexOption.IGNORE_CASE)

    fun format(subject: InteropDocSubject): String? {
        return when (subject) {
            is InteropDocSubject.JsGlobal -> formatJsGlobal(subject)
            is InteropDocSubject.JsFunction -> formatJsFunction(subject)
            is InteropDocSubject.JsChain -> formatJsChain(subject)
            is InteropDocSubject.Member -> formatMember(subject)
            is InteropDocSubject.NpmExport -> formatNpmExport(subject)
            is InteropDocSubject.NpmAlias -> formatNpmAlias(subject)
            InteropDocSubject.Unknown -> null
        }
    }

    // ─── Variants ─────────────────────────────────────────────────────────

    private fun formatJsGlobal(subject: InteropDocSubject.JsGlobal): String {
        val definition = buildString {
            line("js/${subject.name}: ${subject.info.type}")
            line("global value")
        }
        return assemble(
            definition = definition,
            doc = subject.info.doc,
            paramRows = emptyList(),
            returnsRow = null,
        )
    }

    private fun formatJsFunction(subject: InteropDocSubject.JsFunction): String {
        val member = subject.overload
        val sig = renderSignature(subject.name, member)
        val definition = buildString {
            line("js/$sig")
            line(if (subject.overloadCount > 1) "global function (${subject.overloadCount} overloads)" else "global function")
        }
        return assemble(
            definition = definition,
            doc = member.doc,
            paramRows = paramRows(member),
            returnsRow = "Returns" to escapeInline(member.returns),
        )
    }

    private fun formatJsChain(subject: InteropDocSubject.JsChain): String {
        val member = subject.member
        val displayedName = subject.segments.last()
        val sig = renderMemberSignature(displayedName, member)
        val definition = buildString {
            line(sig)
            line("declared in ${escapeInline(subject.declaringType)}")
        }
        return assemble(
            definition = definition,
            doc = member.doc,
            paramRows = paramRows(member),
            returnsRow = returnsRowFor(member),
        )
    }

    private fun formatMember(subject: InteropDocSubject.Member): String {
        val member = subject.member
        val sig = renderMemberSignature(subject.name, member)
        val definition = buildString {
            line(sig)
            line("declared in ${escapeInline(subject.declaringType)}")
        }
        return assemble(
            definition = definition,
            doc = member.doc,
            paramRows = paramRows(member),
            returnsRow = returnsRowFor(member),
        )
    }

    private fun formatNpmExport(subject: InteropDocSubject.NpmExport): String {
        val typeLine = subject.type?.takeIf { it.isNotBlank() }
        val definition = buildString {
            line(if (typeLine != null) "${subject.exportName}: ${escapeInline(typeLine)}" else subject.exportName)
            line("export from ${escapeInline(subject.packageName)}")
        }
        return assemble(
            definition = definition,
            doc = null,
            paramRows = emptyList(),
            returnsRow = null,
        )
    }

    private fun formatNpmAlias(subject: InteropDocSubject.NpmAlias): String {
        val definition = buildString {
            line(escapeInline(subject.alias))
            line("npm alias for ${escapeInline(subject.packageName)}")
        }
        return assemble(
            definition = definition,
            doc = null,
            paramRows = emptyList(),
            returnsRow = null,
        )
    }

    // ─── Assembly ──────────────────────────────────────────────────────────

    private fun assemble(
        definition: String,
        doc: String?,
        paramRows: List<Pair<String, String>>,
        returnsRow: Pair<String, String>?,
    ): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(definition.trim().replace("\n", "<br/>"))
        append(DocumentationMarkup.DEFINITION_END)

        val (contentHtml, mdnUrl) = renderContent(doc)
        if (!contentHtml.isNullOrBlank()) {
            append(DocumentationMarkup.CONTENT_START)
            append(contentHtml)
            append(DocumentationMarkup.CONTENT_END)
        }

        val sectionRows = ArrayList<Pair<String, String>>(paramRows.size + 2)
        sectionRows.addAll(paramRows)
        if (returnsRow != null) sectionRows.add(returnsRow)
        if (!mdnUrl.isNullOrBlank()) {
            sectionRows.add("MDN" to """<a href="$mdnUrl">Open on MDN</a>""")
        }
        if (sectionRows.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            for ((header, value) in sectionRows) {
                append(DocumentationMarkup.SECTION_HEADER_START)
                append(escapeInline(header))
                append(DocumentationMarkup.SECTION_SEPARATOR)
                append(value)
                append(DocumentationMarkup.SECTION_END)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private fun renderContent(rawDoc: String?): Pair<String?, String?> {
        if (rawDoc.isNullOrBlank()) return null to null
        // Pull the MDN URL out of the raw markdown before format() escapes the brackets — we'd
        // rather show it as a dedicated section row than have it disappear into the body.
        val mdnMatch = mdnLinkRegex.find(rawDoc)
        val mdnUrl = mdnMatch?.groupValues?.getOrNull(1)
        val body = if (mdnMatch != null) {
            rawDoc.replace(mdnMatch.value, "")
        } else rawDoc
        val html = JsDocHtmlFormatter.format(body)
        return html to mdnUrl
    }

    // ─── Signature rendering ──────────────────────────────────────────────

    private fun renderSignature(name: String, member: JsMember): String {
        val params = paramList(member)
        return "$name($params): ${escapeInline(member.returns)}"
    }

    private fun renderMemberSignature(name: String, member: JsMember): String =
        when (member.kind) {
            "method" -> renderSignature(name, member)
            else -> "$name: ${escapeInline(member.type)}"
        }

    private fun paramList(member: JsMember): String =
        member.params.joinToString(", ") { p ->
            val type = escapeInline(p.type)
            when {
                p.rest -> "...${escapeInline(p.name)}: $type"
                p.optional -> "${escapeInline(p.name)}?: $type"
                else -> "${escapeInline(p.name)}: $type"
            }
        }

    private fun paramRows(member: JsMember): List<Pair<String, String>> =
        member.params.map { p ->
            val label = buildString {
                append(escapeInline(p.name))
                if (p.optional) append('?')
                if (p.rest) insert(0, "...")
            }
            label to escapeInline(p.type)
        }

    private fun returnsRowFor(member: JsMember): Pair<String, String>? =
        if (member.kind == "method") "Returns" to escapeInline(member.returns) else null

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun StringBuilder.line(s: String) {
        if (isNotEmpty()) append('\n')
        append(s)
    }

    private fun escapeInline(text: String): String = JsDocHtmlFormatter.escapeHtml(text)
}
