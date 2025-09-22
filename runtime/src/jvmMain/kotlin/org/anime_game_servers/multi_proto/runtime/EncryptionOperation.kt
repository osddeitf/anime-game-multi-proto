package org.anime_game_servers.multi_proto.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class EncryptionOperator{
    ADD, SUB, XOR;

    // varInt/I64
    fun encryptI64(value: Long, key: Long): Long = when(this){
        ADD -> value + key
        SUB -> value - key
        XOR -> value xor key
    }

    fun decryptI64(value: Long, key: Long): Long = when(this){
        ADD -> value - key
        SUB -> value + key
        XOR -> value xor key
    }

    // I32
    fun encryptI32(value: Int, key: Int): Int = when(this){
        ADD -> value + key
        SUB -> value - key
        XOR -> value xor key
    }

    fun decryptI32(value: Int, key: Int): Int = when(this){
        ADD -> value - key
        SUB -> value + key
        XOR -> value xor key
    }
}

@Serializable
data class EncryptionOperation(
    val op: EncryptionOperator,
    val param: Long,
)
