package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.runtime.engine.ByteArrayProtoBufferFactory
import org.anime_game_servers.multi_proto.runtime.engine.asZigZag
import org.anime_game_servers.multi_proto.runtime.engine.encodeZigZag
import org.anime_game_servers.multi_proto.runtime.engine.readFixed32
import org.anime_game_servers.multi_proto.runtime.engine.readFixed64
import org.anime_game_servers.multi_proto.runtime.engine.readVarint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure wire-codec checks on the common [ByteArrayProtoBufferFactory] — no fixtures, no gi. Locks the
 * varint/zigzag/fixed primitives on both JVM and JS, and is a quick warm-up for the harness.
 */
@OptIn(ExperimentalStdlibApi::class)
class ProtoWireTest {

    private val factory = ByteArrayProtoBufferFactory

    @Test
    fun varintRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 16384L, Long.MAX_VALUE, -1L)) {
            val w = factory.writer(); w.writeVarint(v)
            assertEquals(v, factory.reader(w.toByteArray()).readVarint(), "varint $v")
        }
    }

    @Test
    fun knownVarintBytes() { // 300 -> AC 02
        val w = factory.writer(); w.writeVarint(300L)
        assertEquals("ac02", w.toByteArray().toHexString())
    }

    @Test
    fun fixed32RoundTrip() {
        val w = factory.writer(); w.writeFixed32(0x01020304)
        assertEquals(0x01020304, factory.reader(w.toByteArray()).readFixed32())
    }

    @Test
    fun fixed64RoundTrip() {
        val w = factory.writer(); w.writeFixed64(0x0102030405060708L)
        assertEquals(0x0102030405060708L, factory.reader(w.toByteArray()).readFixed64())
    }

    @Test
    fun zigzag() {
        for (v in listOf(0, -1, 1, -2, 2, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, v.encodeZigZag().asZigZag(), "zigzag $v")
        }
    }

    @Test
    fun forkLdelimNestsLength() {
        // outer field 1 (LEN) wrapping inner varint field 1 = 1 -> 0A 02 08 01
        val w = factory.writer()
        w.writeVarint((1 shl 3) or 2) // tag: field 1, wire 2 (LEN)
        w.fork()
        w.writeVarint((1 shl 3) or 0) // inner tag: field 1, wire 0
        w.writeVarint(1L)
        w.ldelim()
        assertEquals("0a02 0801".replace(" ", ""), w.toByteArray().toHexString())
    }
}
