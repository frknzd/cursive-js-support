package com.cursivejssupport.parser

import com.cursivejssupport.index.BundledDomLibs
import java.io.File

fun ParsedSymbols.withLogicalBundledLibPaths(): ParsedSymbols {
    fun mapLoc(loc: JsLocation?): JsLocation? {
        if (loc == null) return null
        val p = loc.filePath.replace('\\', '/')
        val name = File(p).name
        return loc.copy(filePath = "${BundledDomLibs.LOGICAL_LIB_PREFIX}$name")
    }

    fun mapMember(m: JsMember): JsMember = m.copy(location = mapLoc(m.location))

    val ifaces = interfaces.mapValues { (_, iface) ->
        JsInterface(
            location = mapLoc(iface.location),
            extends = iface.extends,
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

    return ParsedSymbols(interfaces = ifaces, variables = vars, functions = funcs)
}
