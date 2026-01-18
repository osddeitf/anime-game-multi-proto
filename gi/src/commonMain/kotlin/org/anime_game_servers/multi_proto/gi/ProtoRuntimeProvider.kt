package org.anime_game_servers.multi_proto.gi

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import kotlin.reflect.KClass

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

// commonMain
expect object ProtoRuntimeProvider {
    val service: ProtoRuntime
}
