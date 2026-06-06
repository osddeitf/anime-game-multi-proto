package org.anime_game_servers.multi_proto.runtime.engine

import org.khronos.webgl.Uint8Array

// Parses a binary FileDescriptorSet via protobuf.js (ext/descriptor) into the platform-neutral model.
// The decoded message uses descriptor.proto's camelCase field names (messageType, enumType, typeName,
// oneofIndex, oneofDecl, mapEntry, ...). Field/enum numbers fit in 32 bits, so no 64-bit concern here.

private fun arr(v: dynamic): Array<dynamic> =
    if (v == null || v == undefined) emptyArray() else v.unsafeCast<Array<dynamic>>()

private fun fieldType(t: Int): FieldType = when (t) {
    1 -> FieldType.DOUBLE
    2 -> FieldType.FLOAT
    3 -> FieldType.INT64
    4 -> FieldType.UINT64
    5 -> FieldType.INT32
    6 -> FieldType.FIXED64
    7 -> FieldType.FIXED32
    8 -> FieldType.BOOL
    9 -> FieldType.STRING
    10 -> FieldType.GROUP
    11 -> FieldType.MESSAGE
    12 -> FieldType.BYTES
    13 -> FieldType.UINT32
    14 -> FieldType.ENUM
    15 -> FieldType.SFIXED32
    16 -> FieldType.SFIXED64
    17 -> FieldType.SINT32
    18 -> FieldType.SINT64
    else -> error("Unknown proto field type $t")
}

private fun fieldLabel(l: Int): FieldLabel = when (l) {
    1 -> FieldLabel.OPTIONAL
    2 -> FieldLabel.REQUIRED
    3 -> FieldLabel.REPEATED
    else -> FieldLabel.OPTIONAL
}

private fun convertField(f: dynamic): FieldDescriptor {
    val oneof = f.oneofIndex
    val typeName = f.typeName
    return FieldDescriptor(
        name = f.name as String,
        number = (f.number as Number).toInt(),
        type = fieldType((f.type as Number).toInt()),
        label = fieldLabel((f.label as Number).toInt()),
        typeName = if (typeName == null || typeName == undefined) null else typeName as String,
        oneofIndex = if (oneof == null || oneof == undefined) null else (oneof as Number).toInt(),
    )
}

fun loadDescriptorJs(buffer: Uint8Array): ProtobufDescriptor {
    val set = protobufDescriptor.FileDescriptorSet.decode(buffer)
    val enums = mutableMapOf<String, EnumDescriptor>()
    val messages = mutableMapOf<String, MessageDescriptor>()

    fun addEnum(e: dynamic, prefix: String) {
        val name = e.name as String
        val values = arr(e.value).map { EnumValueDescriptor(it.name as String, (it.number as Number).toInt()) }
        enums["$prefix.$name"] = EnumDescriptor(name, values)
    }

    fun addMessage(m: dynamic, prefix: String) {
        val name = m.name as String
        val full = "$prefix.$name"
        val fields = arr(m.field).map { convertField(it) }
        val oneofNames = arr(m.oneofDecl).map { it.name as String }
        val options = m.options
        val mapEntry = if (options == null || options == undefined) false else options.mapEntry == true
        messages[full] = MessageDescriptor(name, fields, oneofNames, mapEntry)
        arr(m.nestedType).forEach { addMessage(it, full) }
        arr(m.enumType).forEach { addEnum(it, full) }
    }

    arr(set.file).forEach { file ->
        arr(file.messageType).forEach { addMessage(it, "") }
        arr(file.enumType).forEach { addEnum(it, "") }
    }
    return ProtobufDescriptor(enums, messages)
}
