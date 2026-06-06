package org.anime_game_servers.multi_proto.runtime.engine

// Platform-neutral protobuf descriptor model. The JVM loader fills this from protobuf-java's
// FileDescriptorSet; the JS loader fills it from protobufjs. Mirrors the subset of
// DescriptorProtos the engine actually uses.

enum class FieldType {
    DOUBLE, FLOAT, INT64, UINT64, INT32, FIXED64, FIXED32, BOOL, STRING, GROUP,
    MESSAGE, BYTES, UINT32, ENUM, SFIXED32, SFIXED64, SINT32, SINT64
}

enum class FieldLabel { OPTIONAL, REQUIRED, REPEATED }

data class FieldDescriptor(
    val name: String,
    val number: Int,
    val type: FieldType,
    val label: FieldLabel,
    val typeName: String?,   // fully-qualified ".Pkg.Message"/".Pkg.Enum" for MESSAGE/ENUM
    val oneofIndex: Int?,    // null when the field is not part of a oneof
)

data class MessageDescriptor(
    val name: String,
    val fields: List<FieldDescriptor>,
    val oneofNames: List<String>,
    val mapEntry: Boolean,
)

data class EnumValueDescriptor(val name: String, val number: Int)

data class EnumDescriptor(val name: String, val values: List<EnumValueDescriptor>)

data class ProtobufDescriptor(
    val enums: Map<String, EnumDescriptor>,
    val messages: Map<String, MessageDescriptor>,
) {
    fun getFieldMessageType(field: FieldDescriptor): MessageDescriptor? {
        if (field.type == FieldType.MESSAGE) {
            val fullName = field.typeName ?: error("Field ${field.name} missing type name")
            return messages[fullName] ?: error("Missing message $fullName")
        }
        return null
    }

    fun getFieldEnumType(field: FieldDescriptor): EnumDescriptor? {
        if (field.type == FieldType.ENUM) {
            val fullName = field.typeName ?: error("Field ${field.name} missing type name")
            return enums[fullName] ?: error("Missing enum $fullName")
        }
        return null
    }
}

internal fun FieldDescriptor.isRepeated() = this.label == FieldLabel.REPEATED

internal fun FieldDescriptor.isZigzag() = when (type) {
    FieldType.SFIXED32, FieldType.SFIXED64, FieldType.SINT32, FieldType.SINT64 -> true
    else -> false
}

internal fun MessageDescriptor.isGeneratedMap() = this.mapEntry
internal fun MessageDescriptor.getFields() = this.fields.associateBy { it.name }
internal fun MessageDescriptor.getGeneratedMapFields(): Pair<FieldDescriptor, FieldDescriptor> {
    if (!this.isGeneratedMap()) error("This is not a generated message for map")
    val mapKey = fields.getOrNull(0)?.takeIf { it.number == 1 }
    val mapValue = fields.getOrNull(1)?.takeIf { it.number == 2 }
    if (mapKey == null || mapValue == null)
        error("Internal error, the proto descriptor might be corrupt!!")
    return Pair(mapKey, mapValue)
}

/** Wire type for a field type: 0=varint, 1=64-bit, 5=32-bit, 2=length-delimited. */
fun fieldTypeToWire(type: FieldType) = when (type) {
    FieldType.ENUM, FieldType.BOOL, FieldType.INT32, FieldType.INT64,
    FieldType.UINT32, FieldType.UINT64, FieldType.SINT32, FieldType.SINT64 -> 0
    FieldType.FLOAT, FieldType.FIXED32, FieldType.SFIXED32 -> 5
    FieldType.DOUBLE, FieldType.FIXED64, FieldType.SFIXED64 -> 1
    FieldType.STRING, FieldType.MESSAGE, FieldType.BYTES -> 2
    FieldType.GROUP -> -1
}
