package org.anime_game_servers.multi_proto.runtime.common

import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.sequences.forEach

fun packetMapperFromCSVStream(inputStream: InputStream): PacketIdProvider {
    val forward = mutableMapOf<String, Int>()
    val reverse = mutableMapOf<Int, String>()

    BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
        reader.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") } // ignore empty lines and comments
            .forEach { line ->
                val (key, value) = line.split(",", limit = 2).map { it.trim() }
                value.toIntOrNull()?.let {
                    forward[key] = it
                    reverse[it] = key
                }
            }
    }

    return object : PacketIdProvider {
        override fun getPacketId(packetName: String): Int {
            return forward[packetName] ?: 999999
        }

        override fun getPacketName(packetId: Int): String? {
            return reverse[packetId]
        }
    }
}

fun KType.prettyString(): String {
    val classifier = classifier as? KClass<*> ?: return toString()
    val baseName = classifier.simpleName ?: classifier.toString()
    val typeArgs = arguments.map { arg -> arg.type?.prettyString() ?: "*" }

    val typeString = if (typeArgs.isNotEmpty()) {
        "$baseName<${typeArgs.joinToString(", ")}>"
    } else baseName

    return if (isMarkedNullable) "$typeString?" else typeString
}

fun Type.prettyString(): String = when (this) {
    is Class<*> -> simpleName
    is ParameterizedType -> {
        val raw = (rawType as? Class<*>)?.simpleName ?: rawType.toString()
        val args = actualTypeArguments.joinToString(", ") { it.prettyString() }
        "$raw<$args>"
    }

    else -> toString()
}

fun defaultMemberValue(type: KClass<*>): Any? = when (type) {
    Boolean::class -> false
    Char::class -> '\u0000'
    Byte::class -> 0.toByte()
    Short::class -> 0.toShort()
    Int::class -> 0
    Long::class -> 0L
    Float::class -> 0f
    Double::class -> 0.0
    String::class -> ""
    ByteArray::class -> byteArrayOf()
    List::class -> listOf<Any>()
    Map::class -> mapOf<Any, Any>()
    else -> {
        if (type.java.isEnum) {
            val enums = type.java.enumConstants as Array<Enum<*>>
            val value = enums.firstOrNull { it.name === "UNRECOGNISED" }
            value ?: error("Expect enum has UNRECOGNISED value")
        } else null
    } // not a primitive type
}