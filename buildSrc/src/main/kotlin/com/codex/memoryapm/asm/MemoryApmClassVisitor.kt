package com.codex.memoryapm.asm

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class MemoryApmClassVisitor(
    api: Int,
    nextClassVisitor: ClassVisitor,
    private val className: String,
) : ClassVisitor(api, nextClassVisitor) {
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldInstrument(access, name)) {
            return methodVisitor
        }
        return MemoryApmMethodVisitor(
            api = api,
            methodVisitor = methodVisitor,
            access = access,
            methodName = name,
            methodDescriptor = descriptor,
            className = className,
        )
    }

    private fun shouldInstrument(access: Int, name: String): Boolean {
        if (name == "<init>" || name == "<clinit>") {
            return false
        }

        val ignoredAccess = Opcodes.ACC_ABSTRACT or
            Opcodes.ACC_NATIVE or
            Opcodes.ACC_BRIDGE or
            Opcodes.ACC_SYNTHETIC
        return access and ignoredAccess == 0
    }
}
