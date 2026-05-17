package com.cursivejssupport.npm

import org.junit.Test

class ReflectionTest2 {
    @Test
    fun dumpMoreClasses() {
        val classNames = listOf(
            "com.intellij.javascript.nodejs.reference.NodeModuleManager",
            "com.intellij.javascript.nodejs.reference.NodePackage",
            "com.intellij.lang.javascript.psi.resolve.JSResolveUtil"
        )
        for (name in classNames) {
            try {
                val clazz = Class.forName(name)
                println("CLASS FOUND: $name")
                for (method in clazz.methods) {
                    println("  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }}) -> ${method.returnType.simpleName}")
                }
            } catch (e: Exception) {
                println("CLASS NOT FOUND: $name")
            }
        }
    }
}