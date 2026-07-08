package com.codex.memoryapm.asm

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

abstract class MemoryApmAsmClassVisitorFactory : AsmClassVisitorFactory<MemoryApmAsmParameters> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className.toInternalName()
        if (parameters.get().logInstrumentedClasses.get()) {
            println("MemoryApmAsm instrumenting $className")
        }
        return MemoryApmClassVisitor(
            api = instrumentationContext.apiVersion.get(),
            nextClassVisitor = nextClassVisitor,
            className = className,
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val params = parameters.get()
        if (!params.enabled.get()) {
            return false
        }

        val className = classData.className.toInternalName()
        return className.isIncluded(params.includePackages.get()) &&
            !className.isExcluded(params.excludePackages.get())
    }

    private fun String.isIncluded(includePackages: List<String>): Boolean {
        if (includePackages.isEmpty()) {
            return true
        }
        return includePackages.any { startsWithPackage(it) }
    }

    private fun String.isExcluded(excludePackages: List<String>): Boolean {
        return excludePackages.any { startsWithPackage(it) }
    }
}
