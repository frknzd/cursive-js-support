package com.cursivejssupport.documentation

import com.cursivejssupport.parser.JsMember
import com.intellij.lang.documentation.DocumentationMarkup

/**
 * Renders an [InteropDocSubject] into the HTML subset IntelliJ uses for `generateDoc`. The output
 * is split into three sections:
 *
 * - `DEFINITION_START / DEFINITION_END` — every overload signature (capped), a CLJS call-shape
 *   line, and a "declared in …" line.
 * - `CONTENT_START / CONTENT_END` — a deprecation banner (when tagged), the markdown-formatted
 *   JSDoc body, and `@example` blocks.
 * - `SECTIONS_START / … / SECTIONS_END` — a parameter table (types merged with `@param`
 *   descriptions), a return-type row, `@throws` / `@see` rows, and an "Open on MDN" link.
 *
 * Subjects that have no documentation body still get the DEFINITION block, so hovering an unknown
 * type still gives the user a useful signature.
 */
object InteropDocFormatter {

    private val mdnLinkRegex = Regex("""\[MDN Reference]\((https?://[^)\s]+)\)""", RegexOption.IGNORE_CASE)
    private const val MAX_OVERLOADS = 8
    private const val MAX_ALTERNATIVE_GROUPS = 6
    private const val MAX_SIGS_PER_GROUP = 2
    private const val MAX_MORE_NAMES = 10

    fun format(subject: InteropDocSubject): String? {
        return when (subject) {
            is InteropDocSubject.JsGlobal -> formatJsGlobal(subject)
            is InteropDocSubject.JsFunction -> formatJsFunction(subject)
            is InteropDocSubject.JsChain -> formatJsChain(subject)
            is InteropDocSubject.Member -> formatMember(subject)
            is InteropDocSubject.Constructor -> formatConstructor(subject)
            is InteropDocSubject.NpmExport -> formatNpmExport(subject)
            is InteropDocSubject.NpmAlias -> formatNpmAlias(subject)
            InteropDocSubject.Unknown -> null
        }
    }

    // ─── Variants ─────────────────────────────────────────────────────────

    private fun formatJsGlobal(subject: InteropDocSubject.JsGlobal): String {
        val definition = buildString {
            if (subject.isConstructor) {
                line("class ${escapeInline(subject.name)}")
                line(cljsLine("(js/${subject.name}. …)"))
                line("constructor")
            } else {
                line("js/${subject.name}: ${escapeInline(subject.info.type)}")
                line("global value")
            }
        }
        return assemble(
            definition = definition,
            member = null,
            doc = subject.info.doc,
        )
    }

    private fun formatJsFunction(subject: InteropDocSubject.JsFunction): String {
        val overloads = subject.overloads.ifEmpty { listOf(subject.overload) }
        val definition = buildString {
            overloadLines(overloads) { m -> "js/${renderSignature(subject.name, m)}" }
            line(cljsLine(cljsCall("js/${subject.name}", subject.overload)))
            line(if (overloads.size > 1) "global function (${overloads.size} overloads)" else "global function")
        }
        return assemble(
            definition = definition,
            member = subject.overload,
            doc = subject.overload.doc,
        )
    }

    private fun formatJsChain(subject: InteropDocSubject.JsChain): String {
        val member = subject.member
        val displayedName = subject.segments.last()
        val receiver = "js/" + subject.segments.dropLast(1).joinToString(".")
        val definition = buildString {
            overloadLines(subject.overloads.ifEmpty { listOf(member) }) { m ->
                renderMemberSignature(displayedName, m)
            }
            line(cljsLine(cljsMemberCall(displayedName, member, receiver)))
            line("declared in ${escapeInline(subject.declaringType)}")
        }
        return assemble(
            definition = definition,
            member = member,
            doc = member.doc,
        )
    }

    private fun formatMember(subject: InteropDocSubject.Member): String {
        val member = subject.member
        val receiver = subject.receiverText?.takeIf { it.length <= 20 }
            ?: receiverMnemonic(subject.declaringType)
        val definition = buildString {
            overloadLines(subject.overloads.ifEmpty { listOf(member) }) { m ->
                renderMemberSignature(subject.name, m)
            }
            line(cljsLine(cljsMemberCall(subject.name, member, receiver)))
            if (subject.alternatives.isEmpty()) {
                line("declared in ${escapeInline(subject.declaringType)}")
            } else {
                val n = subject.alternatives.size
                line(
                    "declared in ${escapeInline(subject.declaringType)} — receiver type unknown, " +
                        "+$n other candidate${if (n > 1) "s" else ""}"
                )
            }
        }
        return assemble(
            definition = definition,
            member = member,
            doc = member.doc,
            extraContent = alternativesHtml(subject),
        )
    }

