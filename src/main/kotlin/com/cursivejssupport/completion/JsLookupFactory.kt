package com.cursivejssupport.completion

import com.cursivejssupport.parser.JsMember
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import javax.swing.Icon

internal object JsLookupFactory {
    fun global(name: String, typeText: String, insertText: String = "js/$name"): LookupElement =
        base(
            lookupString = insertText,
            extraLookupString = name,
            presentableText = name,
            typeText = typeText,
            icon = JsInteropCompletionIcons.forGlobalVariable(),
        )

    fun globalFunction(name: String): LookupElement =
        base(
            lookupString = "js/$name",
            extraLookupString = name,
            presentableText = name,
            typeText = "function",
            icon = JsInteropCompletionIcons.forGlobalFunction(),
        )

    fun jsMember(memberName: String, receiverType: String, member: JsMember, insertText: String = memberName): LookupElement =
        member(
            lookupString = insertText,
            extraLookupString = memberName,
            presentableText = memberName,
            typeText = receiverType,
            member = member,
        )

    fun dotMember(memberName: String, typeText: String?, member: JsMember): LookupElement {
        val insert = if (member.kind == "property") ".-$memberName" else ".$memberName"
        return member(
            lookupString = insert,
            extraLookupString = memberName,
            presentableText = insert,
            typeText = typeText ?: if (member.kind == "method") member.returns else member.type,
            member = member,
        )
    }

    fun npmExport(namespace: String, packageName: String, exportName: String): LookupElement {
        val icon = if (exportName == "default") {
            JsInteropCompletionIcons.forNpmDefaultExport()
        } else {
            JsInteropCompletionIcons.forNpmNamedExport()
        }
        return base(
            lookupString = "$namespace/$exportName",
            extraLookupString = exportName,
            presentableText = exportName,
            typeText = packageName,
            icon = icon,
        )
    }

    fun npmAlias(aliasName: String, packageName: String): LookupElement =
        base(
            lookupString = aliasName,
            presentableText = aliasName,
            typeText = packageName,
            icon = JsInteropCompletionIcons.forNpmNamespaceAlias(),
        )

    fun jsRoot(): LookupElement =
        base(
            lookupString = "js/",
            presentableText = "js/",
            typeText = "JavaScript",
            icon = JsInteropCompletionIcons.forJsInteropRoot(),
        )

    private fun member(
        lookupString: String,
        extraLookupString: String,
        presentableText: String,
        typeText: String?,
        member: JsMember,
    ): LookupElement {
        val sig = if (member.kind == "method") {
            member.params.joinToString(", ") { p ->
                when {
                    p.rest -> "...${p.name}: ${p.type}"
                    p.optional -> "${p.name}?: ${p.type}"
                    else -> "${p.name}: ${p.type}"
                }
            }
        } else {
            null
        }
        return base(
            lookupString = lookupString,
            extraLookupString = extraLookupString,
            presentableText = presentableText,
            typeText = typeText,
            tailText = sig?.let { "($it)" },
            icon = JsInteropCompletionIcons.forJsMemberKind(member.kind),
        )
    }

    private fun base(
        lookupString: String,
        presentableText: String,
        typeText: String?,
        icon: Icon?,
        tailText: String? = null,
        extraLookupString: String? = null,
    ): LookupElement {
        var builder = LookupElementBuilder.create(lookupString)
            .withPresentableText(presentableText)
            .withTypeText(typeText)
            .withIcon(icon)
        if (tailText != null) builder = builder.withTailText(tailText, true)
        if (!extraLookupString.isNullOrBlank() && extraLookupString != lookupString) {
            builder = builder.withLookupString(extraLookupString)
        }
        return builder
    }
}
