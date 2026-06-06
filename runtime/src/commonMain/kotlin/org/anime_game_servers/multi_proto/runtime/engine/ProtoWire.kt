package org.anime_game_servers.multi_proto.runtime.engine

// Shared protobuf wire codec built on the minimal ProtoReader/ProtoWriter byte primitives.
// Ported verbatim (logic-wise) from the original Netty-based ProtoEncoding so JVM bytes are
// unchanged; now platform-neutral.

// ---- readers ----

fun ProtoReader.readVarint(): Long {
    var value = 0L
    var position = 0
    while (true) {
        if (!isReadable) error("Not enough bytes to read VarInt")
        val b = readByte().toInt()
        value = value or ((b.toLong() and 0x7F) shl position)
        if ((b and 0x80) == 0) return value
        position += 7
        if (position >= 64) error("VarInt too long")
    }
}

fun ProtoReader.readFixed32(): Int {
    val b = readBytes(4)
    return (b[0].toInt() and 0xFF) or
        ((b[1].toInt() and 0xFF) shl 8) or
        ((b[2].toInt() and 0xFF) shl 16) or
        ((b[3].toInt() and 0xFF) shl 24)
}

fun ProtoReader.readFixed64(): Long {
    val b = readBytes(8)
    var r = 0L
    for (i in 0 until 8) r = r or ((b[i].toLong() and 0xFF) shl (8 * i))
    return r
}

fun ProtoReader.readLengthDelimited(): ByteArray {
    val len = readVarint()
    if (!len.isSafeInt()) error("LEN delimited data is too long")
    return readBytes(len.toInt())
}

// packed converters: operate over a sub-reader created from the length-delimited bytes
fun ProtoReader.readPackedVarint(): List<Long> {
    try {
        val list = mutableListOf<Long>()
        while (isReadable) list += readVarint()
        return list
    } catch (ex: IllegalStateException) {
        throw IllegalArgumentException(ex.message)
    }
}

fun ProtoReader.readPackedFixed32(): List<Int> {
    val list = mutableListOf<Int>()
    while (isReadable) list += readFixed32()
    return list
}

fun ProtoReader.readPackedFixed64(): List<Long> {
    val list = mutableListOf<Long>()
    while (isReadable) list += readFixed64()
    return list
}

// ---- integer / zigzag helpers ----

fun Int.asZigZag() = (this ushr 1) xor -(this and 1)
fun Int.encodeZigZag() = (this shl 1) xor (this shr 31)
fun Long.asZigZag() = (this ushr 1) xor -(this and 1)
fun Long.encodeZigZag() = (this shl 1) xor (this shr 63)
fun Long.isSafeInt() = toULong() <= UInt.MAX_VALUE.toULong()

// ---- writers ----

fun ProtoWriter.writeVarint(value: Long) {
    var v = value
    while (true) {
        if ((v and 0x7FL.inv()) == 0L) {
            writeByte(v.toInt())
            return
        }
        writeByte(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

// NOTE: 32-bit varint (no sign extension); used for uint32/lengths. Distinct from the Long variant.
fun ProtoWriter.writeVarint(value: Int) {
    var v = value
    while (true) {
        if ((v and 0x7F.inv()) == 0) {
            writeByte(v)
            return
        }
        writeByte((v and 0x7F) or 0x80)
        v = v ushr 7
    }
}

fun ProtoWriter.writeFixed32(bits: Int) {
    writeByte(bits and 0xFF)
    writeByte((bits ushr 8) and 0xFF)
    writeByte((bits ushr 16) and 0xFF)
    writeByte((bits ushr 24) and 0xFF)
}

fun ProtoWriter.writeFixed64(bits: Long) {
    for (i in 0 until 8) writeByte(((bits ushr (8 * i)) and 0xFF).toInt())
}

fun ProtoWriter.writeFieldTag(number: Int, wire: Int) {
    writeVarint((number shl 3) or wire)
}

/** Write a length-delimited block: varint(size) followed by the bytes. */
fun ProtoWriter.writeLengthDelimited(bytes: ByteArray) {
    writeVarint(bytes.size)
    writeBytes(bytes)
}
