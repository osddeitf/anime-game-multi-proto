package org.anime_game_servers.multi_proto.runtime.test

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.anime_game_servers.multi_proto.gi.ProtoModelRegistry
import org.anime_game_servers.multi_proto.runtime.common.ProtoMappingConfig
import org.anime_game_servers.multi_proto.runtime.common.fixTypo
import org.anime_game_servers.multi_proto.runtime.common.toSnakeCase
import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation
import org.anime_game_servers.multi_proto.runtime.engine.ByteArrayProtoBufferFactory
import org.anime_game_servers.multi_proto.runtime.engine.ProtoDescriptorRuntime
import org.anime_game_servers.multi_proto.runtime.engine.ProtobufDescriptor

/**
 * Shared e2e harness: builds the descriptor engine directly (bypassing the per-version disk layout of
 * ProtoRuntimeImpl/JsProtoRuntime) so each case can pick one of the two descriptors and feed it a
 * per-case sub-mapping / sub-encryption sliced from the single full config files.
 *
 * Encoding/decoding uses the pure-common [ByteArrayProtoBufferFactory] (works on both platforms) and gi's
 * KSP-generated registrations; the global ProtoModelRegistry hub is never wired.
 */
private typealias EncryptionConfig = Map<String, Map<String, List<EncryptionOperation>>>

// Loaded once. `by lazy` so the build stays green when fixtures are absent (cases guard with fixturesPresent()).
val plainDesc: ProtobufDescriptor by lazy { loadTestDescriptor("plain.desc") }
val obfDesc: ProtobufDescriptor by lazy { loadTestDescriptor("obf.desc") }
val fullMapping: ProtoMappingConfig by lazy { Json.decodeFromString(readTestResource("mapping.json")) }
val fullEncryption: EncryptionConfig by lazy { Json.decodeFromString(readTestResource("encryption.json")) }

private val models by lazy { ProtoModelRegistry.getModels().associateBy { it.simpleName } }
private val enums by lazy { ProtoModelRegistry.getEnums().associateBy { it.simpleName } }

/**
 * Slice the full mapping down to the [names] a case touches (messages, fields, enums). Pass model-side
 * names (e.g. "EnterSceneReadyRsp", "retCode", "enterSceneToken", "Retcode"); each is expanded through the
 * same normalization the engine uses (`toSnakeCase` + `fixTypo`) so the matching reverse tokens — which are
 * snake_case / typo-fixed (e.g. "enter_scene_token", "retcode") — are retained.
 */
fun subMapping(vararg names: String): ProtoMappingConfig {
    val keep = names.flatMapTo(mutableSetOf()) { listOf(it, it.toSnakeCase(), fixTypo(it)) }
    return fullMapping.copy(
        translation = fullMapping.translation.filterKeys { it in keep },
        reverse = fullMapping.reverse.filterValues { it in keep },
        fields = fullMapping.fields.filterKeys { it in keep },
        enums = fullMapping.enums.filterKeys { it in keep },
        mockEncode = emptyMap(),
        mockDecode = emptyMap(),
    )
}

/** Slice the full encryption config down to the model classes [names] a case touches. */
fun subEncryption(vararg names: String): EncryptionConfig {
    val keep = names.toSet()
    return fullEncryption.filterKeys { it in keep }
}

/** Construct a fresh engine for one case. Sub-config is serialized back through the production loaders. */
fun buildRuntime(
    desc: ProtobufDescriptor,
    mapping: ProtoMappingConfig? = null,
    encryption: EncryptionConfig? = null,
): ProtoDescriptorRuntime = ProtoDescriptorRuntime("test", desc, ByteArrayProtoBufferFactory, models, enums).apply {
    mapping?.let { loadMapping(Json.encodeToString(it)) }
    encryption?.let { loadEncryption(Json.encodeToString(it)) }
}

/** encode → decode; the caller asserts on the returned model's field(s). */
@Suppress("UNCHECKED_CAST")
fun <T : Any> ProtoDescriptorRuntime.roundTrip(model: T, simpleName: String = model::class.simpleName!!): T {
    val bytes = encodeToByteArray(simpleName, model)
    return decodeFromByteArray(simpleName, bytes) as T
}
