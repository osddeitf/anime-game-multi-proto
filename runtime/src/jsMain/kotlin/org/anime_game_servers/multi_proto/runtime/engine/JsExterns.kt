package org.anime_game_servers.multi_proto.runtime.engine

import org.khronos.webgl.Uint8Array

// Node.js filesystem.
@JsModule("fs")
external object fs {
    fun existsSync(path: String): Boolean
    fun readFileSync(path: String): Uint8Array          // binary -> Buffer (a Uint8Array)
    fun readFileSync(path: String, encoding: String): String
}

// protobuf.js descriptor extension: decodes a binary FileDescriptorSet (.desc).
@JsModule("protobufjs/ext/descriptor.js")
external object protobufDescriptor {
    val FileDescriptorSet: FileDescriptorSetType
}

external interface FileDescriptorSetType {
    fun decode(buffer: Uint8Array): dynamic
}
