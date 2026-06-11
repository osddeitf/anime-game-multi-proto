package org.anime_game_servers.multi_proto.runtime.descriptor

import io.github.oshai.kotlinlogging.KLogger
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.core.interfaces.ProtoRuntime
import org.anime_game_servers.multi_proto.core.interfaces.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.core.registry.EnumRegistration
import org.anime_game_servers.multi_proto.core.registry.ModelRegistration
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCSVStream
import org.anime_game_servers.multi_proto.runtime.common.ConfigPaths
import org.anime_game_servers.multi_proto.runtime.common.descriptorPattern
import org.anime_game_servers.multi_proto.runtime.common.encryptionPattern
import org.anime_game_servers.multi_proto.runtime.common.mappingPattern
import org.anime_game_servers.multi_proto.runtime.common.packetsPattern
import org.anime_game_servers.multi_proto.runtime.common.resolveConfigPath
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
 *
 * Input file locations come from [configPaths] (per-file "{version}" patterns; see [ConfigPaths]).
 * Null, or any omitted field, uses the conventional config/<version>/ default.
 */
class ProtoRuntimeImpl(
    models: Collection<ModelRegistration>,
    enums: Collection<EnumRegistration>,
    private val configPaths: ConfigPaths? = null,
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
            acquireCache(version).encodeToByteArray(modelClass.simpleName!!, model)
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) ${version.namespace} - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version).decodeFromByteArray(modelClass.simpleName!!, byteArray) as T?
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) ${version.namespace} - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    // Keyed by the exact version (name), not namespace: versions can share a descriptor namespace (e.g. V3_2)
    // yet differ in which fields exist, so each needs its own version-filtered runtime. Config files are still
    // resolved by namespace (the shared descriptor), and version.id drives @AddedIn/@RemovedIn filtering.
    private fun acquireCache(version: Version): ProtoDescriptorRuntime {
        return caches.computeIfAbsent(version.name) {
            val ns = version.namespace
            logger?.debug { "(descriptor) version-aware: ${version.name} (id=${version.id}, namespace=$ns)" }
            val descriptor = loadDescriptor(resolveConfigPath(configPaths.descriptorPattern, ns))
            val instance = ProtoDescriptorRuntime(version, descriptor, NettyProtoBufferFactory, modelMap, enumMap)
            instance.logger = logger
            File(resolveConfigPath(configPaths.mappingPattern, ns)).takeIf { it.exists() }?.let { instance.loadMapping(it.readText()) }
            File(resolveConfigPath(configPaths.encryptionPattern, ns)).takeIf { it.exists() }?.let { instance.loadEncryption(it.readText()) }
            instance
        }
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        return try {
            acquireCache(version)
        } catch (ex: Exception) {
            logger?.error(ex) { "(descriptor) failed to get version $version runtime" }
            null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        return try {
            packetMappers.computeIfAbsent(version) {
                val resource = resolveConfigPath(configPaths.packetsPattern, version.namespace)
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
    override fun debug(message: () -> String) = log.debug(message)
    override fun info(message: () -> String) = log.info(message)
    override fun warn(message: () -> String) = log.warn(message)
    override fun error(throwable: Throwable?, message: () -> String) = log.error(throwable, message)
}
