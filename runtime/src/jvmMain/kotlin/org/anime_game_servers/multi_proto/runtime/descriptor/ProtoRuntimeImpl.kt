package org.anime_game_servers.multi_proto.runtime.descriptor

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.ProtoRuntime
import org.anime_game_servers.multi_proto.gi.ProtoRuntimeProvider
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCSVStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ProtoRuntimeImpl: ProtoRuntime {

    private val caches = ConcurrentHashMap<String, ProtoDescriptorRuntime>()
    private val packetMappers = ConcurrentHashMap<Version, PacketIdProvider>()

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass, model)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) ${version.namespace} - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T: Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass, byteArray)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) ${version.namespace} - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    internal fun acquireCache(version: String): ProtoDescriptorRuntime {
        val instance = caches.computeIfAbsent(version) {
            val descriptor = loadDescriptor("config/$version/proto.desc")
            val instance = ProtoDescriptorRuntime(version, descriptor)
            instance.loadMappingFromJson()
            instance.loadEncryptionFromJson()
            instance
        }
        instance.setLogger(ProtoRuntimeProvider.getLogger()) // always keep the logger up-to-date
        return instance
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        try {
            return acquireCache(version.namespace)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "ERROR: (descriptor) failed to get version $version runtime" }
            return null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        try {
            val resourceName = "config/${version.namespace}/packets.csv"
            val inputStream = File(resourceName).inputStream()
            return packetMappers.computeIfAbsent(version, {
                ProtoRuntimeProvider.getLogger()?.info { "Loading $resourceName" }
                packetMapperFromCSVStream(inputStream)
            })
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) getPacketMapper failed for $version" }
            return null
        }
    }
}