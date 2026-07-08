# Public SDK APIs are referenced directly by the host app. Add keep rules here
# only if future reflection-based integrations are introduced.

-keep class com.codex.memoryapm.trace.MemoryApmMethodTrace {
    public static *;
}
