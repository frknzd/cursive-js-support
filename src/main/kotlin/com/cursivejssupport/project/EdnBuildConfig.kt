package com.cursivejssupport.project

import us.bpsm.edn.Keyword
import us.bpsm.edn.parser.Parsers
import java.io.File
import java.io.StringReader

internal object EdnBuildConfig {
    fun parse(file: File): Any? = runCatching {
        val parser = Parsers.newParser(Parsers.defaultConfiguration())
        parser.nextValue(Parsers.newParseable(StringReader(file.readText())))
    }.getOrNull()

    fun map(value: Any?): Map<*, *>? = value as? Map<*, *>

    fun value(map: Map<*, *>?, name: String): Any? = map?.entries?.firstOrNull {
        when (val key = it.key) {
            is Keyword -> key.name == name
            is String -> key.removePrefix(":") == name
            else -> false
        }
    }?.value

    fun text(value: Any?): String? = when (value) {
        is Keyword -> value.name
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    fun stringList(value: Any?): List<String> {
        val iter = when (value) {
            is Iterable<*> -> value.iterator()
            is Array<*> -> value.iterator()
            else -> return emptyList()
        }
        val out = mutableListOf<String>()
        while (iter.hasNext()) {
            val v = iter.next()
            val s = text(v) ?: continue
            out.add(s)
        }
        return out
    }

    fun absolute(root: File, path: String?): String? = path?.let {
        File(it).let { file -> if (file.isAbsolute) file else File(root, it) }.normalize().absolutePath
    }
}
