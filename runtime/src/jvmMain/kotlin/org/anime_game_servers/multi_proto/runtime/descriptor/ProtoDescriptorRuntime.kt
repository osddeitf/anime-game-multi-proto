package org.anime_game_servers.multi_proto.runtime.descriptor

import com.google.protobuf.DescriptorProtos.*
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.slf4j.logger
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.Json
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation
import org.anime_game_servers.multi_proto.runtime.common.ProtoMappingConfig
import org.anime_game_servers.multi_proto.runtime.common.applyDecryption
import org.anime_game_servers.multi_proto.runtime.common.applyEncryption
import org.anime_game_servers.multi_proto.runtime.common.defaultMemberValue
import org.anime_game_servers.multi_proto.runtime.common.fixTypo
import org.anime_game_servers.multi_proto.runtime.common.prettyString
import org.anime_game_servers.multi_proto.runtime.common.toPascalCase
import org.anime_game_servers.multi_proto.runtime.common.toSnakeCase
import org.slf4j.Logger
import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayDeque
import kotlin.collections.get
import kotlin.collections.iterator
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

private fun isModelType(type: KType): Boolean {
    val kClassifier = type.classifier as? KClass<*> ?: error("Type must be a class")
    return when {
        kClassifier == String::class -> false
        kClassifier == ByteArray::class -> false
//            kClassifier.java.isEnum -> false
        kClassifier.javaPrimitiveType != null -> false
        else -> true
    }
}

private fun getReferencedModelTypes(type: KType): Pair<KType?, KType> {
    val valueType = when (type.classifier) {
        List::class -> type.arguments.getOrNull(0)?.type ?: error("Expect List parameter")
        Map::class -> type.arguments.getOrNull(1)?.type ?: error("Expect Map parameter")
        else -> type
    }
    val keyType = when (type.classifier) {
        Map::class -> type.arguments.getOrNull(0)?.type ?: error("Expect Map parameter")
        else -> null
    }

    return Pair(keyType, valueType)
}

private fun getMemberEquivalentProtoType(kType: KType, protoField: FieldDescriptorProto): FieldDescriptorProto.Type {
    val kClassifier = kType.classifier as KClass<*>
    return when {
        kClassifier == Boolean::class -> FieldDescriptorProto.Type.TYPE_BOOL
        kClassifier == Int::class ->
            if (protoField.type == FieldDescriptorProto.Type.TYPE_INT32) {
                FieldDescriptorProto.Type.TYPE_INT32
            }
            else if (protoField.isZigzag()) {
                FieldDescriptorProto.Type.TYPE_SINT32
            }
            else {
                FieldDescriptorProto.Type.TYPE_UINT32
            }
        kClassifier == Long::class ->
            if (protoField.isZigzag()) {
                FieldDescriptorProto.Type.TYPE_SINT64
            }
            else {
                FieldDescriptorProto.Type.TYPE_INT64
            }
        kClassifier == Float::class -> FieldDescriptorProto.Type.TYPE_FLOAT
        kClassifier == Double::class -> FieldDescriptorProto.Type.TYPE_DOUBLE
        kClassifier == String::class -> FieldDescriptorProto.Type.TYPE_STRING
        kClassifier.java.isEnum -> FieldDescriptorProto.Type.TYPE_ENUM
        kClassifier.javaPrimitiveType != null ->
            error("Type is not supported: $kClassifier")
        // bytes, packed, nested, map
        else -> FieldDescriptorProto.Type.TYPE_BYTES
    }
}

fun fieldTypeToWire(type: FieldDescriptorProto.Type) =
    when (type) {
        FieldDescriptorProto.Type.TYPE_ENUM,
        FieldDescriptorProto.Type.TYPE_BOOL,
        FieldDescriptorProto.Type.TYPE_INT32,
        FieldDescriptorProto.Type.TYPE_INT64,
        FieldDescriptorProto.Type.TYPE_UINT32,
        FieldDescriptorProto.Type.TYPE_UINT64,
        FieldDescriptorProto.Type.TYPE_SINT32,
        FieldDescriptorProto.Type.TYPE_SINT64 -> 0
        FieldDescriptorProto.Type.TYPE_FLOAT,
        FieldDescriptorProto.Type.TYPE_FIXED32,
        FieldDescriptorProto.Type.TYPE_SFIXED32 -> 5
        FieldDescriptorProto.Type.TYPE_DOUBLE,
        FieldDescriptorProto.Type.TYPE_FIXED64,
        FieldDescriptorProto.Type.TYPE_SFIXED64 -> 1
        FieldDescriptorProto.Type.TYPE_STRING,
        FieldDescriptorProto.Type.TYPE_MESSAGE,
        FieldDescriptorProto.Type.TYPE_BYTES -> 2
        else -> -1
    }


class ProtoDescriptorRuntime(val version: String, val protoDescriptor: ProtobufDescriptor): ProtoVersionRuntime {
    private var encryptionMap: Map<String, Map<String, List<EncryptionOperation>>>? = null
    private var mapping: ProtoMappingConfig? = null
    private var logger: KLogger? = KotlinLogging.logger {}
    private val lookup = MethodHandles.publicLookup()
    private var nt: Map<String, Set<String>> = mapOf()

    fun setLogger(logger: KLogger?) {
        this.logger = logger
    }

