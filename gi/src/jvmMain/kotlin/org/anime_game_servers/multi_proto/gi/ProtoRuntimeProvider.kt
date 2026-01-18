package org.anime_game_servers.multi_proto.gi

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.slf4j.logger
import org.slf4j.Logger
import java.util.*

actual object ProtoRuntimeProvider {

    private var logger: KLogger? = null

    actual val service: ProtoRuntime by lazy {
        ServiceLoader.load(ProtoRuntime::class.java).first()
    }

    fun setCommonLogger(logger: Logger) {
        this.logger = KotlinLogging.logger(logger)
    }

    fun getLogger() = logger
}