package org.anime_game_servers.multi_proto.runtime.descriptor

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.ProtoRuntime
import org.anime_game_servers.multi_proto.gi.ProtoRuntimeProvider
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.gi.registerAllModels
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCSVStream
import org.anime_game_servers.multi_proto.runtime.engine.NettyProtoBufferFactory
import org.anime_game_servers.multi_proto.runtime.engine.ProtoDescriptorRuntime
import org.anime_game_servers.multi_proto.runtime.engine.loadDescriptor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ProtoRuntimeImpl : ProtoRuntime {

    init {
        // Populate the compile-time model registry (replaces reflection-based discovery).
        registerAllModels()
    }

    private val caches = ConcurrentHashMap<String, ProtoDescriptorRuntime>()
    private val packetMappers = ConcurrentHashMap<Version, PacketIdProvider>()

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass.simpleName!!, model)
        } catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) ${version.namespace} - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass.simpleName!!, byteArray) as T?
        } catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) ${version.namespace} - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    internal fun acquireCache(version: String): ProtoDescriptorRuntime {
        return caches.computeIfAbsent(version) {
            val descriptor = loadDescriptor("config/$version/proto.desc")
            val instance = ProtoDescriptorRuntime(version, descriptor, NettyProtoBufferFactory)
            File("config/$version/mapping.json").takeIf { it.exists() }?.let { instance.loadMapping(it.readText()) }
            File("config/$version/encryption.json").takeIf { it.exists() }?.let { instance.loadEncryption(it.readText()) }
            instance
        }
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        return try {
            acquireCache(version.namespace)
        } catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "ERROR: (descriptor) failed to get version $version runtime" }
            null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        return try {
            val resourceName = "config/${version.namespace}/packets.csv"
            packetMappers.computeIfAbsent(version) {
                ProtoRuntimeProvider.getLogger()?.info { "Loading $resourceName" }
                packetMapperFromCSVStream(File(resourceName).inputStream())
            }
        } catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: (descriptor) getPacketMapper failed for $version" }
            null
        }
    }
}
