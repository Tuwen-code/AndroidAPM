package com.codex.memoryapm.asm

internal fun String.toInternalName(): String {
    return replace('.', '/')
}

internal fun String.startsWithPackage(packageName: String): Boolean {
    val prefix = packageName.toInternalName().trimEnd('/') + "/"
    return this == prefix.trimEnd('/') || startsWith(prefix)
}
