package com.codex.memoryapm.asm

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class MemoryApmAsmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("memoryApmAsm", MemoryApmAsmExtension::class.java)

        project.pluginManager.withPlugin("com.android.application") {
            configureInstrumentation(project, extension)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureInstrumentation(project, extension)
        }
    }

    private fun configureInstrumentation(project: Project, extension: MemoryApmAsmExtension) {
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                MemoryApmAsmClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT,
            ) { parameters ->
                parameters.enabled.set(extension.enabled)
                parameters.includePackages.set(extension.includePackages)
                parameters.excludePackages.set(extension.excludePackages)
                parameters.logInstrumentedClasses.set(extension.logInstrumentedClasses)
            }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
            )
        }
    }
}
