package org.anime_game_servers.multi_proto.runtime.descriptor

import io.github.oshai.kotlinlogging.KLogger
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.core.interfaces.ProtoRuntime
import org.anime_game_servers.multi_proto.core.interfaces.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.core.registry.EnumRegistration
import org.anime_game_servers.multi_proto.core.registry.ModelRegistration
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCSVStream
import org.anime_game_servers.multi_proto.runtime.engine.EngineLogger
import org.anime_game_servers.multi_proto.runtime.engine.NettyProtoBufferFactory
import org.anime_game_servers.multi_proto.runtime.engine.ProtoDescriptorRuntime
import org.anime_game_servers.multi_proto.runtime.engine.loadDescriptor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * JVM descriptor runtime. Constructed with the model/enum metadata (from gi's ProtoModelRegistry) and
 * wired via `ProtoModelRegistry.use(ProtoRuntimeImpl(getModels(), getEnums()))`. Maintains its register
 * (name -> registration) and resolves models on demand against the per-version descriptor.
 *
 * Logging is off by default; call [setLogger] (e.g. with [KLoggerEngineLogger]) to opt in.
 */
class ProtoRuntimeImpl(
    models: Collection<ModelRegistration>,
    enums: Collection<EnumRegistration>,
) : ProtoRuntime {

    private var logger: EngineLogger? = null

    private val modelMap: Map<String, ModelRegistration> = models.associateBy { it.simpleName }
    private val enumMap: Map<String, EnumRegistration> = enums.associateBy { it.simpleName }

    private val caches = ConcurrentHashMap<String, ProtoDescriptorRuntime>()
    private val packetMappers = ConcurrentHashMap<Version, PacketIdProvider>()

    /** Opt into logging (null = silent, the default). Applies to current and future version runtimes. */
    fun setLogger(logger: EngineLogger?) {
        this.logger = logger
        caches.values.forEach { it.logger = logger }
    }

    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass.simpleName!!, model)
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) ${version.namespace} - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass.simpleName!!, byteArray) as T?
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) ${version.namespace} - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    private fun acquireCache(version: String): ProtoDescriptorRuntime {
        return caches.computeIfAbsent(version) {
            val descriptor = loadDescriptor("config/$version/proto.desc")
            val instance = ProtoDescriptorRuntime(version, descriptor, NettyProtoBufferFactory, modelMap, enumMap)
            instance.logger = logger
            File("config/$version/mapping.json").takeIf { it.exists() }?.let { instance.loadMapping(it.readText()) }
            File("config/$version/encryption.json").takeIf { it.exists() }?.let { instance.loadEncryption(it.readText()) }
            instance
        }
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        return try {
            acquireCache(version.namespace)
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) failed to get version $version runtime" }
            null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        return try {
            packetMappers.computeIfAbsent(version) {
                val resource = "config/${version.namespace}/packets.csv"
                logger?.info { "Loading $resource" }
                packetMapperFromCSVStream(File(resource).inputStream())
            }
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) getPacketMapper failed for $version" }
            null
        }
    }
}

/** Ready-made [EngineLogger] backed by kotlin-logging; pass to [ProtoRuntimeImpl.setLogger]. */
class KLoggerEngineLogger(private val log: KLogger) : EngineLogger {
    override fun info(message: () -> String) = log.info(message)
    override fun warn(message: () -> String) = log.warn(message)
    override fun error(throwable: Throwable?, message: () -> String) = log.error(throwable, message)
}