    /**
     * "Also declared in" block for an ambiguous member: one line per alternative interface with
     * up to [MAX_SIGS_PER_GROUP] compact signatures, then a names-only overflow line so every
     * candidate is at least named.
     */
    private fun alternativesHtml(subject: InteropDocSubject.Member): String? {
        if (subject.alternatives.isEmpty()) return null
        return buildString {
            append("<p><b>Also declared in</b></p><p>")
            val shown = subject.alternatives.take(MAX_ALTERNATIVE_GROUPS)
            for ((i, group) in shown.withIndex()) {
                if (i > 0) append("<br/>")
                append("<code>${escapeInline(group.declaringType)}</code> — ")
                val sigs = group.overloads.take(MAX_SIGS_PER_GROUP)
                append(sigs.joinToString("; ") { m -> renderMemberSignature(subject.name, m) })
                val hidden = group.overloads.size - sigs.size
                if (hidden > 0) append(" <i>(+$hidden overload${if (hidden > 1) "s" else ""})</i>")
            }
            append("</p>")
            val overflow = subject.alternatives.drop(MAX_ALTERNATIVE_GROUPS)
            if (overflow.isNotEmpty()) {
                val names = overflow.take(MAX_MORE_NAMES).joinToString(", ") { escapeInline(it.declaringType) }
                val ellipsis = if (overflow.size > MAX_MORE_NAMES) ", …" else ""
                append("<p><i>+${overflow.size} more: $names$ellipsis</i></p>")
            }
        }
    }

    private fun formatConstructor(subject: InteropDocSubject.Constructor): String {
        val primary = subject.overloads.firstOrNull()
        val definition = buildString {
            if (subject.overloads.isEmpty()) {
                line("new ${escapeInline(subject.typeName)}()")
            } else {
                overloadLines(subject.overloads) { m ->
                    "new ${subject.typeName}(${paramList(m)}): ${escapeInline(m.returns)}"
                }
            }
            val args = primary?.params?.joinToString(" ") { cljsParamName(it.name, it.optional) }.orEmpty()
            line(cljsLine("(${subject.typeName}.${if (args.isEmpty()) "" else " $args"})"))
            line(if (subject.overloads.size > 1) "constructor (${subject.overloads.size} overloads)" else "constructor")
        }
        return assemble(
            definition = definition,
            member = primary,
            doc = subject.doc ?: primary?.doc,
        )
    }

    private fun formatNpmExport(subject: InteropDocSubject.NpmExport): String {
        val typeLine = subject.type?.takeIf { it.isNotBlank() }
        val primary = subject.overloads.firstOrNull()
        val definition = buildString {
            if (subject.overloads.isNotEmpty()) {
                overloadLines(subject.overloads) { m -> renderSignature(subject.exportName, m) }
                line(cljsLine(cljsCall(subject.exportName, subject.overloads.first())))
            } else {
                line(if (typeLine != null) "${subject.exportName}: ${escapeInline(typeLine)}" else subject.exportName)
            }
            line("export from ${escapeInline(subject.packageName)}")
        }
        return assemble(
            definition = definition,
            member = primary,
            doc = subject.doc ?: primary?.doc,
        )
    }

    private fun formatNpmAlias(subject: InteropDocSubject.NpmAlias): String {
        val definition = buildString {
            line(escapeInline(subject.alias))
            line("npm alias for ${escapeInline(subject.packageName)}")
        }
        return assemble(
            definition = definition,
            member = null,
            doc = null,
        )
    }

    // ─── CLJS call-shape line ─────────────────────────────────────────────

    /**
     * Short mnemonic for a receiver in the CLJS call line: `Document` → `doc`,
     * `HTMLElement` → `el`, unknown types → `obj`.
     */
    internal fun receiverMnemonic(declaringType: String): String = when (declaringType) {
        "Document", "XMLDocument" -> "doc"
        "Window" -> "win"
        "Element", "HTMLElement", "Node", "SVGElement" -> "el"
        "Event", "MouseEvent", "KeyboardEvent", "PointerEvent" -> "e"
        "Console" -> "js/console"
        "Range", "AbstractRange" -> "range"
        "Array" -> "arr"
        "String" -> "s"
        "Number" -> "n"
        else -> {
            // HTMLDivElement → el; CSSStyleDeclaration → obj; short lowercase-able names → initial.
            if (declaringType.startsWith("HTML") && declaringType.endsWith("Element")) "el" else "obj"
        }
    }

