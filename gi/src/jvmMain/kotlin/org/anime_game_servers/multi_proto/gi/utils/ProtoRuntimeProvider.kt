package org.anime_game_servers.multi_proto.gi.utils

import java.util.ServiceLoader

actual object ProtoRuntimeProvider {
    actual val instance: ProtoHandler by lazy {
        ServiceLoader.load(ProtoHandler::class.java).first()
    }
}