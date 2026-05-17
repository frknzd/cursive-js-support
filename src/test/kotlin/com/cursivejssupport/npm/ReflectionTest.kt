package com.cursivejssupport.npm

import org.junit.Test

class ReflectionTest {
    @Test
    fun dumpClasses() {
        val classNames = listOf(
            "com.intellij.lang.javascript.modules.NodeModuleUtil",
            "com.intellij.lang.javascript.modules.NodeModuleSearchUtil",
            "com.intellij.lang.javascript.psi.resolve.JSResolveUtil",
            "com.intellij.lang.javascript.psi.JSFile",
            "com.intellij.lang.javascript.psi.ecmal4.JSClass",
            "com.intellij.javascript.nodejs.reference.NodeModuleManager"
        )
        for (name in classNames) {
            try {
                val clazz = Class.forName(name)
                println("CLASS FOUND: $name")
                for (method in clazz.methods) {
                    println("  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            } catch (e: Exception) {
                println("CLASS NOT FOUND: $name")
            }
        }
    }
}