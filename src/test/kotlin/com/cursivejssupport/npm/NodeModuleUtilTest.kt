package com.cursivejssupport.npm

import com.intellij.lang.javascript.modules.NodeModuleUtil
import java.lang.reflect.Modifier

fun main() {
    val clazz = NodeModuleUtil::class.java
    for (method in clazz.methods) {
        println(method.name)
    }
}