    fun setLogger(logger: Logger?) {
        this.logger = logger?.let { KotlinLogging.logger(logger) }
    }

    fun loadMappingFromJson() {
        val file = File("config/$version/mapping.json")
        if (file.exists()) {
            val mapping: ProtoMappingConfig = Json.decodeFromString(file.readText())
            val translation = HashMap(mapping.translation)
            val reverse = HashMap(mapping.reverse)
            for ((obf, deobf) in reverse) translation[deobf] = obf
            for ((deobf, obf) in translation) reverse[obf] = deobf
            this.mapping = mapping.copy(
                translation = translation,
                reverse = reverse,
            )

            // nt alternative names
            val nt = mutableMapOf<String, MutableSet<String>>()
            for ((ob, de) in mapping.reverse) {
                val set = nt[de] ?: mutableSetOf()
                set.add(ob)
                nt[de] = set
            }
            this.nt = nt
        }
    }

    fun loadEncryptionFromJson() {
        val file = File("config/$version/encryption.json")
        if (file.exists()) {
            this.encryptionMap = Json.decodeFromString(file.readText())
        }
    }

    override fun getObfuscatedName(name: String): String? {
        return mapping?.translation?.get(name)
    }

    override fun getDeobfuscatedName(name: String): String? {
        // TODO: this is inefficient
        return mapping?.reverse?.get(name)
    }

    fun getAlternativeNames(name: String): Set<String> {
        val names = mutableSetOf<String>()
        val normalized = setOf(fixTypo(name), name.toSnakeCase(), name)
        for (name in normalized) {
            // add all the possible names
            nt[name]?.let { names += it }
        }
        return names + normalized
    }

    enum class MemberProtoCategory {
        Normal,
        Repeated,
        Map,
        Enum,
        Embedded,
    }

    data class ModelOneOfInfo(
        val index: Int,
        val unwrap: MethodHandle,
    )

    data class ModelPropertyInfo(
        val category: MemberProtoCategory,
        val fieldNumber: Int,
        val dataIndex: Int,
        val defaultValue: Any?,
        // for convert from/to wire data type
        val modelType: FieldDescriptorProto.Type,
        val fieldDescriptor: FieldDescriptorProto,
        // this wire type is for the singular type (still varInt even if packed)
        val singularFieldWire: Int,
        // for nested, enum
        val modelTypeName: String?,
        // for fields in oneof
        val dataWrapperConstructor: MethodHandle?,
        // xor encryption
        val encryption: List<EncryptionOperation>?,
        // compat mode
        val isEnumCompat: Boolean,
    )

    data class ModelInvalidMember(
        val index: Int,
        val defaultValue: Any?,
        // xor encryption
        val encryption: List<EncryptionOperation>?,
    )

    data class ModelInfo(
        val modelConstruct: MethodHandle,
        // data class componentN functions
        val components: Map<Int, MethodHandle>,
        // messages
        val members: Map<String, ModelPropertyInfo>,
        val membersById: Map<Int, ModelPropertyInfo>,
        val invalidMembers: Map<String, ModelInvalidMember>,
        // oneofs
        val oneofs: Map<Int, ModelOneOfInfo>,
        val args: Int,
    )

    data class EnumInfo(
        val unknown: Enum<*>,
        val forward: Map<Enum<*>, Int>,
        val backward: Map<Int, Enum<*>>,
    )

    data class GeneratedMapEntry(
        val key: Any?,
        val value: Any?,
    )
    private val mapEntryConstruct = lazy {
        lookup.unreflectConstructor(
            GeneratedMapEntry::class.primaryConstructor?.javaConstructor
        )
    }

    private val modelCache = ConcurrentHashMap<String, Optional<ModelInfo>>()
    private val enumCache = ConcurrentHashMap<String, Optional<EnumInfo>>()

    private fun getFieldProtoCategory(kType: KType): MemberProtoCategory {
        val kClassifier = kType.classifier as? KClass<*>
        return when {
            kClassifier == null -> MemberProtoCategory.Normal
            kClassifier == Map::class -> MemberProtoCategory.Map
            kClassifier == List::class -> MemberProtoCategory.Repeated
            kClassifier.java.isEnum -> MemberProtoCategory.Enum
            kClassifier.javaPrimitiveType == null -> MemberProtoCategory.Embedded
            else -> MemberProtoCategory.Normal
        }
    }

    // compat mode for fields like retcode, enum -> int32
    private fun checkEnumCompat(
        kType: KType,
        protoField: FieldDescriptorProto,
    ): Boolean {
        val kClassifier = kType.classifier as? KClass<*> ?: return false
        if (!kClassifier.java.isEnum) return false

        return when (protoField.type) {
            FieldDescriptorProto.Type.TYPE_INT32,
            FieldDescriptorProto.Type.TYPE_SINT32,
            FieldDescriptorProto.Type.TYPE_UINT32,
            FieldDescriptorProto.Type.TYPE_INT64,
            FieldDescriptorProto.Type.TYPE_SINT64,
            FieldDescriptorProto.Type.TYPE_UINT64 -> true
            else -> false
        }
    }

