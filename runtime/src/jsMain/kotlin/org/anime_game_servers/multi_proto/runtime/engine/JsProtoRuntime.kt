@file:OptIn(kotlin.js.ExperimentalJsExport::class)
@file:Suppress("NON_EXPORTABLE_TYPE")

package org.anime_game_servers.multi_proto.runtime.engine

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.core.interfaces.ProtoRuntime
import org.anime_game_servers.multi_proto.core.interfaces.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.core.registry.EnumRegistration
import org.anime_game_servers.multi_proto.core.registry.ModelRegistration
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCsv
import org.anime_game_servers.multi_proto.runtime.common.ConfigPaths
import org.anime_game_servers.multi_proto.runtime.common.descriptorPattern
import org.anime_game_servers.multi_proto.runtime.common.encryptionPattern
import org.anime_game_servers.multi_proto.runtime.common.mappingPattern
import org.anime_game_servers.multi_proto.runtime.common.packetsPattern
import org.anime_game_servers.multi_proto.runtime.common.resolveConfigPath
import org.khronos.webgl.Uint8Array
import kotlin.js.JsExport
import kotlin.reflect.KClass

/**
 * Node.js ProtoRuntime: loads descriptors/config from the working directory via `fs`, parses the
 * `.desc` with protobuf.js, and drives the shared engine. Constructed with gi's model/enum metadata and
 * wired via `ProtoModelRegistry.use(JsProtoRuntime(getModels(), getEnums()))`. Single-threaded.
 *
 * Logging is off by default; call [setLogger] (e.g. with [ConsoleEngineLogger]) to opt in.
 *
 * Input file locations come from [configPaths] (per-file "{version}" patterns; see [ConfigPaths]).
 * Null, or any omitted field, uses the conventional config/<version>/ default.
 */
@JsExport
class JsProtoRuntime(
    models: Collection<ModelRegistration>,
    enums: Collection<EnumRegistration>,
    private val configPaths: ConfigPaths? = null,
) : ProtoRuntime {

    private var logger: EngineLogger? = null

    private val modelMap: Map<String, ModelRegistration> = models.associateBy { it.simpleName }
    private val enumMap: Map<String, EnumRegistration> = enums.associateBy { it.simpleName }

    private val caches = mutableMapOf<String, ProtoDescriptorRuntime>()
    private val packetMappers = mutableMapOf<String, PacketIdProvider>()

    /** Opt into logging (null = silent, the default). Applies to current and future version runtimes. */
    fun setLogger(logger: EngineLogger?) {
        this.logger = logger
        caches.values.forEach { it.logger = logger }
    }

    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass.simpleName!!, model)
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass.simpleName!!, byteArray) as T?
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    /**
     * Plain-object API: decode wire [bytes] for model [simpleName] (under version namespace [version]) into a
     * PLAIN JS OBJECT — used with the plain-data registry (no Kotlin model class). [bytes] is a Uint8Array.
     */
    fun decodeModel(version: String, simpleName: String, bytes: Uint8Array): Any? {
        return try {
            acquireCache(version).decodeFromByteArray(simpleName, uint8ToByteArray(bytes))
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) decodeModel failed for $simpleName" }
            null
        }
    }

    /** Plain-object API: encode a PLAIN JS OBJECT [model] for [simpleName] to a Uint8Array. */
    fun encodeModel(version: String, simpleName: String, model: Any): Uint8Array? {
        return try {
            byteArrayToUint8(acquireCache(version).encodeToByteArray(simpleName, model))
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) encodeModel failed for $simpleName" }
            null
        }
    }

    private fun acquireCache(version: String): ProtoDescriptorRuntime {
        return caches.getOrPut(version) {
            val descriptor = loadDescriptorJs(fs.readFileSync(resolveConfigPath(configPaths.descriptorPattern, version)))
            val instance = ProtoDescriptorRuntime(version, descriptor, JsProtoBufferFactory, modelMap, enumMap)
            instance.logger = logger
            val mapping = resolveConfigPath(configPaths.mappingPattern, version)
            if (fs.existsSync(mapping)) instance.loadMapping(fs.readFileSync(mapping, "utf8"))
            val encryption = resolveConfigPath(configPaths.encryptionPattern, version)
            if (fs.existsSync(encryption)) instance.loadEncryption(fs.readFileSync(encryption, "utf8"))
            instance
        }
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        return try {
            acquireCache(version.namespace)
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) failed to get version $version runtime" }
            null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        return try {
            packetMappers.getOrPut(version.namespace) {
                packetMapperFromCsv(fs.readFileSync(resolveConfigPath(configPaths.packetsPattern, version.namespace), "utf8"))
            }
        } catch (ex: Throwable) {
            logger?.error(ex) { "(descriptor-js) getPacketMapper failed for $version" }
            null
        }
    }
}

/** Ready-made [EngineLogger] that writes to the JS console; pass to [JsProtoRuntime.setLogger]. */
@JsExport
object ConsoleEngineLogger : EngineLogger {
    override fun info(message: () -> String) { console.info(message()) }
    override fun warn(message: () -> String) { console.warn(message()) }
    override fun error(throwable: Throwable?, message: () -> String) { console.error(message(), throwable) }
}
