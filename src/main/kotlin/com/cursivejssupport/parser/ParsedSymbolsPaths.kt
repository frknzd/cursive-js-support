package com.cursivejssupport.parser

import com.cursivejssupport.index.BundledDomLibs
import java.io.File

/**
 * Rewrite every [JsLocation.filePath] to a logical bundled-resource path so the index is stable
 * across machines (absolute paths differ between the generator host and plugin users).
 *
 * [prefix] is the logical directory under `src/main/resources/js/` that the source `.d.ts` files
 * live in (e.g. `js/lib/` for the browser libs, `js/node-types/` for `@types/node`). Only the file
 * basename is kept, matching how [BundledDomLibs.resolveVirtualFile] locates them at runtime.
 *
 * Recurses into [ParsedSymbols.modules] so ambient external modules (`declare module "fs" { … }`)
 * get the same path rewriting for their scoped interfaces/variables/functions.
 */
fun ParsedSymbols.withLogicalBundledLibPaths(prefix: String = BundledDomLibs.LOGICAL_LIB_PREFIX): ParsedSymbols =
    mapLocations(prefix)

private fun ParsedSymbols.mapLocations(prefix: String): ParsedSymbols {
    fun mapLoc(loc: JsLocation?): JsLocation? {
        if (loc == null) return null
        val p = loc.filePath.replace('\\', '/')
        val name = File(p).name
        return loc.copy(filePath = "$prefix$name")
    }

    fun mapMember(m: JsMember): JsMember = m.copy(location = mapLoc(m.location))

    val ifaces = interfaces.mapValues { (_, iface) ->
        iface.copy(
            location = mapLoc(iface.location),
            members = iface.members.mapValues { (_, overloads) ->
                overloads.map { mapMember(it) }
            }
        )
    }

    val vars = variables.mapValues { (_, v) ->
        v.copy(location = mapLoc(v.location))
    }

    val funcs = functions.mapValues { (_, overloads) ->
        overloads.map { mapMember(it) }
    }

    val mods = if (modules.isEmpty()) modules else modules.mapValues { (_, mod) -> mod.mapLocations(prefix) }

    return copy(interfaces = ifaces, variables = vars, functions = funcs, modules = mods)
}
