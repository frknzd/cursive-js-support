package com.cursivejssupport.debug

import com.intellij.javascript.debugger.JavaScriptDebugAwareBase
import com.intellij.javascript.debugger.breakpoints.JavaScriptBreakpointType
import cursive.file.CljcFileType
import cursive.file.ClojureScriptFileType

class CljsJavaScriptDebugAware : JavaScriptDebugAwareBase(ClojureScriptFileType.INSTANCE) {
    override val breakpointTypeClass get() = JavaScriptBreakpointType::class.java
    override val isOnlySourceMappedBreakpoints: Boolean get() = true
}

class CljcJavaScriptDebugAware : JavaScriptDebugAwareBase(CljcFileType.INSTANCE) {
    override val breakpointTypeClass get() = JavaScriptBreakpointType::class.java
    override val isOnlySourceMappedBreakpoints: Boolean get() = true
}
