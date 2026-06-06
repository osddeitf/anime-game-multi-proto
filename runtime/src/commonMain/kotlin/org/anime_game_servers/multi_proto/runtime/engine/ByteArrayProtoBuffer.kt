package org.anime_game_servers.multi_proto.runtime.engine

// Pure-Kotlin byte buffer implementing the platform surface. fork()/ldelim() use a frame stack so
// nested length-delimited regions are length-prefixed without per-submessage concatenation of the
// parent. Used as a portable fallback (and on the JVM via Netty's variant); JS uses protobuf.js.

internal class ByteArrayProtoReader(private val bytes: ByteArray) : ProtoReader {
    private var pos = 0
    override val isReadable: Boolean get() = pos < bytes.size
    override fun readByte(): Byte = bytes[pos++]
    override fun readBytes(count: Int): ByteArray {
        val out = bytes.copyOfRange(pos, pos + count)
        pos += count
        return out
    }
}

internal class GrowableProtoWriter : ProtoWriter {
    private class Frame {
        var buf = ByteArray(64)
        var size = 0
        fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size * 2
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
        fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }
        fun raw(b: ByteArray, len: Int) { ensure(len); b.copyInto(buf, size, 0, len); size += len }
    }

    private val stack = ArrayDeque<Frame>().apply { addLast(Frame()) }
    private val cur: Frame get() = stack.last()

    private fun varint(f: Frame, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) { f.byte(v.toInt()); return }
            f.byte(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
    }

    private fun varint(f: Frame, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) { f.byte(v); return }
            f.byte((v and 0x7F) or 0x80); v = v ushr 7
        }
    }

    override fun writeVarint(value: Long) = varint(cur, value)
    override fun writeVarint(value: Int) = varint(cur, value)
    override fun writeFixed32(bits: Int) {
        val f = cur
        f.byte(bits and 0xFF); f.byte((bits ushr 8) and 0xFF); f.byte((bits ushr 16) and 0xFF); f.byte((bits ushr 24) and 0xFF)
    }
    override fun writeFixed64(bits: Long) {
        val f = cur
        for (i in 0 until 8) f.byte(((bits ushr (8 * i)) and 0xFF).toInt())
    }
    override fun writeLengthDelimited(bytes: ByteArray) { varint(cur, bytes.size); cur.raw(bytes, bytes.size) }
    override fun fork() { stack.addLast(Frame()) }
    override fun ldelim() {
        val child = stack.removeLast()
        varint(cur, child.size)
        cur.raw(child.buf, child.size)
    }
    override fun toByteArray(): ByteArray = stack.first().let { it.buf.copyOf(it.size) }
}

object ByteArrayProtoBufferFactory : ProtoBufferFactory {
    override fun reader(bytes: ByteArray): ProtoReader = ByteArrayProtoReader(bytes)
    override fun writer(): ProtoWriter = GrowableProtoWriter()
}
