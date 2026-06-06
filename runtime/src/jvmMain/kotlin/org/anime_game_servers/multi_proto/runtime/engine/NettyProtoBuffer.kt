package org.anime_game_servers.multi_proto.runtime.engine

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

internal class NettyProtoReader(private val buf: ByteBuf) : ProtoReader {
    override val isReadable: Boolean get() = buf.isReadable
    override fun readByte(): Byte = buf.readByte()
    override fun readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        buf.readBytes(out)
        return out
    }
}

internal class NettyProtoWriter : ProtoWriter {
    private val stack = ArrayDeque<ByteBuf>().apply { addLast(Unpooled.buffer()) }
    private val cur: ByteBuf get() = stack.last()

    override fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) { cur.writeByte(v.toInt()); return }
            cur.writeByte(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
    }

    override fun writeVarint(value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) { cur.writeByte(v); return }
            cur.writeByte((v and 0x7F) or 0x80); v = v ushr 7
        }
    }

    override fun writeFixed32(bits: Int) { cur.writeIntLE(bits) }
    override fun writeFixed64(bits: Long) { cur.writeLongLE(bits) }
    override fun writeLengthDelimited(bytes: ByteArray) { writeVarint(bytes.size); cur.writeBytes(bytes) }
    override fun fork() { stack.addLast(Unpooled.buffer()) }
    override fun ldelim() {
        val child = stack.removeLast()
        writeVarint(child.readableBytes())
        cur.writeBytes(child)
    }
    override fun toByteArray(): ByteArray {
        val root = stack.first()
        val out = ByteArray(root.readableBytes())
        root.getBytes(root.readerIndex(), out)
        return out
    }
}

object NettyProtoBufferFactory : ProtoBufferFactory {
    override fun reader(bytes: ByteArray): ProtoReader = NettyProtoReader(Unpooled.wrappedBuffer(bytes))
    override fun writer(): ProtoWriter = NettyProtoWriter()
}
