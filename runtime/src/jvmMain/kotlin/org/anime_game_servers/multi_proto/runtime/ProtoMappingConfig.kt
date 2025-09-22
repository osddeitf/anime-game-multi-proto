package org.anime_game_servers.multi_proto.runtime
import kotlinx.serialization.Serializable

@Serializable
data class ProtoMappingConfig(
    val mockEncode: Map<String, String> = emptyMap(),     // model class -> hex data
    val mockDecode: Map<String, String> = emptyMap(),     // model class -> hex data
    val models: Map<String, String> = emptyMap(),         // model class -> proto class alias
    val fields: Map<String, Map<String, String>> = emptyMap(),  // model class → (kotlin field → proto field alias)
    val enums: Map<String, Map<String, String>> = emptyMap(),   // enum class -> (name -> proto name)
    val translation: Map<String, String> = emptyMap(),    // raw nt
)
