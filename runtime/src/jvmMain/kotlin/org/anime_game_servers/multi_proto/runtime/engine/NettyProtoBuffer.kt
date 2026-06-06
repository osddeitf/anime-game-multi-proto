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

internal class NettyProtoWriter(private val buf: ByteBuf = Unpooled.buffer()) : ProtoWriter {
    override fun writeByte(value: Int) { buf.writeByte(value) }
    override fun writeBytes(bytes: ByteArray) { buf.writeBytes(bytes) }
    override fun toByteArray(): ByteArray {
        val out = ByteArray(buf.readableBytes())
        buf.getBytes(buf.readerIndex(), out)
        return out
    }
}

object NettyProtoBufferFactory : ProtoBufferFactory {
    override fun reader(bytes: ByteArray): ProtoReader = NettyProtoReader(Unpooled.wrappedBuffer(bytes))
    override fun writer(): ProtoWriter = NettyProtoWriter()
}
