package org.anime_game_servers.multi_proto.runtime

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.slf4j.logger
import kotlinx.serialization.json.Json
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import org.anime_game_servers.multi_proto.gi.utils.ProtoHandler
import org.slf4j.Logger
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.collections.get
import kotlin.reflect.KClass

internal class DynamicProtoHandler: ProtoHandler {
    private val reflectionCaches = ConcurrentHashMap<String, ReflectionCache>()

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> encodeToByteArray(version: String, modelClass: KClass<out T>, model: T): ByteArray? {
        return try {
            acquireCache(version).encodeToByteArray(modelClass, model)
        }
        catch (ex: Exception) {
            ProtoLoader.logger?.error(ex) { "FATAL: $version - encodeToByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T: Any> decodeFromByteArray(version: String, modelClass: KClass<out T>, byteArray: ByteArray): T? {
        return try {
            acquireCache(version).decodeFromByteArray(modelClass, byteArray)
        }
        catch (ex: Exception) {
            ProtoLoader.logger?.error(ex) { "FATAL: $version - decodeFromByteArray failed for ${modelClass.simpleName}" }
            null
        }
    }

    internal fun acquireCache(version: String): ReflectionCache {
        val cache = reflectionCaches.computeIfAbsent(version) {
            val instance = ReflectionCache(version)
            instance.loadMappingFromJson()
            instance.loadEncryptionFromJson()
            instance
        }
        cache.setLogger(ProtoLoader.logger) // always keep the logger up-to-date
        return cache
    }

}