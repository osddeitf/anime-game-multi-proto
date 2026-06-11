package org.anime_game_servers.multi_proto.runtime.engine

import kotlinx.serialization.json.Json
import org.anime_game_servers.multi_proto.core.registry.EnumRegistration
import org.anime_game_servers.multi_proto.core.registry.ModelRegistration
import org.anime_game_servers.multi_proto.core.registry.OneOf
import org.anime_game_servers.multi_proto.core.registry.OneOfCase
import org.anime_game_servers.multi_proto.core.registry.Property
import org.anime_game_servers.multi_proto.core.registry.PropertyKind
import org.anime_game_servers.multi_proto.core.interfaces.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.runtime.common.ProtoMappingConfig
import org.anime_game_servers.multi_proto.runtime.common.applyDecryption
import org.anime_game_servers.multi_proto.runtime.common.applyEncryption
import org.anime_game_servers.multi_proto.runtime.common.fixTypo
import org.anime_game_servers.multi_proto.runtime.common.toSnakeCase
import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation

/** Minimal logging hook so the engine stays in commonMain; the platform may wire a real logger. */
interface EngineLogger {
    fun info(message: () -> String) {}
    fun warn(message: () -> String) {}
    fun error(throwable: Throwable?, message: () -> String) {}
}

/**
 * Platform-neutral descriptor-driven proto encoder/decoder. Model structure comes from the KSP-generated
 * [models]/[enums] registers (no reflection); proto structure comes from [protoDescriptor]; bytes flow
 * through [buffers]. Ported from the original JVM/Netty/reflection implementation.
 */
