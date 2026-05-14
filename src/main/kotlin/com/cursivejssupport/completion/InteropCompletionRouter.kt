package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex

internal enum class InteropCompletionRouteKind {
    None,
    JsGlobals,
    JsGlobalMembers,
    JsChainMembers,
    DotMember,
    NpmExports,
    NpmExportMembers,
    General,
}

internal data class InteropCompletionRoute(
    val kind: InteropCompletionRouteKind,
    val receiverType: String? = null,
)

internal object InteropCompletionRouter {
    fun route(intent: InteropCompletionIntent, index: JsSymbolIndex, aliases: Map<String, String>): InteropCompletionRoute =
        when (intent.kind) {
            InteropCompletionKind.None ->
                InteropCompletionRoute(InteropCompletionRouteKind.None)

            InteropCompletionKind.Js -> routeJs(intent, index)

            InteropCompletionKind.DotMember ->
                InteropCompletionRoute(InteropCompletionRouteKind.DotMember)

            InteropCompletionKind.NpmAlias -> {
                val ns = intent.namespace
                val export = intent.exportName
                val pkg = ns?.let { aliases[it] }
                if (pkg != null && !export.isNullOrBlank() && index.isKnownNpmExport(pkg, export) &&
                    (intent.hadTerminalDot || intent.npmMemberSegments.isNotEmpty() || intent.memberPrefix.isNotEmpty())) {
                    InteropCompletionRoute(
                        InteropCompletionRouteKind.NpmExportMembers,
                        receiverType = index.resolveNpmExportType(pkg, export),
                    )
                } else {
                    InteropCompletionRoute(InteropCompletionRouteKind.NpmExports)
                }
            }

            InteropCompletionKind.General ->
                InteropCompletionRoute(InteropCompletionRouteKind.General)
        }

    private fun routeJs(intent: InteropCompletionIntent, index: JsSymbolIndex): InteropCompletionRoute {
        if (intent.hadInvalidSlash) return InteropCompletionRoute(InteropCompletionRouteKind.None)
        val receiver = intent.receiverSegments
        if (receiver.isEmpty()) return InteropCompletionRoute(InteropCompletionRouteKind.JsGlobals)
        if (intent.hadTerminalDot && receiver.size == 1) {
            val type = index.resolveGlobalType(receiver[0])
            if (type != null) {
                return InteropCompletionRoute(InteropCompletionRouteKind.JsGlobalMembers, receiverType = type)
            }
        }
        if (receiver.isNotEmpty() && (intent.hadTerminalDot || intent.memberPrefix.isNotEmpty())) {
            return InteropCompletionRoute(
                InteropCompletionRouteKind.JsChainMembers,
                receiverType = index.resolveJsChainType(receiver),
            )
        }
        return InteropCompletionRoute(InteropCompletionRouteKind.JsGlobals)
    }
}
