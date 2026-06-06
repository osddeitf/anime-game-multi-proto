package org.anime_game_servers.multi_proto.runtime.common

import org.anime_game_servers.multi_proto.core.interfaces.PacketIdProvider

/** Build a packet-id mapping from CSV text ("name,id" per line; '#' comments). Platform-neutral. */
fun packetMapperFromCsv(text: String): PacketIdProvider {
    val forward = mutableMapOf<String, Int>()
    val reverse = mutableMapOf<Int, String>()
    text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .forEach { line ->
            val parts = line.split(",", limit = 2).map { it.trim() }
            if (parts.size == 2) parts[1].toIntOrNull()?.let {
                forward[parts[0]] = it
                reverse[it] = parts[0]
            }
        }
    return object : PacketIdProvider {
        override fun getPacketId(packetName: String): Int = forward[packetName] ?: 999999
        override fun getPacketName(packetId: Int): String? = reverse[packetId]
    }
}
