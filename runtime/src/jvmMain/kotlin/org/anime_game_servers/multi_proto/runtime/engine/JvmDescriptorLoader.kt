package org.anime_game_servers.multi_proto.runtime.engine

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import java.io.File

private fun FieldDescriptorProto.Type.toCommon(): FieldType = when (this) {
    FieldDescriptorProto.Type.TYPE_DOUBLE -> FieldType.DOUBLE
    FieldDescriptorProto.Type.TYPE_FLOAT -> FieldType.FLOAT
    FieldDescriptorProto.Type.TYPE_INT64 -> FieldType.INT64
    FieldDescriptorProto.Type.TYPE_UINT64 -> FieldType.UINT64
    FieldDescriptorProto.Type.TYPE_INT32 -> FieldType.INT32
    FieldDescriptorProto.Type.TYPE_FIXED64 -> FieldType.FIXED64
    FieldDescriptorProto.Type.TYPE_FIXED32 -> FieldType.FIXED32
    FieldDescriptorProto.Type.TYPE_BOOL -> FieldType.BOOL
    FieldDescriptorProto.Type.TYPE_STRING -> FieldType.STRING
    FieldDescriptorProto.Type.TYPE_GROUP -> FieldType.GROUP
    FieldDescriptorProto.Type.TYPE_MESSAGE -> FieldType.MESSAGE
    FieldDescriptorProto.Type.TYPE_BYTES -> FieldType.BYTES
    FieldDescriptorProto.Type.TYPE_UINT32 -> FieldType.UINT32
    FieldDescriptorProto.Type.TYPE_ENUM -> FieldType.ENUM
    FieldDescriptorProto.Type.TYPE_SFIXED32 -> FieldType.SFIXED32
    FieldDescriptorProto.Type.TYPE_SFIXED64 -> FieldType.SFIXED64
    FieldDescriptorProto.Type.TYPE_SINT32 -> FieldType.SINT32
    FieldDescriptorProto.Type.TYPE_SINT64 -> FieldType.SINT64
}

private fun FieldDescriptorProto.Label.toCommon(): FieldLabel = when (this) {
    FieldDescriptorProto.Label.LABEL_OPTIONAL -> FieldLabel.OPTIONAL
    FieldDescriptorProto.Label.LABEL_REQUIRED -> FieldLabel.REQUIRED
    FieldDescriptorProto.Label.LABEL_REPEATED -> FieldLabel.REPEATED
}

private fun FieldDescriptorProto.toCommon(): FieldDescriptor = FieldDescriptor(
    name = name,
    number = number,
    type = type.toCommon(),
    label = label.toCommon(),
    typeName = if (hasTypeName()) typeName else null,
    oneofIndex = if (hasOneofIndex()) oneofIndex else null,
)

/** Loads a binary protobuf descriptor set (.desc) into the platform-neutral model via protobuf-java. */
fun loadDescriptor(filePath: String): ProtobufDescriptor {
    val descriptorSet = FileDescriptorSet.parseFrom(File(filePath).inputStream())
    val enums = mutableMapOf<String, EnumDescriptor>()
    val messages = mutableMapOf<String, MessageDescriptor>()

    fun addEnum(enum: EnumDescriptorProto, prefix: String) {
        enums["$prefix.${enum.name}"] = EnumDescriptor(
            enum.name,
            enum.valueList.map { EnumValueDescriptor(it.name, it.number) },
        )
    }

    fun addMessage(message: DescriptorProto, prefix: String) {
        val fullName = "$prefix.${message.name}"
        messages[fullName] = MessageDescriptor(
            name = message.name,
            fields = message.fieldList.map { it.toCommon() },
            oneofNames = message.oneofDeclList.map { it.name },
            mapEntry = message.options.mapEntry,
        )
        for (nested in message.nestedTypeList) addMessage(nested, fullName)
        for (enum in message.enumTypeList) addEnum(enum, fullName)
    }

    for (file in descriptorSet.fileList) {
        for (message in file.messageTypeList) addMessage(message, "")
        for (enum in file.enumTypeList) addEnum(enum, "")
    }
    return ProtobufDescriptor(enums, messages)
}
