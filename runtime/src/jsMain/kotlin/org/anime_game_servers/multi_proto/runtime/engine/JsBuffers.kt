package org.anime_game_servers.multi_proto.runtime.engine

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

// protobuf.js Writer: a chunked, lazy-length writer. fork()/ldelim() emit length-delimited regions
// without allocating+concatenating a temp buffer per sub-message — the fast path for encode.
// .js is required: protobufjs is a CommonJS package with no `exports` map, so Node's ESM loader won't
// resolve the bare subpath (bundlers do). Needed to run under Node ESM (incl. jsNodeTest).
@JsModule("protobufjs/minimal.js")
external object protobuf {
    object util {
        val Long: ProtoLongCtor?
    }

    class Writer {
        fun uint32(value: Int): Writer
        fun uint64(value: dynamic): Writer
        fun fixed32(value: Int): Writer
        fun fixed64(value: dynamic): Writer
        fun bytes(value: Uint8Array): Writer
        fun fork(): Writer
        fun ldelim(): Writer
        fun finish(): Uint8Array

        companion object {
            fun create(): Writer
        }
    }
}

external interface ProtoLongCtor {
    fun fromBits(low: Int, high: Int, unsigned: Boolean): dynamic
}

internal fun ByteArray.toUint8Array(): Uint8Array {
    val i8 = this.unsafeCast<Int8Array>()
    return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
}

internal fun Uint8Array.toByteArray(): ByteArray {
    return Int8Array(this.buffer, this.byteOffset, this.length).unsafeCast<ByteArray>()
}

internal class JsProtobufWriter : ProtoWriter {
    private val w = protobuf.Writer.create()

    // Kotlin/JS Long -> protobuf.js Long via fromBits (requires the `long` npm package, which
    // protobuf.js auto-detects into util.Long). Falls back to a JS number if absent.
    private fun longValue(value: Long): dynamic {
        val ctor = protobuf.util.Long
        return if (ctor != null) ctor.fromBits(value.toInt(), (value ushr 32).toInt(), false)
        else value.toDouble()
    }

    override fun writeVarint(value: Long) { w.uint64(longValue(value)) }
    override fun writeVarint(value: Int) { w.uint32(value) }
    override fun writeFixed32(bits: Int) { w.fixed32(bits) }
    override fun writeFixed64(bits: Long) { w.fixed64(longValue(bits)) }
    override fun writeLengthDelimited(bytes: ByteArray) { w.bytes(bytes.toUint8Array()) }
    override fun fork() { w.fork() }
    override fun ldelim() { w.ldelim() }
    override fun toByteArray(): ByteArray = w.finish().toByteArray()
}

object JsProtoBufferFactory : ProtoBufferFactory {
    // Reads use the pure-Kotlin reader (decode is not the hot path); writes use protobuf.js.
    override fun reader(bytes: ByteArray): ProtoReader = ByteArrayProtoReader(bytes)
    override fun writer(): ProtoWriter = JsProtobufWriter()
}
