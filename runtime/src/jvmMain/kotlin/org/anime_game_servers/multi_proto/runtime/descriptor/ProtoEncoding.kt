package org.anime_game_servers.multi_proto.runtime.descriptor

import io.netty.buffer.ByteBuf
import org.anime_game_servers.multi_proto.runtime.common.applyEncryption
import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation

fun ByteBuf.readVarInt(): Long {
    var value = 0L
    var position = 0

    while (true) {
        if (!isReadable) {
            error("Not enough bytes to read VarInt")
        }

        val b = readByte().toInt()
        value = value or ((b.toLong() and 0x7F) shl position)

        if ((b and 0x80) == 0) {
            return value
        }

        position += 7
        if (position >= 64) {
            error("VarInt too long")
        }
    }
}

fun ByteBuf.readFixed32(): ByteBuf = this.readSlice(4)
fun ByteBuf.readFixed64(): ByteBuf = this.readSlice(8)

fun ByteBuf.readLenDelimited(): ByteBuf {
    val int = readVarInt()
    if (!int.isSafeInt()) {
        error("LEN delimited data is too long")
    }
    val len = int.toUInt().toInt()   // TODO: hopefully it isn't negative
    return this.readSlice(len)
}


// fixed types converters
fun ByteBuf.asFloat() = this.readFloatLE()
fun ByteBuf.asDouble() = this.readDoubleLE()
fun ByteBuf.asInt32() = this.readIntLE()
fun ByteBuf.asInt64() = this.readLongLE()

// packed converters
fun ByteBuf.asPackedVarInt(): List<Long> {
    try {
        val list = mutableListOf<Long>()
        while (this.isReadable) {
            list += this.readVarInt()
        }
        return list
    }
    catch (ex: IllegalStateException) {
        // convert to non-fatal exception
        throw IllegalArgumentException(ex.message)
    }
}
fun ByteBuf.asPackedFixed32(): List<ByteBuf> {
    if (this.readableBytes() % 4 != 0) {
        throw IllegalArgumentException("Not a packed fixed32 type")
    }
    val list = mutableListOf<ByteBuf>()
    while (this.isReadable) {
        list += this.readFixed32()
    }
    return list
}
fun ByteBuf.asPackedFixed64(): List<ByteBuf> {
    if (this.readableBytes() % 8 != 0) {
        throw IllegalArgumentException("Not a packed fixed64 type")
    }
    val list = mutableListOf<ByteBuf>()
    while (this.isReadable) {
        list += this.readFixed64()
    }
    return list
}

// len-delim converters. TODO: according to chatgpt, invalid characters are substituted
fun ByteBuf.asString(): String = this.toString(Charsets.UTF_8)

fun ByteBuf.asByteArray(): ByteArray {
    val len = readableBytes()
    val bytes = ByteArray(len)
    readBytes(bytes)
    return bytes
}

// integer converters
fun Int.asZigZag() = (this ushr 1) xor -(this and 1)
fun Int.encodeZigZag() = (this shl 1) xor (this shr 31)
fun Long.asZigZag() = (this ushr 1) xor -(this and 1)
fun Long.encodeZigZag() = (this shl 1) xor (this shr 63)
fun Long.isSafeInt() = toUInt() in UInt.MIN_VALUE..UInt.MAX_VALUE

// writers
fun ByteBuf.writeVarInt(encryption: List<EncryptionOperation>?, value: Long) {
    var v = encryption.applyEncryption(value) as Long
    while (true) {
        if ((v and 0x7FL.inv()) == 0L) {
            writeByte(v.toInt())
            return
        }
        writeByte(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

fun ByteBuf.writeVarInt(encryption: List<EncryptionOperation>?, value: Int) {
    // NOTE: though look the same as Long variant, it actually not
    var v = encryption.applyEncryption(value) as Int
    while (true) {
        if ((v and 0x7F.inv()) == 0) {
            writeByte(v)
            return
        }
        writeByte((v and 0x7F) or 0x80)
        v = v ushr 7
    }
}

fun ByteBuf.writeBoolean(encryption: List<EncryptionOperation>?, value: Boolean) {
    val data = encryption.applyEncryption(value) as Boolean
    writeBoolean(data)
}
fun ByteBuf.writeIntLE(encryption: List<EncryptionOperation>?, value: Int) {
    val data = encryption.applyEncryption(value) as Int
    writeIntLE(data)
}
fun ByteBuf.writeLongLE(encryption: List<EncryptionOperation>?, value: Long) {
    val data = encryption.applyEncryption(value) as Long
    writeLongLE(data)
}
fun ByteBuf.writeFloatLE(encryption: List<EncryptionOperation>?, value: Float) {
    val data = encryption.applyEncryption(value) as Float
    writeFloatLE(data)
}
fun ByteBuf.writeDoubleLE(encryption: List<EncryptionOperation>?, value: Double) {
    val data = encryption.applyEncryption(value) as Double
    writeDoubleLE(data)
}
fun ByteBuf.writeFieldTag(number: Int, wire: Int) {
    val tag = (number shl 3) or wire
    writeVarInt(null, tag)
}