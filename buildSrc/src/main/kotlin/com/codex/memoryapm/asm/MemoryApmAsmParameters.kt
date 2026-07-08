package com.codex.memoryapm.asm

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

abstract class MemoryApmAsmParameters : InstrumentationParameters {
    @get:Input
    abstract val enabled: Property<Boolean>

    @get:Input
    abstract val includePackages: ListProperty<String>

    @get:Input
    abstract val excludePackages: ListProperty<String>

    @get:Input
    abstract val logInstrumentedClasses: Property<Boolean>
}
