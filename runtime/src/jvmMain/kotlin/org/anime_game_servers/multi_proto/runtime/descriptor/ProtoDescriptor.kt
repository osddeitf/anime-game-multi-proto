package org.anime_game_servers.multi_proto.runtime.descriptor

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import java.io.File

data class ProtobufDescriptor(
    val enums: Map<String, EnumDescriptorProto>,
    val messages: Map<String, DescriptorProto>,
) {
    fun getFieldMessageType(field: FieldDescriptorProto): DescriptorProto? {
        if (field.type == FieldDescriptorProto.Type.TYPE_MESSAGE) {
            val fullName = field.typeName ?: error("Field ${field.name} missing type name")
            return messages[fullName] ?: error("Missing message $fullName")
        }
        return null
    }
    fun getFieldEnumType(field: FieldDescriptorProto): EnumDescriptorProto? {
        if (field.type == FieldDescriptorProto.Type.TYPE_ENUM) {
            val fullName = field.typeName ?: error("Field ${field.name} missing type name")
            return enums[fullName] ?: error("Missing message $fullName")
        }
        return null
    }
}

internal fun FieldDescriptorProto.isRepeated() = this.label == FieldDescriptorProto.Label.LABEL_REPEATED
internal fun FieldDescriptorProto.isMap(root: ProtobufDescriptor): Boolean {
    if (!this.hasTypeName()) return false
    val message = root.messages[this.typeName]
    return message?.isGeneratedMap() ?: false
}
internal fun FieldDescriptorProto.isZigzag() = when (type) {
    FieldDescriptorProto.Type.TYPE_SFIXED32,
    FieldDescriptorProto.Type.TYPE_SFIXED64,
    FieldDescriptorProto.Type.TYPE_SINT32,
    FieldDescriptorProto.Type.TYPE_SINT64 -> true
    else -> false
}

internal fun DescriptorProto.isGeneratedMap() = this.options.mapEntry
internal fun DescriptorProto.getFields() = this.fieldList.associateBy { it.name }
internal fun DescriptorProto.getGeneratedMapFields(): Pair<FieldDescriptorProto, FieldDescriptorProto> {
    if (!this.isGeneratedMap()) {
        error("This is not a generated message for map")
    }
    val mapKey = fieldList.getOrNull(0)?.takeIf { it.number == 1 }
    val mapValue = fieldList.getOrNull(1)?.takeIf { it.number == 2 }
    if (mapKey == null || mapValue == null)
        error("Internal error, the proto descriptor might be corrupt!!")

    return Pair(mapKey, mapValue)
}

internal fun DescriptorProto.getNormalFields() = this.fieldList.filter { !it.hasOneofIndex() }
internal fun DescriptorProto.getOneOfs() = this.fieldList
    .filter { it.hasOneofIndex() }
    .groupBy { it.oneofIndex }
    .mapKeys { this.oneofDeclList[it.key].name }

fun loadDescriptor(filePath: String): ProtobufDescriptor {
    val descriptorSet = FileDescriptorSet.parseFrom(
        File(filePath).inputStream()
    )
    val enums = mutableMapOf<String, EnumDescriptorProto>()
    val messages = mutableMapOf<String, DescriptorProto>()

    fun addEnum(enum: EnumDescriptorProto, prefix: String = "") {
        val fullName = "$prefix.${enum.name}"
        enums[fullName] = enum
    }

    fun addMessage(message: DescriptorProto, prefix: String = "") {
        val fullName = "$prefix.${message.name}"
        messages[fullName] = message
        for (type in message.nestedTypeList) {
            addMessage(type, fullName)
        }
        for (enum in message.enumTypeList) {
            addEnum(enum, fullName)
        }
    }

    for (file in descriptorSet.fileList) {
        for (message in file.messageTypeList) addMessage(message)
        for (enum in file.enumTypeList) addEnum(enum)
    }
    return ProtobufDescriptor(enums, messages)
}
