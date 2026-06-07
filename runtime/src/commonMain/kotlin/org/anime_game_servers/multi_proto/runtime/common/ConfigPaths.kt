@file:OptIn(ExperimentalJsExport::class)

package org.anime_game_servers.multi_proto.runtime.common

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/** Placeholder substituted with the version namespace in each pattern. */
const val CONFIG_VERSION_PLACEHOLDER = "{version}"

/**
 * Per-file path patterns for a version's input files, shared by the JVM and JS runtimes. Exposed as a
 * plain interface (a TS interface on JS), so a consumer can pass an options object literal — no class to
 * instantiate. Each field is optional; an omitted/null field falls back to the conventional
 * config/<version>/ default. "{version}" is replaced with the version namespace.
 */
@JsExport
interface ConfigPaths {
    val descriptor: String?
    val mapping: String?
    val encryption: String?
    val packets: String?
}

// Defaults, applied when a field is absent/null. Single source of the conventional layout.
val ConfigPaths?.descriptorPattern: String get() = this?.descriptor ?: "config/$CONFIG_VERSION_PLACEHOLDER/proto.desc"
val ConfigPaths?.mappingPattern: String get() = this?.mapping ?: "config/$CONFIG_VERSION_PLACEHOLDER/mapping.json"
val ConfigPaths?.encryptionPattern: String get() = this?.encryption ?: "config/$CONFIG_VERSION_PLACEHOLDER/encryption.json"
val ConfigPaths?.packetsPattern: String get() = this?.packets ?: "config/$CONFIG_VERSION_PLACEHOLDER/packets.csv"

/** Substitute [CONFIG_VERSION_PLACEHOLDER] in [pattern] with the [version] namespace. */
fun resolveConfigPath(pattern: String, version: String): String =
    pattern.replace(CONFIG_VERSION_PLACEHOLDER, version)

/** Kotlin/JVM convenience builder; JS consumers just pass an object literal. Omitted fields use defaults. */
fun ConfigPaths(
    descriptor: String? = null,
    mapping: String? = null,
    encryption: String? = null,
    packets: String? = null,
): ConfigPaths {
    val d = descriptor; val m = mapping; val e = encryption; val p = packets
    return object : ConfigPaths {
        override val descriptor = d
        override val mapping = m
        override val encryption = e
        override val packets = p
    }
}