class ProtoDescriptorRuntime(
    val version: String,
    val protoDescriptor: ProtobufDescriptor,
    private val buffers: ProtoBufferFactory,
    // KSP-generated model/enum metadata, keyed by simpleName (the runtime impl's register).
    private val models: Map<String, ModelRegistration>,
    private val enums: Map<String, EnumRegistration>,
) : ProtoVersionRuntime {

    var logger: EngineLogger? = null

    private var encryptionMap: Map<String, Map<String, List<EncryptionOperation>>>? = null
    private var mapping: ProtoMappingConfig? = null
    private var nt: Map<String, Set<String>> = mapOf()

    fun loadMapping(json: String) {
        val parsed: ProtoMappingConfig = Json.decodeFromString(json)
        val translation = HashMap(parsed.translation)
        val reverse = HashMap(parsed.reverse)
        for ((obf, deobf) in reverse) translation[deobf] = obf
        for ((deobf, obf) in translation) reverse[obf] = deobf
        this.mapping = parsed.copy(translation = translation, reverse = reverse)

        val nt = mutableMapOf<String, MutableSet<String>>()
        for ((ob, de) in parsed.reverse) {
            val set = nt[de] ?: mutableSetOf()
            set.add(ob)
            nt[de] = set
        }
        this.nt = nt
    }

    fun loadEncryption(json: String) {
        this.encryptionMap = Json.decodeFromString(json)
    }

    override fun getObfuscatedName(name: String): String? = mapping?.translation?.get(name)
    override fun getDeobfuscatedName(name: String): String? = mapping?.reverse?.get(name)

    fun getAlternativeNames(name: String): Set<String> {
        val names = mutableSetOf<String>()
        val normalized = setOf(fixTypo(name), name.toSnakeCase(), name)
        for (n in normalized) nt[n]?.let { names += it }
        return names + normalized
    }

    enum class MemberProtoCategory { Normal, Repeated, Map, Enum, Embedded }

    class ModelPropertyInfo(
        val category: MemberProtoCategory,
        val fieldNumber: Int,
        val dataIndex: Int,
        val modelType: FieldType,
        val fieldDescriptor: FieldDescriptor,
        val singularFieldWire: Int,
        val modelTypeName: String?,
        val oneOfCase: OneOfCase?,
        val encryption: List<EncryptionOperation>?,
        val isEnumCompat: Boolean,
        // for MAP properties: the synthetic key/value members of the generated map entry
        val mapKey: ModelPropertyInfo? = null,
        val mapValue: ModelPropertyInfo? = null,
    )

    class OneOfPropInfo(
        val dataIndex: Int,
        val oneOf: OneOf,
        val casesByName: Map<String, ModelPropertyInfo>,
    )

    class ModelInfo(
        val registration: ModelRegistration,
        val argCount: Int,
        val membersById: Map<Int, ModelPropertyInfo>,
        val writeOrder: List<ModelPropertyInfo>,
        val oneOfs: List<OneOfPropInfo>,
    )

    class EnumInfo(
        val unknown: Any,
        val forward: Map<Any, Int>,
        val backward: Map<Int, Any>,
    )

    private val modelCache = mutableMapOf<String, ModelInfo?>()
    private val enumCache = mutableMapOf<String, EnumInfo?>()

    fun lookupEnum(fullName: String): EnumDescriptor? = protoDescriptor.enums[fullName]
    fun lookupMessage(fullName: String): MessageDescriptor? = protoDescriptor.messages[fullName]

    // ---- resolution (registry + descriptor) ----

    private fun refKindOf(property: Property): PropertyKind = when (property.kind) {
        PropertyKind.LIST, PropertyKind.MAP -> property.elementKind ?: PropertyKind.DATA
        else -> property.kind
    }

    private fun memberProtoType(refKind: PropertyKind, protoField: FieldDescriptor): FieldType = when (refKind) {
        PropertyKind.BOOLEAN -> FieldType.BOOL
        PropertyKind.INT ->
            if (protoField.type == FieldType.INT32) FieldType.INT32
            else if (protoField.isZigzag()) FieldType.SINT32
            else FieldType.UINT32
        PropertyKind.LONG -> if (protoField.isZigzag()) FieldType.SINT64 else FieldType.INT64
        PropertyKind.FLOAT -> FieldType.FLOAT
        PropertyKind.DOUBLE -> FieldType.DOUBLE
        PropertyKind.STRING -> FieldType.STRING
        PropertyKind.ENUM -> FieldType.ENUM
        else -> FieldType.BYTES // bytes, nested message, etc.
    }

    private fun isEnumCompat(refKind: PropertyKind, protoField: FieldDescriptor): Boolean {
        if (refKind != PropertyKind.ENUM) return false
        return when (protoField.type) {
            FieldType.INT32, FieldType.SINT32, FieldType.UINT32,
            FieldType.INT64, FieldType.SINT64, FieldType.UINT64 -> true
            else -> false
        }
    }

    private fun matchField(name: String, protoFields: Map<String, FieldDescriptor>): FieldDescriptor? =
        getAlternativeNames(name).firstNotNullOfOrNull { protoFields[it] }

    private fun encryptionFor(modelName: String, memberName: String): List<EncryptionOperation>? {
        val alt = getAlternativeNames(memberName)
        return alt.firstNotNullOfOrNull { encryptionMap?.get(modelName)?.get(it) }
    }

    private fun buildMember(
        queue: ArrayDeque<String>,
        refs: MutableMap<String, String>,
        modelName: String,
        memberName: String,
        kind: PropertyKind,
        refKind: PropertyKind,
        modelTypeName: String?,
        dataIndex: Int,
        protoField: FieldDescriptor,
        oneOfCase: OneOfCase?,
        property: Property?,
    ): ModelPropertyInfo {
        val enumCompat = isEnumCompat(refKind, protoField)
        val category = when {
            kind == PropertyKind.LIST -> MemberProtoCategory.Repeated
            kind == PropertyKind.MAP -> MemberProtoCategory.Map
            refKind == PropertyKind.ENUM -> MemberProtoCategory.Enum
            refKind == PropertyKind.DATA -> MemberProtoCategory.Embedded
            else -> MemberProtoCategory.Normal
        }

        // map entry synthetic members
        var mapKey: ModelPropertyInfo? = null
        var mapValue: ModelPropertyInfo? = null
        if (kind == PropertyKind.MAP) {
            val entry = protoDescriptor.getFieldMessageType(protoField)?.takeIf { it.isGeneratedMap() }
                ?: error("Map field ${protoField.name} has no generated map entry")
            val (keyField, valueField) = entry.getGeneratedMapFields()
            mapKey = buildMember(
                queue, refs, modelName, "key", property?.keyKind ?: PropertyKind.STRING,
                property?.keyKind ?: PropertyKind.STRING, property?.keyModelTypeName, 0, keyField, null, null,
            )
            mapValue = buildMember(
                queue, refs, modelName, "value", property?.elementKind ?: PropertyKind.DATA,
                property?.elementKind ?: PropertyKind.DATA, property?.modelTypeName, 1, valueField, null, null,
            )
        }

        // register referenced model/enum for traversal
        if (modelTypeName != null && (refKind == PropertyKind.DATA || refKind == PropertyKind.ENUM)) {
            val protoName = if (enumCompat) {
                val mapped = mapping?.translation?.get(modelTypeName) ?: modelTypeName
                ".$mapped"
            } else {
                protoField.typeName ?: error("Field ${protoField.name} missing type name for $modelTypeName")
            }
            if (!refs.containsKey(modelTypeName)) {
                refs[modelTypeName] = protoName
                queue.add(modelTypeName)
            }
        }

        return ModelPropertyInfo(
            category = category,
            fieldNumber = protoField.number,
            dataIndex = dataIndex,
            modelType = memberProtoType(refKind, protoField),
            fieldDescriptor = protoField,
            singularFieldWire = fieldTypeToWire(protoField.type),
            modelTypeName = modelTypeName,
            oneOfCase = oneOfCase,
            encryption = encryptionFor(modelName, memberName),
            isEnumCompat = enumCompat,
            mapKey = mapKey,
            mapValue = mapValue,
        )
    }

    private fun prepareModel(
        queue: ArrayDeque<String>,
        refs: MutableMap<String, String>,
        modelName: String,
        registration: ModelRegistration,
        message: MessageDescriptor,
    ): ModelInfo {
        val protoFields = message.getFields()
        val membersById = mutableMapOf<Int, ModelPropertyInfo>()
        val writeOrder = mutableListOf<ModelPropertyInfo>()
        val oneOfs = mutableListOf<OneOfPropInfo>()

        // The property's position in registration.properties IS its data index (create/read exchange values
        // in this order); the engine carries it internally as ModelPropertyInfo/OneOfPropInfo.dataIndex.
        registration.properties.forEachIndexed { dataIndex, property ->
            try {
                if (property.kind == PropertyKind.ONEOF) {
                    val oneOf = property.oneOf ?: error("oneof property ${property.name} missing descriptor")
                    val casesByName = mutableMapOf<String, ModelPropertyInfo>()
                    for (case in oneOf.cases) {
                        val fieldName = case.caseName.replaceFirstChar { it.lowercase() }
                        val protoField = matchField(fieldName, protoFields)
                            ?: matchField(case.caseName, protoFields)
                            ?: error("No proto field for oneof case ${case.caseName}")
                        val refKind = when (protoField.type) {
                            FieldType.MESSAGE -> PropertyKind.DATA
                            FieldType.ENUM -> PropertyKind.ENUM
                            else -> PropertyKind.INT
                        }
                        val member = buildMember(
                            queue, refs, modelName, fieldName, refKind, refKind,
                            case.wrappedTypeName, dataIndex, protoField, case, null,
                        )
                        membersById[protoField.number] = member
                        casesByName[case.caseName] = member
                    }
                    oneOfs += OneOfPropInfo(dataIndex, oneOf, casesByName)
                } else {
                    val protoField = matchField(property.name, protoFields)
                        ?: error("No proto field for ${modelName}.${property.name}")
                    val refKind = refKindOf(property)
                    val member = buildMember(
                        queue, refs, modelName, property.name, property.kind, refKind,
                        property.modelTypeName, dataIndex, protoField, null, property,
                    )
                    membersById[protoField.number] = member
                    writeOrder += member
                }
            } catch (ex: Exception) {
                logger?.warn { "Model $modelName.${property.name}: ${ex.message}" }
            }
        }

        return ModelInfo(registration, registration.properties.size, membersById, writeOrder, oneOfs)
    }

    private fun prepareEnum(modelName: String, registration: EnumRegistration, enumProto: EnumDescriptor): EnumInfo {
        val protoEnums = enumProto.values.associateBy { it.name.removePrefix(enumProto.name + "_") }
        val alias = mapping?.enums?.get(modelName) ?: emptyMap()
        val forward = mutableMapOf<Any, Int>()
        val backward = mutableMapOf<Int, Any>()
        for (entry in registration.entries) {
            if (entry.isUnrecognised) continue
            val otherName = alias[entry.name] ?: entry.name
            when (val other = protoEnums[otherName]) {
                null -> {
                    logger?.warn { "Missing matching enum for model $modelName: ${entry.name}" }
                    forward[entry.value] = 0
                }
                else -> {
                    forward[entry.value] = other.number
                    backward[other.number] = entry.value
                }
            }
        }
        return EnumInfo(registration.unrecognised, forward, backward)
    }

    fun resolveModelInfo(simpleName: String): ModelInfo? {
        if (modelCache.containsKey(simpleName)) return modelCache[simpleName]

        val protoName = mapping?.translation?.get(simpleName) ?: simpleName
        val refs = mutableMapOf<String, String>()
        val queue = ArrayDeque<String>()
        refs[simpleName] = ".$protoName"
        queue.add(simpleName)

        while (queue.isNotEmpty()) {
            val name = queue.removeLast()
            val proto = refs[name]!!
            val enumReg = enums[name]
            if (enumReg != null) {
                if (!enumCache.containsKey(name)) {
                    enumCache[name] = try {
                        val enumProto = lookupEnum(proto) ?: error("Proto enum $proto missing for $name")
                        prepareEnum(name, enumReg, enumProto)
                    } catch (ex: Exception) {
                        logger?.error(ex) { "Resolve enum failed: $name" }
                        null
                    }
                }
            } else {
                if (!modelCache.containsKey(name)) {
                    modelCache[name] = try {
                        val reg = models[name] ?: error("No registration for model $name")
                        val message = lookupMessage(proto) ?: error("Proto message $proto missing for $name")
                        prepareModel(queue, refs, name, reg, message)
                    } catch (ex: Exception) {
                        logger?.error(ex) { "Resolve model failed: $name" }
                        null
                    }
                }
            }
        }
        return modelCache[simpleName]
    }

    // ---- public API ----

    fun decodeFromByteArray(simpleName: String, byteArray: ByteArray): Any? {
        val input = mapping?.mockDecode?.get(simpleName)?.hexToByteArray() ?: byteArray
        val modelInfo = resolveModelInfo(simpleName) ?: return null
        return convertProtoToModel(modelInfo, buffers.reader(input))
    }

    fun encodeToByteArray(simpleName: String, model: Any): ByteArray {
        mapping?.mockEncode?.get(simpleName)?.let { return it.hexToByteArray() }
        val modelInfo = resolveModelInfo(simpleName) ?: return byteArrayOf()
        val writer = buffers.writer()
        convertModelToProto(modelInfo, model, writer, simpleName)
        return writer.toByteArray()
    }

    // ---- decode ----

    private fun ModelPropertyInfo.decodeEnum(value: Long): Any {
        val enum = enumCache[this.modelTypeName] ?: error("decode: Missing enum for field")
        return enum.backward[value.toInt()] ?: enum.unknown
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ModelPropertyInfo.decrypt(value: T): T = encryption.applyDecryption(value!!) as T
    private fun ModelPropertyInfo.decrypt32(value: Long) = encryption.applyDecryption(value.toInt())
    private fun ModelPropertyInfo.decodeZigzag(int: Int) = if (fieldDescriptor.isZigzag()) int.asZigZag() else int
    private fun ModelPropertyInfo.decodeZigzag(int: Long) = if (fieldDescriptor.isZigzag()) int.asZigZag() else int

    private fun ModelPropertyInfo.decodeVarInt(varInt: Long): Any = when (modelType) {
        FieldType.BOOL -> decodeZigzag(decrypt(varInt)) == 1L
        FieldType.UINT32 -> decrypt32(varInt)
        FieldType.SINT32 -> decrypt32(varInt).asZigZag()
        FieldType.INT32 -> decrypt(varInt).toInt()
        FieldType.INT64, FieldType.UINT64 -> decrypt(varInt)
        FieldType.SINT64 -> decrypt(varInt).asZigZag()
        FieldType.ENUM -> decodeEnum(decrypt(varInt))
        else -> error("Unreachable: model type is invalid $modelType")
    }

    private fun ModelPropertyInfo.decodeFixed32(bits: Int): Any = when (modelType) {
        FieldType.BOOL -> decodeZigzag(decrypt(bits)) == 1
        FieldType.UINT32 -> decrypt(bits)
        FieldType.SINT32 -> decrypt(bits).asZigZag()
        FieldType.FLOAT -> decrypt(Float.fromBits(bits))
        else -> error("Unreachable: model type is invalid $modelType")
    }

    private fun ModelPropertyInfo.decodeFixed64(bits: Long): Any = when (modelType) {
        FieldType.BOOL -> decodeZigzag(decrypt(bits)) == 1L
        FieldType.UINT32 -> decrypt(bits).toInt()
        FieldType.SINT32 -> decrypt(bits).toInt().asZigZag()
        FieldType.INT64, FieldType.UINT64 -> decrypt(bits)
        FieldType.SINT64 -> decrypt(bits).asZigZag()
        FieldType.FLOAT -> Float.fromBits(decrypt(bits).toInt())
        FieldType.DOUBLE -> Double.fromBits(decrypt(bits))
        else -> error("Unreachable: model type is invalid $modelType")
    }

    private fun ModelPropertyInfo.decodeLenDelimited(bytes: ByteArray): Any? {
        if (category == MemberProtoCategory.Map) {
            return decodeMapEntry(this, bytes)
        }
        if (modelType == FieldType.STRING) return bytes.decodeToString()
        if (modelTypeName != null) {
            val info = modelCache[modelTypeName] ?: return null
            return convertProtoToModel(info, buffers.reader(bytes))
        }
        return bytes // raw bytes
    }

    private fun decodeMapEntry(field: ModelPropertyInfo, bytes: ByteArray): Pair<Any?, Any?> {
        val keyMember = field.mapKey ?: error("map missing key member")
        val valueMember = field.mapValue ?: error("map missing value member")
        var key: Any? = null
        var value: Any? = null
        val reader = buffers.reader(bytes)
        while (reader.isReadable) {
            val tag = reader.readVarint()
            val number = tag.toInt() shr 3
            val type = tag.toInt() and 0b111
            val member = when (number) { 1 -> keyMember; 2 -> valueMember; else -> null }
            val decoded = decodeScalarOrLen(member, type, reader)
            when (number) { 1 -> key = decoded; 2 -> value = decoded }
        }
        return Pair(key, value)
    }

    private fun decodeScalarOrLen(field: ModelPropertyInfo?, type: Int, reader: ProtoReader): Any? = when (type) {
        0 -> reader.readVarint().let { field?.decodeVarInt(it) }
        1 -> reader.readFixed64().let { field?.decodeFixed64(it) }
        5 -> reader.readFixed32().let { field?.decodeFixed32(it) }
        2 -> reader.readLengthDelimited().let { field?.decodeLenDelimited(it) }
        else -> null
    }

    private fun convertProtoToModel(modelInfo: ModelInfo, reader: ProtoReader): Any {
        val properties = mutableMapOf<ModelPropertyInfo, Any?>()

        @Suppress("UNCHECKED_CAST")
        fun putMerge(field: ModelPropertyInfo, value: Any) {
            val isRepeated = field.fieldDescriptor.isRepeated()
            if (isRepeated) {
                val list = properties[field] as MutableList<Any>? ?: mutableListOf()
                when (value) {
                    is List<*> -> list.addAll(value as List<Any>)
                    else -> list.add(value)
                }
                properties[field] = list
            } else if (field.fieldDescriptor.type == FieldType.MESSAGE) {
                // merge multiple occurrences by concatenating their length-delimited bytes
                val prev = properties[field] as ByteArray?
                properties[field] = if (prev == null) value as ByteArray else prev + (value as ByteArray)
            } else {
                properties[field] = value
            }
        }

        while (reader.isReadable) {
            try {
                val tag = reader.readVarint()
                if (tag.toInt().toLong() != tag) error("Invalid proto tag: $tag")
                val number = tag.toInt() shr 3
                val type = tag.toInt() and 0b111
                val field = modelInfo.membersById[number]

                val value: Any? = when (type) {
                    0 -> reader.readVarint().let { field?.decodeVarInt(it) }
                    1 -> reader.readFixed64().let { field?.decodeFixed64(it) }
                    5 -> reader.readFixed32().let { field?.decodeFixed32(it) }
                    2 -> reader.readLengthDelimited().let { bytes ->
                        when (field?.singularFieldWire) {
                            0 -> buffers.reader(bytes).readPackedVarint().map { field.decodeVarInt(it) }
                            1 -> buffers.reader(bytes).readPackedFixed64().map { field.decodeFixed64(it) }
                            5 -> buffers.reader(bytes).readPackedFixed32().map { field.decodeFixed32(it) }
                            else -> bytes
                        }
                    }
                    else -> null
                }
                if (field != null && value != null) putMerge(field, value)
            } catch (ex: IllegalArgumentException) {
                // non-fatal, continue
            }
        }

        // post-process accumulated raw values
        for ((field, value) in properties) {
            if (value is ByteArray && field.fieldDescriptor.type == FieldType.MESSAGE) {
                properties[field] = field.decodeLenDelimited(value)
            } else if (value is List<*>) {
                val items = value.mapNotNull {
                    if (field.singularFieldWire == 2) field.decodeLenDelimited(it as ByteArray) else it
                }
                properties[field] = if (field.category == MemberProtoCategory.Map) {
                    items.mapNotNull {
                        if (it !is Pair<*, *>) return@mapNotNull null
                        val k = it.first ?: return@mapNotNull null
                        val v = it.second ?: return@mapNotNull null
                        k to v
                    }.associate { it }
                } else items
            } else if (field.isEnumCompat) {
                properties[field] = when (value) {
                    is Int -> field.decodeEnum(value.toLong())
                    is Long -> field.decodeEnum(value)
                    else -> value
                }
            }
        }

        val args = arrayOfNulls<Any?>(modelInfo.argCount)
        // oneof cases and normal members
        for ((_, field) in modelInfo.membersById) {
            if (!properties.containsKey(field)) continue
            val value = properties[field] ?: continue
            args[field.dataIndex] = if (field.oneOfCase != null) field.oneOfCase.wrap(value) else value
        }
        // proto3 enum default (value 0) for missing non-repeated enum fields
        for (field in modelInfo.writeOrder) {
            if (args[field.dataIndex] != null) continue
            if (field.category == MemberProtoCategory.Enum && !field.fieldDescriptor.isRepeated()) {
                args[field.dataIndex] = field.decodeEnum(0)
            }
        }
        return modelInfo.registration.create(args)
    }

    // ---- encode ----

    private fun ModelPropertyInfo.encodeEnum(value: Any): Long {
        val enum = enumCache[modelTypeName] ?: error("encode: Enum $modelTypeName expected")
        return (enum.forward[value] ?: 0).toLong()
    }

    private fun convertModelToProto(modelInfo: ModelInfo, model: Any, writer: ProtoWriter, name: String?) {
        val values = modelInfo.registration.read(model)

        fun writeEncoded(out: ProtoWriter, member: ModelPropertyInfo, data: Any, isPacked: Boolean = false) {
            val encryption = member.encryption
            val protoType = if (member.isEnumCompat) FieldType.ENUM else member.fieldDescriptor.type

            fun tag() { if (!isPacked) out.writeFieldTag(member.fieldNumber, member.singularFieldWire) }
            fun varLong(v: Long) { val e = encryption.applyEncryption(v); if (!isPacked && e == 0L) return; tag(); out.writeVarint(e) }
            fun varInt(v: Int) { val e = encryption.applyEncryption(v); if (!isPacked && e == 0) return; tag(); out.writeVarint(e) }
            fun fixInt(v: Int) { val e = encryption.applyEncryption(v); if (!isPacked && e == 0) return; tag(); out.writeFixed32(e) }
            fun fixLong(v: Long) { val e = encryption.applyEncryption(v); if (!isPacked && e == 0L) return; tag(); out.writeFixed64(e) }
            fun fl(v: Float) { val e = Float.fromBits(encryption.applyEncryption(v.toRawBits())); if (!isPacked && e == 0f) return; tag(); out.writeFixed32(e.toRawBits()) }
            fun dbl(v: Double) { val e = Double.fromBits(encryption.applyEncryption(v.toRawBits())); if (!isPacked && e == 0.0) return; tag(); out.writeFixed64(e.toRawBits()) }

            when (protoType) {
                FieldType.BOOL -> { val e = encryption.applyEncryption(if (data as Boolean) 1 else 0); if (!isPacked && e == 0) return; tag(); out.writeVarint(e) }
                FieldType.INT32 -> varLong((data as Int).toLong())
                FieldType.UINT32 -> varInt(data as Int)
                FieldType.INT64, FieldType.UINT64 -> varLong(data as Long)
                FieldType.SINT32 -> varLong((data as Int).encodeZigZag().toLong())
                FieldType.SINT64 -> varLong((data as Long).encodeZigZag())
                FieldType.FLOAT -> fl(data as Float)
                FieldType.DOUBLE -> dbl(data as Double)
                FieldType.FIXED32 -> fixInt(data as Int)
                FieldType.FIXED64 -> fixLong(data as Long)
                FieldType.SFIXED32 -> fixInt((data as Int).encodeZigZag())
                FieldType.SFIXED64 -> fixLong((data as Long).encodeZigZag())
                FieldType.ENUM -> varLong(member.encodeEnum(data))
                FieldType.MESSAGE -> {
                    val refName = member.modelTypeName
                    val info = modelCache[refName] ?: error("encode: Model $refName expected")
                    tag()
                    out.fork()
                    convertModelToProto(info, data, out, refName)
                    out.ldelim()
                }
                FieldType.STRING -> { val s = data as String; if (s.isEmpty()) return; tag(); out.writeLengthDelimited(s.encodeToByteArray()) }
                FieldType.BYTES -> { val b = data as ByteArray; if (b.isEmpty()) return; tag(); out.writeLengthDelimited(b) }
                FieldType.GROUP -> error("Unsupported group")
            }
        }

        fun writeMember(out: ProtoWriter, member: ModelPropertyInfo, data: Any?) {
            data ?: return
            when {
                data is List<*> && data.isEmpty() -> return
                data is Map<*, *> && data.isEmpty() -> return
                member.category == MemberProtoCategory.Enum && data === enumCache[member.modelTypeName]?.unknown -> return
            }
            when (data) {
                is List<*> -> {
                    val packed = member.singularFieldWire != 2
                    if (packed) {
                        out.writeFieldTag(member.fieldNumber, 2)
                        out.fork()
                        data.forEach { it?.let { e -> writeEncoded(out, member, e, true) } }
                        out.ldelim()
                    } else {
                        data.forEach { it?.let { e -> writeEncoded(out, member, e) } }
                    }
                }
                is Map<*, *> -> {
                    val key = member.mapKey ?: error("encode: map key expected")
                    val value = member.mapValue ?: error("encode: map value expected")
                    for ((k, v) in data) {
                        out.writeFieldTag(member.fieldNumber, 2)
                        out.fork()
                        writeMember(out, key, k)
                        writeMember(out, value, v)
                        out.ldelim()
                    }
                }
                else -> writeEncoded(out, member, data)
            }
        }

        for (oneOf in modelInfo.oneOfs) {
            val wrapper = values.getOrNull(oneOf.dataIndex) ?: continue
            val caseName = oneOf.oneOf.caseNameOf(wrapper)
            val member = oneOf.casesByName[caseName] ?: continue
            writeMember(writer, member, oneOf.oneOf.unwrap(wrapper))
        }

        for (member in modelInfo.writeOrder) {
            writeMember(writer, member, values.getOrNull(member.dataIndex))
        }
    }
}

// hex helpers (commonMain): mock encode/decode fixtures stored as hex strings
private fun String.hexToByteArray(): ByteArray {
    val clean = this.trim()
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        out[i] = ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