    /** `(.-name obj)` / `(.name obj arg1 arg2)` — the idiomatic CLJS shape of a member access. */
    internal fun cljsMemberCall(name: String, member: JsMember, receiver: String): String =
        if (member.kind == "method") {
            val args = member.params.joinToString(" ") { cljsParamName(it.name, it.optional) }
            "(.${name} $receiver${if (args.isEmpty()) "" else " $args"})"
        } else {
            "(.-$name $receiver)"
        }

    /** `(js/fetch input init?)` — the CLJS shape of a global/exported function call. */
    internal fun cljsCall(qualifiedName: String, member: JsMember): String {
        val args = member.params.joinToString(" ") { cljsParamName(it.name, it.optional) }
        return "($qualifiedName${if (args.isEmpty()) "" else " $args"})"
    }

    private fun cljsParamName(name: String, optional: Boolean): String =
        if (optional) "$name?" else name

    private fun cljsLine(call: String): String =
        """<span style="color:#808080;">${escapeInline(call)}</span>"""

    // ─── Assembly ──────────────────────────────────────────────────────────

    private fun StringBuilder.overloadLines(overloads: List<JsMember>, render: (JsMember) -> String) {
        val shown = overloads.take(MAX_OVERLOADS)
        for ((i, m) in shown.withIndex()) {
            line(if (i == 0) "<b>${render(m)}</b>" else render(m))
        }
        val hidden = overloads.size - shown.size
        if (hidden > 0) line("… +$hidden more overload${if (hidden > 1) "s" else ""}")
    }

    private fun assemble(
        definition: String,
        member: JsMember?,
        doc: String?,
        extraContent: String? = null,
    ): String = buildString {
        val parsed = JsDocTags.parse(doc)

        append(DocumentationMarkup.DEFINITION_START)
        append(definition.trim().replace("\n", "<br/>"))
        append(DocumentationMarkup.DEFINITION_END)

        val (bodyHtml, mdnUrl) = renderContent(parsed.body)
        val exampleHtml = parsed.examples.joinToString("") { ex ->
            "<pre><code>${JsDocHtmlFormatter.escapeHtml(stripFences(ex))}</code></pre>"
        }
        val deprecationHtml = parsed.deprecated?.let { reason ->
            val suffix = JsDocHtmlFormatter.format(reason)?.let { " $it" }.orEmpty()
            "<p><b>Deprecated.</b>$suffix</p>"
        }.orEmpty()
        val contentHtml = deprecationHtml + (bodyHtml ?: "") + exampleHtml + extraContent.orEmpty()
        if (contentHtml.isNotBlank()) {
            append(DocumentationMarkup.CONTENT_START)
            append(contentHtml)
            append(DocumentationMarkup.CONTENT_END)
        }

        val sectionRows = ArrayList<Pair<String, String>>()
        sectionRows.addAll(paramRows(member, parsed.params))
        returnsRowFor(member, parsed.returns)?.let { sectionRows.add(it) }
        for (t in parsed.throws) {
            sectionRows.add("Throws" to (JsDocHtmlFormatter.format(t) ?: escapeInline(t)))
        }
        for (s in parsed.sees) {
            sectionRows.add("See" to (JsDocHtmlFormatter.format(s) ?: escapeInline(s)))
        }
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

    private fun stripFences(example: String): String =
        example.lines()
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n")
            .trim()

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

    /** Parameter rows: declared type, with the `@param` description appended when present. */
    private fun paramRows(member: JsMember?, paramDocs: Map<String, String>): List<Pair<String, String>> {
        if (member == null || member.kind != "method") return emptyList()
        return member.params.map { p ->
            val label = buildString {
                append(escapeInline(p.name))
                if (p.optional) append('?')
                if (p.rest) insert(0, "...")
            }
            val desc = paramDocs[p.name]?.takeIf { it.isNotBlank() }
                ?.let { JsDocHtmlFormatter.format(it) }
            val value = if (desc != null) "${escapeInline(p.type)} — $desc" else escapeInline(p.type)
            label to value
        }
    }

    private fun returnsRowFor(member: JsMember?, returnsDoc: String?): Pair<String, String>? {
        if (member == null || member.kind != "method") return null
        val desc = returnsDoc?.takeIf { it.isNotBlank() }?.let { JsDocHtmlFormatter.format(it) }
        val value = if (desc != null) "${escapeInline(member.returns)} — $desc" else escapeInline(member.returns)
        return "Returns" to value
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun StringBuilder.line(s: String) {
        if (isNotEmpty()) append('\n')
        append(s)
    }

    private fun escapeInline(text: String): String = JsDocHtmlFormatter.escapeHtml(text)
}