    private fun checkTypeCompatibility(
        kType: KType,
        protoField: FieldDescriptorProto,
        noRepeated: Boolean = false
    ): Boolean {
        val kClassifier = kType.classifier as? KClass<*> ?: return false
        return when {
            kClassifier == List::class -> {
                if (!protoField.isRepeated() || noRepeated) return false
                val elementType = kType.arguments.getOrNull(0)?.type ?: return true // List<*> case
                checkTypeCompatibility(elementType, protoField, true)
            }

            kClassifier == Map::class -> {
                val keyType = kType.arguments.getOrNull(0)?.type ?: return true
                val valueType = kType.arguments.getOrNull(1)?.type ?: return true
                val mapEntryProto = protoDescriptor.getFieldMessageType(protoField)
                    ?.takeIf { it.isGeneratedMap() }
                    ?: return false

                val (mapKey, mapValue) = mapEntryProto.getGeneratedMapFields()
                checkTypeCompatibility(keyType, mapKey) &&
                        checkTypeCompatibility(valueType, mapValue)
            }

            kClassifier == String::class ->
                protoField.type == FieldDescriptorProto.Type.TYPE_STRING

            kClassifier == ByteArray::class ->
                protoField.type == FieldDescriptorProto.Type.TYPE_BYTES

            // is primitive
            kClassifier.javaPrimitiveType != null -> {
                when (protoField.type) {
                    FieldDescriptorProto.Type.TYPE_DOUBLE -> kClassifier == Double::class
                    FieldDescriptorProto.Type.TYPE_FLOAT -> kClassifier == Float::class
                    FieldDescriptorProto.Type.TYPE_BOOL -> kClassifier == Boolean::class
                    FieldDescriptorProto.Type.TYPE_INT32,
                    FieldDescriptorProto.Type.TYPE_SINT32,
                    FieldDescriptorProto.Type.TYPE_UINT32,
                    FieldDescriptorProto.Type.TYPE_FIXED32,
                    FieldDescriptorProto.Type.TYPE_SFIXED32 -> kClassifier == Int::class
                    FieldDescriptorProto.Type.TYPE_INT64,
                    FieldDescriptorProto.Type.TYPE_SINT64,
                    FieldDescriptorProto.Type.TYPE_UINT64,
                    FieldDescriptorProto.Type.TYPE_FIXED64,
                    FieldDescriptorProto.Type.TYPE_SFIXED64 -> kClassifier == Long::class
                    FieldDescriptorProto.Type.TYPE_STRING -> kClassifier == String::class
                    FieldDescriptorProto.Type.TYPE_ENUM -> kClassifier == Int::class    // compat for enum
                    FieldDescriptorProto.Type.TYPE_BYTES,
                    FieldDescriptorProto.Type.TYPE_MESSAGE -> false
                    FieldDescriptorProto.Type.TYPE_GROUP -> TODO("Not supported")
                }
            }

            // simple comparison for message/enum
            else -> {
                val name = kClassifier.simpleName!!
                val protoName =
                    protoDescriptor.getFieldMessageType(protoField)?.name
                    ?: protoDescriptor.getFieldEnumType(protoField)?.name
                getAlternativeNames(name).contains(protoName!!)
            }
        }
    }

    private fun prepareModelProperty(
        nestedModels: MutableMap<KClass<*>, String>,
        name: String,
        type: KType,
        constructorIndex: Int,
        protoFields: Map<String, FieldDescriptorProto>
    ): ModelPropertyInfo {

        val namesPool = getAlternativeNames(name)
        val protoField = namesPool.firstNotNullOfOrNull { protoFields[it] }
            ?: error("Reflection failed, missing matching proto field")

        val isEnumCompat = checkEnumCompat(type, protoField)
        val isCompatible = isEnumCompat || checkTypeCompatibility(type, protoField)
        if (!isCompatible) {
            error("Property '$name' has incompatible type: ${type.prettyString()} vs ${protoField.typeName}")
        }

        val (refKeyType, refType) = getReferencedModelTypes(type)
        val modelType = getMemberEquivalentProtoType(refType, protoField)

        // save property along with its index
        val propertyInfo = ModelPropertyInfo(
            category = getFieldProtoCategory(type),
            dataIndex = constructorIndex,
            fieldNumber = protoField.number,
            defaultValue = defaultMemberValue(type.jvmErasure),
            modelType = modelType,
            fieldDescriptor = protoField,
            modelTypeName = null,
            dataWrapperConstructor = null,
            singularFieldWire = fieldTypeToWire(protoField.type),
            encryption = null,
            isEnumCompat = isEnumCompat,
        )

        val refProtoType = protoDescriptor.getFieldMessageType(protoField)

        // construct model for map entry
        if (refProtoType != null && refProtoType.isGeneratedMap()) {
            refKeyType ?: error("Unreachable: type must be compatible")
            val (key, value) = refProtoType.getGeneratedMapFields()
            val protoFieldsMap = mapOf("key" to key, "value" to value)

            val members = mapOf(
                "key" to prepareModelProperty(nestedModels, "key", refKeyType, 0, protoFieldsMap),
                "value" to prepareModelProperty(nestedModels, "value", refType, 1, protoFieldsMap),
            )

            modelCache[refProtoType.name] = Optional.of(
                ModelInfo(
                    modelConstruct = mapEntryConstruct.value,
                    components = mapOf(),
                    members = members,
                    membersById = members.mapKeys { it.value.fieldNumber },
                    invalidMembers = mapOf(),
                    oneofs = mapOf(),
                    args = 2,
                )
            )
        }

        if (!isModelType(refType)) return propertyInfo

        // add mapping model -> proto
        if (isEnumCompat) {
            val name = refType.jvmErasure.simpleName!!
            val type = mapping?.translation[name] ?: name
            val fullName = ".$type"
            protoDescriptor.enums[fullName] ?: error("Missing proto enum $fullName")
            nestedModels[refType.jvmErasure] = fullName
        }
        else {
            refProtoType
                ?: protoDescriptor.getFieldEnumType(protoField)
                ?: error("Unreachable: type must be compatible")

            nestedModels[refType.jvmErasure] = if (refProtoType?.isGeneratedMap() == true) {
                // map value type
                refProtoType.getGeneratedMapFields().second.typeName
            } else {
                protoField.typeName
            }
        }

        return propertyInfo.copy(modelTypeName = refType.jvmErasure.simpleName!!)
    }

