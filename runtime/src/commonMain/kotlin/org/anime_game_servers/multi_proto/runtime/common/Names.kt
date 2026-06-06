package org.anime_game_servers.multi_proto.runtime.common

// Platform-agnostic name helpers shared by the descriptor runtime engine.

val typoMap = mapOf("retCode" to "retcode")
fun fixTypo(name: String) = typoMap[name] ?: name

fun String.toPascalCase() = this
    .split('_', '-', ' ')
    .filter { it.isNotEmpty() }
    .joinToString("") {
        it.replaceFirstChar(Char::uppercase)
    }

fun String.toSnakeCase(): String =
    this.replace(Regex("([a-z])([A-Z])"), "$1_$2") // split camelCase
        .replace(Regex("[\\s-]+"), "_")            // replace spaces/dashes with _
        .lowercase()
