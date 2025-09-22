package org.anime_game_servers.multi_proto.gi.utils

import kotlin.reflect.KClass

interface ProtoHandler {
    fun <T : Any> encodeToByteArray(version: String, modelClass: KClass<out T>, model: T): ByteArray?
    fun <T : Any> decodeFromByteArray(version: String, modelClass: KClass<out T>, byteArray: ByteArray): T?
}