    private fun prepareEnumClass(enumClass: KClass<*>, enumProto: EnumDescriptorProto): EnumInfo {
        val modelName = enumClass.simpleName ?: error("Model class must have a name")
        logger?.info { "Matching enum: ${enumProto.name}" }

        @Suppress("UNCHECKED_CAST")
        val modelEnums = enumClass.java.enumConstants as Array<Enum<*>>
        val protoEnums = enumProto.valueList.associateBy {
            // proto enum should have the prefix, or malformed
            it.name.removePrefix(enumProto.name + "_")
        }
        val unknown = modelEnums.find { it.name == "UNRECOGNISED" } ?: error("Expect UNRECOGNISED enum")
        val alias = mapping?.enums[modelName] ?: emptyMap()
        val forward = mutableMapOf<Enum<*>, Int>()
        val backward = mutableMapOf<Int, Enum<*>>()

        for (enum in modelEnums) {
            if (enum == unknown) {
                // the same as omitting the field in protobuf
                forward[enum] = 0
                continue
            }

            val otherName = alias[enum.name] ?: enum.name
            when (val other = protoEnums[otherName]) {
                null -> {
                    logger?.warn { "Missing matching enum for model: ${enum.name}" }
                    forward[enum] = 0   // TODO: or -1???
                }

                else -> {
                    forward[enum] = other.number
                    backward[other.number] = enum
                }
            }
        }
        for ((_, enum) in protoEnums) {
            if (backward.contains(enum.number)) continue
            logger?.warn { "Missing matching enum for proto: ${enum.name}" }
        }

        return EnumInfo(unknown, forward, backward)
    }

    private fun prepareModelClass(
        nestedModels: MutableMap<KClass<*>, String>,
        modelClass: KClass<*>,
        messageProto: DescriptorProto
    ): ModelInfo {
        // validate model class
        val name = modelClass.simpleName ?: error("Unreachable: Model class must have a name")
        val ctor = modelClass.primaryConstructor ?: error("Unreachable: Model class must have a primary constructor: $modelClass")
        val javaCtor = ctor.javaConstructor ?: error("Unreachable: No Java constructor found")

        val components = mutableMapOf<Int, MethodHandle>()
        val members = mutableMapOf<String, ModelPropertyInfo>()
        val invalidMembers = mutableMapOf<String, ModelInvalidMember>()
        val oneofs = mutableMapOf<Int, ModelOneOfInfo>()
        val protoFields = messageProto.getFields()

        fun cacheComponent(index: Int) {
            val modelGetter = modelClass.memberFunctions
                .firstOrNull { it.name == "component${index + 1}" }
                ?.javaMethod
                ?: error("Unexpected: data class doesn't have componentN function")
            components[index] = lookup.unreflect(modelGetter)
        }

        fun getEncryption(memberName: String): List<EncryptionOperation>? {
            val altNames = getAlternativeNames(memberName)
            return altNames.firstNotNullOfOrNull {
                encryptionMap?.get(name)?.get(it)
            }
        }

        fun handleInvalidMember(ex: Exception, memberName: String, index: Int, type: KType) {
            if (ex is IllegalStateException) {
                logger?.error { "Model $name.${memberName}: ${ex.message}" }
            } else {
                logger?.error(ex) { "Model $name.${memberName} reflection failed" }
            }
            invalidMembers[memberName] = ModelInvalidMember(
                index,
                defaultMemberValue(type.jvmErasure),
                getEncryption(memberName),
            )
        }

        // get available properties
        fun resolveMember(memberName: String, index: Int, type: KType, wrapper: MethodHandle? = null) {
            try {
                members[memberName] = prepareModelProperty(
                    nestedModels,
                    memberName,
                    type,
                    index,
                    protoFields,
                ).copy(
                    encryption = getEncryption(memberName),
                    dataWrapperConstructor = wrapper
                )

                // cache the componentN function
                cacheComponent(index)
            } catch (ex: Exception) {
                handleInvalidMember(ex, memberName, index, type)
            }
        }

        for (prop in ctor.parameters) {
            val propName = prop.name ?: error("Unreachable: Property at ${prop.index} has no name")

            // oneOf is a nested class
            if (prop.type.jvmErasure.java.enclosingClass == modelClass.java) {
                try {
                    val wrapperClass = prop.type.jvmErasure
                    val unwrap = wrapperClass.memberProperties
                        .firstOrNull { it.name == "value" }
                        ?.javaGetter
                        ?: error("Unreachable: Expect a getter for 'value' property of one of class")

                    oneofs[prop.index] = ModelOneOfInfo(
                        prop.index,
                        lookup.unreflect(unwrap)
                    )

                    wrapperClass.sealedSubclasses.forEach {
                        val name = it.simpleName ?: error("Unreachable: Expect class has a name")
                        if (name == "Unknown${wrapperClass.simpleName}") return@forEach

                        val ctor = it.primaryConstructor?.javaConstructor ?: error("Unreachable: Expect a constructor")
                        val type = it.primaryConstructor?.valueParameters?.firstOrNull()?.type
                            ?: error("Unreachable: Expect a constructor with an argument")
                        val fieldName = name.toPascalCase().replaceFirstChar { it.lowercase() }

                        resolveMember(fieldName, prop.index, type, lookup.unreflectConstructor(ctor))
                    }
                }
                catch (ex: Exception) {
                    handleInvalidMember(ex, propName, prop.index, prop.type)
                }
            }
            else {
                resolveMember(propName, prop.index, prop.type)
            }
        }

        return ModelInfo(
            modelConstruct = lookup.unreflectConstructor(javaCtor),
            components,
            members,
            members.mapKeys { it.value.fieldNumber },
            invalidMembers,
            oneofs,
            ctor.parameters.size,
        )
    }

