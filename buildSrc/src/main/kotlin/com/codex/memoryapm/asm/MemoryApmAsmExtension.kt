package com.codex.memoryapm.asm

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class MemoryApmAsmExtension @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val includePackages: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val excludePackages: ListProperty<String> = objects.listProperty(String::class.java).convention(
        listOf(
            "android/",
            "androidx/",
            "kotlin/",
            "kotlinx/",
            "org/intellij/",
            "org/jetbrains/",
            "com/codex/memoryapm/trace/",
        ),
    )
    val logInstrumentedClasses: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
