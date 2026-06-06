@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.anime_game_servers.multi_proto.runtime.engine

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.ProtoRuntime
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.gi.registerAllModels
import org.anime_game_servers.multi_proto.gi.registerProtoRuntime
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCsv
import kotlin.reflect.KClass

/**
 * Node.js ProtoRuntime: loads descriptors/config from the working directory via `fs`, parses the
 * `.desc` with protobuf.js, and drives the shared engine. Single-threaded, so plain maps suffice.
 */
class JsProtoRuntime : ProtoRuntime {

    private val caches = mutableMapOf<String, ProtoDescriptorRuntime>()
    private val packetMappers = mutableMapOf<String, PacketIdProvider>()

    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass.simpleName!!, model)
        } catch (ex: Throwable) {
            console.error("(descriptor-js) encodeToByteArray failed for ${modelClass.simpleName}", ex)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass.simpleName!!, byteArray) as T?
        } catch (ex: Throwable) {
            console.error("(descriptor-js) decodeFromByteArray failed for ${modelClass.simpleName}", ex)
            null
        }
    }

    private fun acquireCache(version: String): ProtoDescriptorRuntime {
        return caches.getOrPut(version) {
            val descriptor = loadDescriptorJs(fs.readFileSync("config/$version/proto.desc"))
            val instance = ProtoDescriptorRuntime(version, descriptor, JsProtoBufferFactory)
            if (fs.existsSync("config/$version/mapping.json"))
                instance.loadMapping(fs.readFileSync("config/$version/mapping.json", "utf8"))
            if (fs.existsSync("config/$version/encryption.json"))
                instance.loadEncryption(fs.readFileSync("config/$version/encryption.json", "utf8"))
            instance
        }
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        return try {
            acquireCache(version.namespace)
        } catch (ex: Throwable) {
            console.error("(descriptor-js) failed to get version $version runtime", ex)
            null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        return try {
            packetMappers.getOrPut(version.namespace) {
                packetMapperFromCsv(fs.readFileSync("config/${version.namespace}/packets.csv", "utf8"))
            }
        } catch (ex: Throwable) {
            console.error("(descriptor-js) getPacketMapper failed for $version", ex)
            null
        }
    }
}

/**
 * Wire the descriptor runtime into the gi provider. Call once at startup before using
 * Model.decodeFromByteArray / encodeToByteArray. (JS analog of the JVM ServiceLoader registration.)
 */
@kotlin.js.JsExport
fun register() {
    registerAllModels()
    registerProtoRuntime(JsProtoRuntime())
}