    fun lookupEnum(fullName: String): EnumDescriptorProto? {
        return protoDescriptor.enums[fullName]
    }

    fun lookupMessage(fullName: String): DescriptorProto? {
        return protoDescriptor.messages[fullName]
    }

    fun resolveModelInfo(clazz: KClass<*>): ModelInfo? {
        modelCache[clazz.simpleName]?.let { return it.getOrNull() }

        val cname = clazz.simpleName ?: error("Model class must have a name")
        val protoName = (mapping?.translation[cname] ?: cname)

        val queue = ArrayDeque(listOf(clazz))
        val map = object : HashMap<KClass<*>, String>() {
            // new entries will also be pushed to queue
            override fun put(key: KClass<*>, value: String): String? {
                val saved = this.computeIfAbsent(key, {
                    queue.add(key)
                    value
                })
                if (saved != value) {
                    // ignore conflict for now
                    logger?.error {
                        "Conflict mapping for ${key.simpleName}: current $saved, set $value"
                    }
                }
                return null
            }
        }
        // fully-qualified name
        map[clazz] = ".$protoName"

        while (queue.isNotEmpty()) {
            val model = queue.removeLast()
            val protoName = map[model]!!
            val name = model.simpleName!!

            if (model.java.isEnum) {
                enumCache.computeIfAbsent(name, {
                    try {
                        val enum = lookupEnum(protoName) ?: error("Proto enum $protoName is missing for model")
                        Optional.of(prepareEnumClass(model, enum))
                    } catch (ex: Exception) {
                        logger?.error(ex) { "Resolve model failed: $name" }
                        Optional.empty()
                    }
                })
            } else {
                modelCache.computeIfAbsent(name, {
                    try {
                        val message = lookupMessage(protoName) ?: error("Proto message $protoName is missing for model")
                        val modelInfo = prepareModelClass(map, model, message)
                        Optional.of(modelInfo)
                    } catch (ex: Exception) {
                        logger?.error(ex) { "Resolve model failed: $name" }
                        Optional.empty()
                    }
                })
            }
        }
        return modelCache[cname]?.getOrNull()
    }

    fun <T : Any> decodeFromByteArray(clazz: KClass<T>, byteArray: ByteArray): T? {
        val input = mapping?.mockDecode[clazz.simpleName]?.hexToByteArray() ?: byteArray
        val modelInfo = resolveModelInfo(clazz) ?: return null
        @Suppress("UNCHECKED_CAST")
        return convertProtoToModel(modelInfo, Unpooled.wrappedBuffer(input)) as T
    }

    fun <T : Any> encodeToByteArray(clazz: KClass<*>, model: T): ByteArray {
        mapping?.mockEncode[clazz.simpleName]?.let {
            return it.hexToByteArray()
        }

        val modelInfo = resolveModelInfo(clazz) ?: return byteArrayOf()
        val buf = ByteBufAllocator.DEFAULT.ioBuffer()
        convertModelToProto(modelInfo, model, buf, clazz.simpleName)

        val bytes = buf.asByteArray()
        buf.release()
        return bytes
    }

