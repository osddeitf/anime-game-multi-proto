package org.anime_game_servers.multi_proto.core.interfaces

import org.anime_game_servers.core.base.Version
import kotlin.reflect.KClass

/**
 * The proto encode/decode runtime. Implemented in the `runtime` module; the active instance is wired
 * into gi's `ProtoModelRegistry` via `ProtoModelRegistry.use(...)`. Generated models delegate to it.
 */
interface ProtoRuntime {
    fun getVersionRuntime(version: Version): ProtoVersionRuntime?
    fun getPacketMapper(version: Version): PacketIdProvider?
    fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray?
    fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T?
}

interface ProtoVersionRuntime {
    fun getObfuscatedName(name: String): String?
    fun getDeobfuscatedName(name: String): String?
}
