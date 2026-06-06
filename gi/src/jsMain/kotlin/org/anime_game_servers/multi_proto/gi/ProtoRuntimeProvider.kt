package org.anime_game_servers.multi_proto.gi

// JS analog of the JVM ServiceLoader-based provider. There is no ServiceLoader on JS, so the
// concrete ProtoRuntime is supplied explicitly via registerProtoRuntime(...) (called by the
// runtime module's exported register() entrypoint). The service getter fails loudly if used
// before registration rather than returning a half-initialized runtime.
private var registered: ProtoRuntime? = null

actual object ProtoRuntimeProvider {
    actual val service: ProtoRuntime
        get() = registered
            ?: error("ProtoRuntime is not registered. Import the runtime package and call register() once at startup.")
}

fun registerProtoRuntime(runtime: ProtoRuntime) {
    registered = runtime
}