    private fun ModelPropertyInfo.decodeEnum(value: Long): Enum<*> {
        val enum = enumCache[this.modelTypeName]?.getOrNull() ?: error("decode: Missing enum for field")
        return enum.backward[value.toInt()] ?: enum.unknown
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ModelPropertyInfo.decrypt(value: T): T =
        encryption.applyDecryption(value!!) as T

    private fun ModelPropertyInfo.decrypt32(value: Long) =
        encryption.applyEncryption(value.toInt())

    private fun ModelPropertyInfo.decodeZigzag(int: Int) =
        if (fieldDescriptor.isZigzag()) int.asZigZag() else int

    private fun ModelPropertyInfo.decodeZigzag(int: Long) =
        if (fieldDescriptor.isZigzag()) int.asZigZag() else int

    // truncate -> decrypt -> zigzag
    private fun ModelPropertyInfo.decodeVarInt(varInt: Long): Any {
        return when (modelType) {
            FieldDescriptorProto.Type.TYPE_BOOL -> decodeZigzag(decrypt(varInt)) == 1L
            FieldDescriptorProto.Type.TYPE_UINT32 -> decrypt32(varInt)
            FieldDescriptorProto.Type.TYPE_SINT32 -> decrypt32(varInt).asZigZag()
            FieldDescriptorProto.Type.TYPE_INT32 -> decrypt(varInt).toInt()
            FieldDescriptorProto.Type.TYPE_INT64,
            FieldDescriptorProto.Type.TYPE_UINT64 -> decrypt(varInt)
            FieldDescriptorProto.Type.TYPE_SINT64 -> decrypt(varInt).asZigZag()
            FieldDescriptorProto.Type.TYPE_ENUM -> decodeEnum(decrypt(varInt))
            else -> error("Unreachable: model type is invalid $modelType")
        }
    }
    private fun ModelPropertyInfo.decodeFixed32(fixed: ByteBuf): Any {
        val bits = fixed.asInt32()
        return when (modelType) {
            FieldDescriptorProto.Type.TYPE_BOOL -> decodeZigzag(decrypt(bits)) == 1
            FieldDescriptorProto.Type.TYPE_UINT32 -> decrypt(bits)
            FieldDescriptorProto.Type.TYPE_SINT32 -> decrypt(bits).asZigZag()
            FieldDescriptorProto.Type.TYPE_FLOAT -> decrypt(Float.fromBits(bits))
            else -> error("Unreachable: model type is invalid $modelType")
        }
    }
    private fun ModelPropertyInfo.decodeFixed64(fixed: ByteBuf): Any {
        val bits = fixed.asInt64()
        return when (modelType) {
            FieldDescriptorProto.Type.TYPE_BOOL -> decodeZigzag(decrypt(bits)) == 1L
            FieldDescriptorProto.Type.TYPE_UINT32 -> decrypt(bits).toInt()
            FieldDescriptorProto.Type.TYPE_SINT32 -> decrypt(bits).toInt().asZigZag()
            FieldDescriptorProto.Type.TYPE_INT64,
            FieldDescriptorProto.Type.TYPE_UINT64 -> decrypt(bits)
            FieldDescriptorProto.Type.TYPE_SINT64 -> decrypt(bits).asZigZag()
            FieldDescriptorProto.Type.TYPE_FLOAT -> Float.fromBits(decrypt(bits).toInt())
            FieldDescriptorProto.Type.TYPE_DOUBLE -> Double.fromBits(decrypt(bits))
            else -> error("Unreachable: model type is invalid $modelType")
        }
    }
    private fun ModelPropertyInfo.decodeLenDelimited(bytes: ByteBuf): Any? {
        // is a map
        if (category == MemberProtoCategory.Map) {
            val descriptor = protoDescriptor.messages[fieldDescriptor.typeName]
                ?.takeIf { it.isGeneratedMap() }
                ?: error("Unreachable: missing proto description of generated message for map")

            val model = modelCache[descriptor.name]?.getOrNull() ?: error("Unreachable")
            return convertProtoToModel(model, bytes) as GeneratedMapEntry
        }

        // string
        if (modelType == FieldDescriptorProto.Type.TYPE_STRING) {
            return bytes.asString()
        }

        // reference to other message
        if (modelTypeName != null) {
            return modelCache[modelTypeName]?.getOrNull()?.let {
                convertProtoToModel(it, bytes)
            }
        }

        // raw bytes
        return bytes.asByteArray()
    }

    private fun convertProtoToModel(
        modelInfo: ModelInfo,
        bytes: ByteBuf
    ): Any {
        val properties = mutableMapOf<ModelPropertyInfo, Any?>()

        @Suppress("UNCHECKED_CAST")
        fun putMerge(field: ModelPropertyInfo, value: Any) {
            val isRepeated = field.fieldDescriptor.isRepeated()
            properties[field] = if (isRepeated) {
                val list = properties[field] as MutableList<Any>? ?: mutableListOf()
                when {
                    value is List<*> -> list.addAll(value as List<Any>)
                    else -> list.add(value)
                }
                list
            }
            else {
                if (field.fieldDescriptor.type == FieldDescriptorProto.Type.TYPE_MESSAGE) {
                    val buffer = properties[field] as CompositeByteBuf? ?: Unpooled.compositeBuffer()
                    buffer.addComponent(true, value as ByteBuf)
                }
                else {
                    value
                }
            }
        }

        while (bytes.isReadable) {
            try {
                val tag = bytes.readVarInt()
                if (tag.toInt().toLong() != tag) {
                    // tag must be 32-bit number, no more
                    error("Invalid proto tag: $tag")
                }

                val number = tag.toInt() shr 3
                val type = tag.toInt() and 0b111
                val field = modelInfo.membersById[number]

                val value: Any? = when (type) {
                    0 -> bytes.readVarInt().let {
                        field?.decodeVarInt(it)
                    }
                    1 -> bytes.readFixed64().let {
                        field?.decodeFixed64(it)
                    }
                    5 -> bytes.readFixed32().let {
                        field?.decodeFixed32(it)
                    }
                    2 -> bytes.readLenDelimited().let { bytes ->
                        // decode as packed types. TODO: what if the field is singular
                        when (field?.singularFieldWire) {
                            0 -> bytes.asPackedVarInt().map { field.decodeVarInt(it) }
                            1 -> bytes.asPackedFixed64().map { field.decodeFixed64(it) }
                            5 -> bytes.asPackedFixed32().map { field.decodeFixed32(it) }
                            else -> bytes
                        }
                    }
                    else -> null
                }
                if (field != null && value != null) {
                    putMerge(field, value)
                }
            }
            catch (ex: IllegalArgumentException) {
                // still continue when it is a non-fatal exception
            }
        }

        // process after concatenated
        for ((field, value) in properties) {

            if (value is ByteBuf) {
                properties[field] = field.decodeLenDelimited(value)
            } else if (value is List<*>) {
                val items = value.mapNotNull {
                    if (field.singularFieldWire == 2)
                        field.decodeLenDelimited(it as ByteBuf)
                    else it
                }

                properties[field] = if (field.category == MemberProtoCategory.Map) {
                    items.mapNotNull {
                        if (it !is GeneratedMapEntry) return@mapNotNull null
                        Pair(
                            it.key ?: return@mapNotNull null,
                            it.value ?: return@mapNotNull null
                        )
                    }
                        .associate { it }
                } else {
                    items
                }
            } else if (field.isEnumCompat) {
                properties[field] = when (value) {
                    is Int -> field.decodeEnum(value.toLong())
                    is Long -> field.decodeEnum(value)
                    else -> value   // keep as-is, model constructor will throw
                }
            }
        }

        val args = MutableList<Any?>(modelInfo.args) { null }
        for (field in modelInfo.members.values) {
            (properties[field] ?: field.defaultValue)
                ?.let {
                    // if value is non-null, wrap it if necessary (oneof case)
                    field.dataWrapperConstructor?.invoke(it) ?: it
                }
                ?.let { args[field.dataIndex] = it }
        }

        for (member in modelInfo.invalidMembers.values) {
            // ignore members wrapped by oneof
            if (modelInfo.oneofs.contains(member.index)) continue
            // no decrypt needed
            member.defaultValue?.let { args[member.index] = it }
        }

        return modelInfo.modelConstruct.invokeWithArguments(args)
    }

    private fun ModelPropertyInfo.encodeEnum(value: Enum<*>): Long {
        val name = modelTypeName!!
        val enum = enumCache[name]?.getOrNull() ?: error("encode: Enum $name expected")
        val numeric = enum.forward[value] ?: 0
        return numeric.toLong()
    }

    private fun convertModelToProto(
        modelInfo: ModelInfo,
        model: Any,
        bytes: ByteBuf,
        name: String?
    ) {
        fun writeEncoded(bytes: ByteBuf, member: ModelPropertyInfo, data: Any, isPacked: Boolean = false) {
            val encryption = member.encryption
            val protoType = if (member.isEnumCompat && data is Enum<*>) {
                FieldDescriptorProto.Type.TYPE_ENUM
            }
            else {
                member.fieldDescriptor.type
            }

            // NOTE: these functions seem repetitive, but are optimized as bytecodes
            fun writeTag() {
                if (!isPacked) bytes.writeFieldTag(member.fieldNumber, member.singularFieldWire)
            }
            fun writeBoolean(data: Boolean) {
                val value = encryption.applyEncryption(data)
                if (!isPacked && !value) return
                writeTag()
                bytes.writeBoolean(value)
            }
            fun writeVarInt(data: Int) {
                val value = encryption.applyEncryption(data)
                if (!isPacked && value == 0) return
                writeTag()
                bytes.writeVarInt(value)
            }
            fun writeVarInt(data: Long) {
                val value = encryption.applyEncryption(data)
                if (!isPacked && value == 0L) return
                writeTag()
                bytes.writeVarInt(value)
            }
            fun writeFixedInt(data: Int) {
                val value = encryption.applyEncryption(data)
                if (!isPacked && value == 0) return
                writeTag()
                bytes.writeIntLE(value)
            }
            fun writeFixedInt(data: Long) {
                val value = encryption.applyEncryption(data)
                if (!isPacked && value == 0L) return
                writeTag()
                bytes.writeLongLE(value)
            }
            fun writeFloat(data: Float) {
                val value = Float.fromBits(encryption.applyEncryption(data.toRawBits()))
                if (!isPacked && value == 0f) return
                writeTag()
                bytes.writeFloatLE(value)
            }
            fun writeDouble(data: Double) {
                val value = Double.fromBits(encryption.applyEncryption(data.toRawBits()))
                if (!isPacked && value == 0.0) return
                writeTag()
                bytes.writeDoubleLE(value)
            }

            when (protoType) {
                FieldDescriptorProto.Type.TYPE_BOOL -> writeBoolean(data as Boolean)
                FieldDescriptorProto.Type.TYPE_INT32 -> writeVarInt((data as Int).toLong())
                FieldDescriptorProto.Type.TYPE_UINT32 -> writeVarInt(data as Int)
                FieldDescriptorProto.Type.TYPE_INT64,
                FieldDescriptorProto.Type.TYPE_UINT64 -> writeVarInt(data as Long)
                FieldDescriptorProto.Type.TYPE_SINT32 -> writeVarInt((data as Int).encodeZigZag())
                FieldDescriptorProto.Type.TYPE_SINT64 -> writeVarInt((data as Long).encodeZigZag())
                FieldDescriptorProto.Type.TYPE_FLOAT -> writeFloat(data as Float)
                FieldDescriptorProto.Type.TYPE_DOUBLE -> writeDouble(data as Double)
                FieldDescriptorProto.Type.TYPE_FIXED32 -> writeFixedInt(data as Int)
                FieldDescriptorProto.Type.TYPE_FIXED64 -> writeFixedInt(data as Long)
                FieldDescriptorProto.Type.TYPE_SFIXED32 -> writeFixedInt((data as Int).encodeZigZag())
                FieldDescriptorProto.Type.TYPE_SFIXED64 -> writeFixedInt((data as Long).encodeZigZag())
                FieldDescriptorProto.Type.TYPE_ENUM -> writeVarInt(member.encodeEnum(data as Enum<*>))
                FieldDescriptorProto.Type.TYPE_MESSAGE -> {
                    val name = member.modelTypeName
                    val model = modelCache[name]?.getOrNull() ?: error("encode: Model $name expected")
                    val embedded = ByteBufAllocator.DEFAULT.ioBuffer()
                    try {
                        convertModelToProto(model, data, embedded, name)
                        writeTag()
                        bytes.writeVarInt(embedded.readableBytes())  // read index is always 0, and must be so
                        bytes.writeBytes(embedded)
                    }
                    finally {
                        embedded.release()
                    }
                }
                FieldDescriptorProto.Type.TYPE_STRING -> {
                    data as? String ?: error("encode: String expected")
                    if (data.isEmpty()) return
                    val len = ByteBufUtil.utf8Bytes(data)
                    writeTag()
                    bytes.writeVarInt(len)
                    bytes.writeCharSequence(data, Charsets.UTF_8)
                }
                FieldDescriptorProto.Type.TYPE_BYTES -> {
                    data as? ByteArray ?: error("encode: ByteArray expected")
                    if (data.isEmpty()) return
                    writeTag()
                    bytes.writeVarInt(data.size)
                    bytes.writeBytes(data)
                }
                FieldDescriptorProto.Type.TYPE_GROUP -> TODO("Unsupported")
            }
        }

        fun writeMemberWithFailsafe(bytes: ByteBuf, member: ModelPropertyInfo, data: Any?) {
            data ?: return
            var checkpoint = bytes.writerIndex()
            try {
                when (data) {
                    member.defaultValue if member.encryption == null -> return
                    is List<*> if data.isEmpty() -> return
                    is Map<*, *> if data.isEmpty() -> return
                    is Enum<*> if data.name == "UNRECOGNISED" -> return
                }

                if (data is List<*>) {
                    val isPacked = member.singularFieldWire != 2
                    if (isPacked) {
                        bytes.writeFieldTag(member.fieldNumber, 2)
                        val packed = ByteBufAllocator.DEFAULT.ioBuffer()
                        try {
                            data.forEach {
                                it ?: return@forEach
                                writeEncoded(packed, member, it, true)
                            }
                            bytes.writeVarInt(packed.readableBytes())
                            bytes.writeBytes(packed)
                        }
                        finally {
                            packed.release()
                        }
                    }
                    else {
                        data.forEach {
                            it ?: return@forEach
                            writeEncoded(bytes, member, it)
                            checkpoint = bytes.writerIndex()    // save new checkpoint
                        }
                    }
                }
                else if (data is Map<*, *>) {
                    val name = protoDescriptor.getFieldMessageType(member.fieldDescriptor)?.name
                    val model = modelCache[name]?.getOrNull() ?: error("encode: map Model expected")
                    val key = model.membersById[1]
                    val value = model.membersById[2]
                    if (key == null || value == null)
                        error("encode: key value expected")

                    for ((k, v) in data) {
                        bytes.writeFieldTag(member.fieldNumber, 2)
                        val mapEntry = ByteBufAllocator.DEFAULT.ioBuffer()
                        try {
                            writeMemberWithFailsafe(mapEntry, key, k)
                            writeMemberWithFailsafe(mapEntry, value, v)
                            bytes.writeVarInt(mapEntry.readableBytes())
                            bytes.writeBytes(mapEntry)
                            checkpoint = bytes.writerIndex()    // save new checkpoint
                        }
                        finally {
                            mapEntry.release()
                        }
                    }
                }
                else {
                    writeEncoded(bytes, member, data)
                }
            }
            catch (ex: Exception) {
                // TODO: maybe handle IO exception differently
                logger?.error(ex) { "Encode protobuf failed for $name" }
                bytes.writerIndex(checkpoint)
            }
        }

        for (oneof in modelInfo.oneofs.values) {
            val index = oneof.index
            val getter = modelInfo.components[index] ?: error("Model $name missing cached component${index + 1}")
            val wrapper = getter.invoke(model) ?: continue  // skip null
            val wrapperName = wrapper::class.simpleName ?: error("Expect class name")
            val fieldName = wrapperName.toPascalCase().replaceFirstChar { it.lowercase() }
            val member = modelInfo.members[fieldName]  // TODO: some member maybe invalid?

            if (member != null) {
                val value = oneof.unwrap.invoke(wrapper)
                writeMemberWithFailsafe(bytes, member, value)
            }
        }

        for (member in modelInfo.members.values) {
            // ignore member inside oneof
            if (member.dataWrapperConstructor != null) continue

            val index = member.dataIndex
            val getter = modelInfo.components[index] ?: error("Missing cached component${index + 1}")
            val value = getter.invoke(model)
            writeMemberWithFailsafe(bytes, member, value)
        }
    }
}
