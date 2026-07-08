plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle-api:8.13.2")
    implementation("org.ow2.asm:asm-commons:9.8")
}

gradlePlugin {
    plugins {
        create("memoryApmAsm") {
            id = "com.codex.memoryapm.asm"
            implementationClass = "com.codex.memoryapm.asm.MemoryApmAsmPlugin"
        }
    }
}
