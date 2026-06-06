package org.anime_game_servers.multi_proto.runtime.engine

// Platform byte-buffer surface.
//
// Reads stay byte-level (decode is not the hot path); all read-side wire decoding lives in commonMain
// (ProtoWire.kt). Writes are higher-level and include fork()/ldelim() so length-delimited regions
// (nested messages, packed fields, map entries) are emitted WITHOUT allocating a temp buffer per
// sub-message and concatenating — the writer back-patches the length. On JS this is backed by
// protobuf.js's Writer (native fork/ldelim, single final copy); on JVM by Netty.

interface ProtoReader {
    val isReadable: Boolean
    fun readByte(): Byte
    fun readBytes(count: Int): ByteArray
}

interface ProtoWriter {
    fun writeVarint(value: Long)
    fun writeVarint(value: Int)
    fun writeFixed32(bits: Int)
    fun writeFixed64(bits: Long)
    /** Write a length-delimited block: varint(size) followed by the bytes (for string/bytes fields). */
    fun writeLengthDelimited(bytes: ByteArray)
    /** Begin a length-delimited region; pair with [ldelim]. Regions may nest. */
    fun fork()
    /** Close the region opened by [fork], prefixing its byte length as a varint. */
    fun ldelim()
    fun toByteArray(): ByteArray
}

interface ProtoBufferFactory {
    fun reader(bytes: ByteArray): ProtoReader
    fun writer(): ProtoWriter
}
