package org.anime_game_servers.multi_proto.runtime

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.slf4j.logger
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.utils.ProtoHandler
import org.slf4j.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.sequences.forEach

object ProtoLoader {
    private class VersionedClassLoader(version: String) : URLClassLoader(
        arrayOf(File("config/$version/proto.jar").toURI().toURL()),
    )

    var logger: KLogger? = null
    private val classLoaders = ConcurrentHashMap<String, VersionedClassLoader>()
    private val packetMappers = ConcurrentHashMap<Version, PacketIdProvider>()

    fun getProtoSet(version: Version): ReflectionCache? {
        val handler = ServiceLoader.load(ProtoHandler::class.java).firstOrNull()
        if (handler is DynamicProtoHandler) {
            return handler.acquireCache(version.namespace)
        }
        return null
    }

    fun getPacketMapper(version: Version): PacketIdProvider? {
        val resourceName = "packets.csv"
        val inputStream = getClassLoader(version.namespace).findResource(resourceName)?.openStream()
            ?: return null

        return packetMappers.computeIfAbsent(version, {
            val forward = mutableMapOf<String, Int>()
            val reverse = mutableMapOf<Int, String>()
            logger?.info { "Loading $resourceName" }

            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") } // ignore empty lines and comments
                    .forEach { line ->
                        val (key, value) = line.split(",", limit = 2).map { it.trim() }
                        value.toIntOrNull()?.let {
                            forward[key] = it
                            reverse[it] = key
                        }?: logger?.warn { "Unknown line $line" }
                    }
            }

            object : PacketIdProvider {
                override fun getPacketId(packetName: String): Int {
                    return forward[packetName] ?: 999999
                }

                override fun getPacketName(packetId: Int): String? {
                    return reverse[packetId]
                }
            }
        })
    }

    fun setCommonLogger(logger: Logger) {
        this.logger = KotlinLogging.logger(logger)
    }

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