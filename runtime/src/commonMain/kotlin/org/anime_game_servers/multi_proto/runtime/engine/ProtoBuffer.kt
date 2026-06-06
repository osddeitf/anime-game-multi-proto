package org.anime_game_servers.multi_proto.runtime.engine

// Minimal platform byte-buffer surface. Everything wire-format related (varint, zigzag, fixed,
// length-delimited, packed) is implemented once in commonMain on top of these primitives (see
// ProtoWire.kt), so encoded bytes are identical on every platform. JVM backs this with Netty;
// JS will back it with a protobufjs/typed-array buffer.

interface ProtoReader {
    val isReadable: Boolean
    fun readByte(): Byte
    fun readBytes(count: Int): ByteArray
}

interface ProtoWriter {
    fun writeByte(value: Int)
    fun writeBytes(bytes: ByteArray)
    fun toByteArray(): ByteArray
}

interface ProtoBufferFactory {
    fun reader(bytes: ByteArray): ProtoReader
    fun writer(): ProtoWriter
}
