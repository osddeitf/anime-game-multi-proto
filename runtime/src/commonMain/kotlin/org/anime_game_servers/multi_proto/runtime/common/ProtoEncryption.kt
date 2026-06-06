package org.anime_game_servers.multi_proto.runtime.common

import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation
import kotlin.ranges.contains

@Suppress("UNCHECKED_CAST")
fun <T: Any> List<EncryptionOperation>?.applyDecryption(value: T): T {
    var ret = value
    for (step in this.orEmpty().asReversed()) {
        ret = when (ret) {
            is Int -> {
                val param: Int =
                    if (step.param in Int.MIN_VALUE..Int.MAX_VALUE) step.param.toInt()
                    else throw ArithmeticException("Overflow when converting Long to Int")

                step.op.decryptI32(ret, param)
            }

            is Long -> step.op.decryptI64(ret, step.param)
            // TODO: apply for float / double
            else -> ret
        } as T
    }
    return ret
}

@Suppress("UNCHECKED_CAST")
fun <T: Any> List<EncryptionOperation>?.applyEncryption(value: T): T {
    var ret = value
    for (step in this.orEmpty()) {
        ret = when (ret) {
            is Int -> {
                val param: Int =
                    if (step.param in Int.MIN_VALUE..Int.MAX_VALUE) step.param.toInt()
                    else throw ArithmeticException("Overflow when converting Long to Int")

                step.op.encryptI32(ret, param)
            }

            is Long -> step.op.encryptI64(ret, step.param)
            // TODO: apply for float / double
            else -> ret
        } as T
    }
    return ret
}
