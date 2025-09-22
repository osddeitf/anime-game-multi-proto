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

    fun getProtoSet(version: Version): ReflectionCache? {
        val handler = ServiceLoader.load(ProtoHandler::class.java).firstOrNull()
        if (handler is DynamicProtoHandler) {
            return handler.acquireCache(version.namespace)
        }
        return null
    }

    fun setCommonLogger(logger: Logger) {
        this.logger = KotlinLogging.logger(logger)
    }

    internal fun loadClass(
        version: String,
        className: String
    ): Class<*>? {
        val classLoader = classLoaders.computeIfAbsent(version) {
            VersionedClassLoader(version)
        }
        return try {
            classLoader.loadClass(className)
//            classLoader.getClass(className)
        } catch (ex: ClassNotFoundException) {
            null
        }
    }
}