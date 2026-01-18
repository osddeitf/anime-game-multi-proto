package org.anime_game_servers.multi_proto.runtime.reflection

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.ProtoRuntime
import org.anime_game_servers.multi_proto.gi.ProtoRuntimeProvider
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.runtime.common.packetMapperFromCSVStream
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ProtoRuntimeImpl: ProtoRuntime {
    class VersionedClassLoader(version: String) : URLClassLoader(
        arrayOf(File("config/$version/proto.jar").toURI().toURL()),
    )

    private val reflectionCaches = ConcurrentHashMap<String, ReflectionRuntime>()
    private val packetMappers = ConcurrentHashMap<Version, PacketIdProvider>()

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> encodeToByteArray(version: Version, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version.namespace).encodeToByteArray(modelClass, model)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: ${version.namespace} - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T: Any> decodeFromByteArray(version: Version, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version.namespace).decodeFromByteArray(modelClass, byteArray)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "FATAL: ${version.namespace} - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    internal fun acquireCache(version: String): ReflectionRuntime {
        val cache = reflectionCaches.computeIfAbsent(version) {
            val instance = ReflectionRuntime(version)
            instance.loadMappingFromJson()
            instance.loadEncryptionFromJson()
            instance
        }
        cache.setLogger(ProtoRuntimeProvider.getLogger()) // always keep the logger up-to-date
        return cache
    }

    override fun getVersionRuntime(version: Version): ProtoVersionRuntime? {
        try {
            return acquireCache(version.namespace)
        }
        catch (ex: Exception) {
            ProtoRuntimeProvider.getLogger()?.error(ex) { "ERROR: (reflection) failed to get version $version runtime" }
            return null
        }
    }

    override fun getPacketMapper(version: Version): PacketIdProvider? {
        val resourceName = "packets.csv"
        val inputStream = getClassLoader(version.namespace).findResource(resourceName)?.openStream()
            ?: return null

        return packetMappers.computeIfAbsent(version, {
            ProtoRuntimeProvider.getLogger()?.info { "Loading $resourceName" }
            packetMapperFromCSVStream(inputStream)
        })
    }

    companion object {
        private val classLoaders = ConcurrentHashMap<String, VersionedClassLoader>()

        private fun getClassLoader(version: String) =
            classLoaders.computeIfAbsent(version) {
                VersionedClassLoader(version)
            }

        internal fun loadClass(
            version: String,
            className: String
        ): Class<*>? {
            val classLoader = getClassLoader(version);
            return try {
                classLoader.loadClass(className)
    //            classLoader.getClass(className)
            } catch (ex: ClassNotFoundException) {
                null
            }
        }
    }
}