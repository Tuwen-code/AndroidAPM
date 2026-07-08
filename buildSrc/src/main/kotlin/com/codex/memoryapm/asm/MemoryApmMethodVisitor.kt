package com.codex.memoryapm.asm

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method

class MemoryApmMethodVisitor(
    api: Int,
    methodVisitor: MethodVisitor,
    access: Int,
    private val methodName: String,
    private val methodDescriptor: String,
    private val className: String,
) : AdviceAdapter(api, methodVisitor, access, methodName, methodDescriptor) {
    private var startTimeLocalIndex = -1

    override fun onMethodEnter() {
        startTimeLocalIndex = newLocal(Type.LONG_TYPE)
        push(className)
        push("$methodName$methodDescriptor")
        invokeStatic(TRACE_TYPE, ON_METHOD_ENTER)
        storeLocal(startTimeLocalIndex, Type.LONG_TYPE)
    }

    override fun onMethodExit(opcode: Int) {
        if (opcode == Opcodes.ATHROW) {
            return
        }
        push(className)
        push("$methodName$methodDescriptor")
        loadLocal(startTimeLocalIndex, Type.LONG_TYPE)
        invokeStatic(TRACE_TYPE, ON_METHOD_EXIT)
    }

    private companion object {
        private val TRACE_TYPE = Type.getObjectType("com/codex/memoryapm/trace/MemoryApmMethodTrace")
        private val ON_METHOD_ENTER = Method("onMethodEnter", "(Ljava/lang/String;Ljava/lang/String;)J")
        private val ON_METHOD_EXIT = Method("onMethodExit", "(Ljava/lang/String;Ljava/lang/String;J)V")
    }
}
