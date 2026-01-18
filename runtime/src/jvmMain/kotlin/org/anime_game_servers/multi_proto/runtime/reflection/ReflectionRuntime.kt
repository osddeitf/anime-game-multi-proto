package org.anime_game_servers.multi_proto.runtime.reflection

import com.google.protobuf.ByteString
import com.google.protobuf.Message
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.anime_game_servers.multi_proto.gi.ProtoVersionRuntime
import org.anime_game_servers.multi_proto.runtime.common.ProtoMappingConfig
import org.anime_game_servers.multi_proto.runtime.common.applyDecryption
import org.anime_game_servers.multi_proto.runtime.common.applyEncryption
import org.anime_game_servers.multi_proto.runtime.common.defaultMemberValue
import org.anime_game_servers.multi_proto.runtime.common.fixTypo
import org.anime_game_servers.multi_proto.runtime.common.prettyString
import org.anime_game_servers.multi_proto.runtime.common.toPascalCase
import org.anime_game_servers.multi_proto.runtime.common.toSnakeCase
import org.anime_game_servers.multi_proto.runtime.encryption.EncryptionOperation
import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.ByteArray
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.get
import kotlin.collections.iterator
import kotlin.error
import kotlin.jvm.optionals.getOrNull
import kotlin.ranges.contains
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

fun argumentType(type: Type, index: Int): Type? {
    if (type !is ParameterizedType) return null
    return type.actualTypeArguments.getOrNull(index)
}

enum class MemberProtoCategory {
    Normal,
    Repeated,
    Map,
    Enum,
    Embedded,
    Bytes,
}

class ReflectionRuntime(val version: String): ProtoVersionRuntime {
    private val GENERATED_PROTO_PREFIX = "emu.grasscutter.net.proto."
    private var encryption: Map<String, Map<String, List<EncryptionOperation>>>? = null
    private var mapping: ProtoMappingConfig? = null
    private var logger: KLogger? = KotlinLogging.logger {}
    private val lookup = MethodHandles.publicLookup()
    private var nt: Map<String, Set<String>> = mapOf()

    fun setLogger(logger: KLogger?) {
        this.logger = logger
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
            this.encryption = Json.decodeFromString(file.readText())
        }
    }

    override fun getObfuscatedName(name: String): String? {
        return mapping?.translation?.get(name)
    }

    override fun getDeobfuscatedName(name: String): String? {
        return mapping?.reverse?.get(name)
    }


    data class ModelOneOfInfo(
        val properties: Map<String, ModelPropertyInfo>,
        val constructors: Map<String, MethodHandle>,
        val protoCase: MethodHandle,
        val unwrap: MethodHandle,
        val index: Int,
    )

    data class ModelPropertyInfo(
        val protoGetter: MethodHandle,
        val protoSetter: MethodHandle,
        val index: Int,
        val category: MemberProtoCategory,
        // for nested
        val protoHas: MethodHandle?,
        // for nested, enum
        val modelType: String?,
        val defaultValue: Any?,
        val isEnumCompat: Boolean,
        val encrypt: List<EncryptionOperation>?
    )

    data class ModelInvalidMember(
        val index: Int,
        val defaultValue: Any?
    )

    data class ModelInfo(
        val modelConstruct: MethodHandle,
        val protoParse: MethodHandle,
        val protoBuilder: MethodHandle,
        val protoBuild: MethodHandle,
        val protoToByteArray: MethodHandle,
        // data class componentN functions
        val components: Map<Int, MethodHandle>,
        // messages
        val members: Map<String, ModelPropertyInfo>,
        val invalidMembers: Map<String, ModelInvalidMember>,
        // oneofs
        val oneofs: Map<String, ModelOneOfInfo>,
    )

    data class EnumInfo(
        val forward: Map<String, Enum<*>>,
        val backward: Map<String, Enum<*>>,
        val numeric: Map<Int, Enum<*>>,
        val values: Map<Enum<*>, Int>,
    )

    private val modelCache = ConcurrentHashMap<String, Optional<ModelInfo>>()
    private val enumCache = ConcurrentHashMap<String, Optional<EnumInfo>>()

    private fun getFieldProtoCategory(kType: KType): MemberProtoCategory {
        val kClassifier = kType.classifier as? KClass<*>
        return when {
            kClassifier == null -> MemberProtoCategory.Normal
            kClassifier == String::class -> MemberProtoCategory.Normal
            kClassifier == ByteArray::class -> MemberProtoCategory.Bytes
//            kClassifier == ByteString::class -> MemberProtoCategory.Normal
            kClassifier == List::class -> MemberProtoCategory.Repeated
            kClassifier == Map::class -> MemberProtoCategory.Map
            kClassifier.java.isEnum -> MemberProtoCategory.Enum
            kClassifier.javaPrimitiveType == null -> MemberProtoCategory.Embedded
            else -> MemberProtoCategory.Normal
        }
    }

    private fun getNestedModelType(type: KType): KType? {
        val nestedType = when (type.jvmErasure) {
            List::class -> type.arguments.getOrNull(0)?.type ?: error("Expect List parameter")
            Map::class -> type.arguments.getOrNull(1)?.type ?: error("Expect Map parameter")
            else -> type
        }

        val kClassifier = nestedType.classifier as? KClass<*> ?: error("Type must be a class")
        return when {
            kClassifier == String::class -> null
            kClassifier == ByteArray::class -> null
//            kClassifier.java.isEnum -> null
            kClassifier.javaPrimitiveType != null -> null
            else -> nestedType
        }
    }

    private fun getNestedProtoType(type: Type): Class<*>? {
        val javaClass = when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*>
            else -> return null
        }
        return javaClass?.let {
            when {
                javaClass == List::class.java -> {
                    argumentType(type, 0)?.let { getNestedProtoType(it) }
                }

                javaClass == Map::class.java -> {
                    argumentType(type, 1)?.let { getNestedProtoType(it) }
                }

                javaClass.typeName == "com.google.protobuf.ProtocolStringList" -> null
                javaClass.isEnum -> javaClass
                javaClass.kotlin.javaPrimitiveType == null -> {
                    javaClass.asSubclass(Message::class.java)
                }

                else -> null
            }
        }
    }

    private fun checkTypeCompatibility(kType: KType, javaType: Type): Boolean {
        val kClassifier = kType.classifier as? KClass<*> ?: return false
        val javaClass = when (javaType) {
            is Class<*> -> javaType
            is ParameterizedType -> javaType.rawType as? Class<*> ?: return false
            else -> return false
        }

        // special case (allow model Enum -> proto int)
//        if (kClassifier.java.isEnum) {
//            when (javaClass) {
//                Int::class.java, Int::class.javaPrimitiveType -> return true
//            }
//        }

        return when {
//            List::class.java.isAssignableFrom(kClassifier.javaObjectType) -> {
            kClassifier == List::class -> {
                val elementType = kType.arguments.getOrNull(0)?.type ?: return true // List<*> case
                if (elementType.jvmErasure == String::class && javaType.typeName == "com.google.protobuf.ProtocolStringList") {
                    // A special case. TODO: verify it, sometimes it doesn't seem to work
                    return true
                }
                val javaArg = argumentType(javaType, 0) ?: return false
                checkTypeCompatibility(elementType, javaArg)
            }

//            Map::class.java.isAssignableFrom(kClassifier.javaObjectType) -> {
            kClassifier == Map::class -> {
                val keyType = kType.arguments.getOrNull(0)?.type ?: return true
                val valueType = kType.arguments.getOrNull(1)?.type ?: return true
                val javaKey = argumentType(javaType, 0) ?: return false
                val javaValue = argumentType(javaType, 1) ?: return false
                checkTypeCompatibility(keyType, javaKey) &&
                        checkTypeCompatibility(valueType, javaValue)
            }

            kClassifier == ByteArray::class && javaClass.simpleName == "ByteString" ->
                true

            // is primitive
            kClassifier.javaPrimitiveType != null -> {
                when {
                    javaClass.isPrimitive -> javaClass == kClassifier.javaPrimitiveType
                    else -> javaClass == kClassifier.javaObjectType
                }
            }

            // TODO
//            kClassifier.simpleName == "ULong" -> javaClass.kotlin.simpleName == "Long"
//            kClassifier.simpleName == "UInt" -> javaClass.kotlin.simpleName == "Int"

            // simple comparison
            else -> {
                val protoClassName = mapping?.translation[kClassifier.simpleName] ?: kClassifier.simpleName
                protoClassName == javaClass.simpleName
            }
        }
    }

    private fun prepareModelOneOf(
        modelName: String,
        nestedModels: MutableMap<KClass<*>, Class<*>>,
        protoClass: Class<*>,
        builderClass: Class<*>,
        prop: KParameter,
    ): ModelOneOfInfo {
        val name = prop.name ?: error("Unreachable: Expect a class name")
//        val aliasMap = mapping?.fields[modelName] ?: emptyMap()
//        val oneofName = aliasMap[name] ?: mapping?.translation[name] ?: name

        val nestedClass = prop.type.jvmErasure
        val constructors = mutableMapOf<String, MethodHandle>()
        val properties = mutableMapOf<String, ModelPropertyInfo>()

        properties.putAll(nestedClass.sealedSubclasses.mapNotNull { it ->
            try {
                val name = it.simpleName ?: error("Unreachable: Expect class has a name")
                if (name == "Unknown${nestedClass.simpleName}") return@mapNotNull null

                val field = name.toPascalCase().replaceFirstChar { it.lowercase() }
                val ctor = it.primaryConstructor?.javaConstructor ?: error("Expect a constructor")
                val type = it.primaryConstructor?.valueParameters?.firstOrNull()?.type
                    ?: error("Unreachable: Expect a constructor with an argument")

                constructors[field] = lookup.unreflectConstructor(ctor)
                val info = prepareModelProperty(
                    modelName,
                    nestedModels,
                    protoClass,
                    builderClass,
                    fixTypo(field),
                    type,
                    prop.index,
                    true,
                )
                Pair(field, info)
            } catch (ex: IllegalStateException) {
                logger?.error { "Model $modelName: ${ex.message}" }
                null
            }
        })

        val unwrap = nestedClass.memberProperties
            .firstOrNull { it.name == "value" }
            ?.javaGetter
            ?: error("Expect a getter for 'value' property of one of class")

        return ModelOneOfInfo(
            properties,
            constructors,
            unwrap = lookup.unreflect(unwrap),
            index = prop.index,
            protoCase = lookup.unreflect(
                getOneOfCaseMethod(protoClass, name, nt[name] ?: nt[name.toSnakeCase()])
            )
        )
    }

    private fun prepareModelProperty(
        modelName: String,
        nestedModels: MutableMap<KClass<*>, Class<*>>,
        protoClass: Class<*>,
        builderClass: Class<*>,
        name: String,
        type: KType,
        constructorIndex: Int,
        isOneOf: Boolean = false
    ): ModelPropertyInfo {
        // property name
//        val aliasMap = mapping?.fields[modelName] ?: emptyMap()
//        val protoPropName = if (isOneOf) name else {    // keep oneof properties as-is
//            aliasMap[name]
//            ?: aliasMap[name.toSnakeCase()]
//            ?: mapping?.translation[name]
//            ?: mapping?.translation[name.toSnakeCase()]
//            ?: name
//        }

        // encryption
        val encryptMap = encryption?.get(modelName) ?: emptyMap()
        val encrypt = encryptMap[name] ?: encryptMap[name.toSnakeCase()]

        // enum compat mode
        var isEnumCompat = false

        // get methods
        val (getter, setter, has) = getPropertyMethods(protoClass, builderClass, name, nt[name] ?: nt[name.toSnakeCase()], type)

        // check type compatibility
        val protoPropType = getter.genericReturnType.let {
            when (it) {
                // allow mapping Enum -> Int
                Int::class.java, Int::class.javaPrimitiveType -> {
                    // TODO: this is just a workaround, should have a map, or refine models' properties' types
                    type.jvmErasure.simpleName
                        ?.let { getProtoClass(it) }
                        ?.also { isEnumCompat = true }
                        ?: it
                }
                else -> it
            }
        }
        val isCompatible = checkTypeCompatibility(type, protoPropType)
        if (!isCompatible) {
            error("Property '$name' has incompatible type: ${type.prettyString()} vs ${protoPropType.prettyString()}")
        }

        // save property along with its index
        val propertyInfo = ModelPropertyInfo(
            protoGetter = lookup.unreflect(getter),
            protoSetter = lookup.unreflect(setter),
            index = constructorIndex,
            protoHas = null,
            modelType = null,
            category = getFieldProtoCategory(type),
            defaultValue = defaultMemberValue(type.jvmErasure),
            isEnumCompat = isEnumCompat,
            encrypt = encrypt
        )

        // Add pair (model, proto) for nested processing
        val nestedType = getNestedModelType(type)
        nestedType?.let {
            val model = nestedType.jvmErasure
            if (model.java.isEnum) {
                // allow mapping from Enum -> int
                when (protoPropType) {
                    Int::class.java, Int::class.javaPrimitiveType -> return@let
                }
            }

            val proto = getNestedProtoType(protoPropType) ?: error("Unreachable: Expect nested proto class")
            val saved = nestedModels.getOrPut(model, { proto })
            // Fix this?
            if (saved != proto)
                error("Conflict mapping for ${proto.simpleName}: current ${saved.simpleName}, set ${model.simpleName}")
        }

        return when (propertyInfo.category) {
            MemberProtoCategory.Bytes,
            MemberProtoCategory.Normal -> propertyInfo
            MemberProtoCategory.Enum -> {
                val enum = nestedType?.jvmErasure ?: error("Expect an enum type")
                propertyInfo.copy(modelType = enum.simpleName)
            }
            else -> {
                // add has method for embedded type
                val hasMethod = when (propertyInfo.category) {
                    MemberProtoCategory.Embedded -> lookup.unreflect(has)
                    else -> null
                }
                val modelType = nestedType?.jvmErasure
                if (propertyInfo.category == MemberProtoCategory.Embedded) {
                    modelType ?: error("Expect nested model")
                }

                propertyInfo.copy(
                    protoHas = hasMethod,
                    modelType = modelType?.simpleName,
                )
            }
        }
    }

    private fun prepareEnumClass(enumClass: KClass<*>, protoClass: Class<*>): EnumInfo {
        val modelName = enumClass.simpleName ?: error("Model class must have a name")
        val protoName = protoClass.simpleName ?: error("Proto class must have a name")
        logger?.info { "Matching enum: $protoName" }
        val modelEnums = enumClass.java.enumConstants as Array<Enum<*>>
        val protoEnums = (protoClass.enumConstants as Array<Enum<*>>).associateBy {
            // proto enum must have the prefix, except UNRECOGNIZED
            it.name.removePrefix(protoName + "_")
        }
        val alias = mapping?.enums[modelName] ?: emptyMap()

        val lunknown = modelEnums.find { it.name == "UNRECOGNISED" } ?: error("Expect UNRECOGNISED enum")
        val runknown = protoEnums.values.find { it.name == "UNRECOGNIZED" } ?: error("Expect UNRECOGNIZED enum")
        val getNumberMethod = getEnumValueMethod(protoClass)?.let(lookup::unreflect)
        getNumberMethod ?: logger?.warn { "ProtoEnum has no method getNumber()" }

        val forward = mutableMapOf(Pair(lunknown.name, runknown))
        val backward = mutableMapOf(Pair(runknown.name, lunknown))
        val numeric = mutableMapOf<Int, Enum<*>>()
        val values = mutableMapOf<Enum<*>, Int>()

        for (enum in modelEnums) {
            val name = enum.name
            if (name == "UNRECOGNISED") continue
            val otherName = alias[name] ?: name
            when (val other = protoEnums[otherName]) {
                null -> {
                    logger?.warn { "Missing matching enum for model: $name" }
                    forward[name] = runknown
                }

                else -> {
                    forward[name] = other
                    backward[other.name] = enum
                }
            }
        }
        for ((_, enum) in protoEnums) {
            if (getNumberMethod != null && enum.name != runknown.name) {
                val num = getNumberMethod.invoke(enum) as Int
                numeric[num] = enum
                values[enum] = num
            }

            if (backward.contains(enum.name)) continue
            logger?.warn { "Missing matching enum for proto: ${enum.name}" }
            backward[enum.name] = lunknown
        }


        return EnumInfo(forward, backward, numeric, values)
    }

    private fun prepareModelClass(
        nestedModels: MutableMap<KClass<*>, Class<*>>,
        modelClass: KClass<*>,
        protoClass: Class<*>
    ): ModelInfo {
        // validate model class
        val name = modelClass.simpleName ?: error("Unreachable: Model class must have a name")
        val ctor = modelClass.primaryConstructor
            ?: error("Unreachable: Model class must have a primary constructor: $modelClass")

        // get proto class
        val builderClass = protoClass.declaredClasses.firstOrNull { it.simpleName == "Builder" }
            ?: error("Unreachable: Builder class of '${protoClass.simpleName}' not found for model")

        // check constructor
        val javaCtor = ctor.javaConstructor ?: error("Unreachable: No Java constructor found")

        val components = mutableMapOf<Int, MethodHandle>()
        val members = mutableMapOf<String, ModelPropertyInfo>()
        val invalidMembers = mutableMapOf<String, ModelInvalidMember>()
        val oneofs = mutableMapOf<String, ModelOneOfInfo>()

        val cacheComponent: (Int) -> Unit = { index ->
            val modelGetter = modelClass.memberFunctions
                .firstOrNull { it.name == "component${index + 1}" }
                ?.javaMethod
                ?: error("Unexpected: data class doesn't have componentN function")
            components[index] = lookup.unreflect(modelGetter)
        }

        // get available properties
        for (prop in ctor.parameters) {
            try {
                val propName = prop.name ?: error("Property at ${prop.index} has no name")
                // oneOf is a nested class
                if (prop.type.jvmErasure.java.enclosingClass == modelClass.java) {
                    oneofs[propName] = prepareModelOneOf(
                        name,
                        nestedModels,
                        protoClass,
                        builderClass,
                        prop,
                    )
                } else {
                    members[propName] = prepareModelProperty(
                        name,
                        nestedModels,
                        protoClass,
                        builderClass,
                        fixTypo(propName),
                        prop.type,
                        prop.index,
                    )
                }
                // when resolve success, cache the componentN function
                cacheComponent(prop.index)
            } catch (ex: Exception) {
                if (ex is IllegalStateException) {
                    logger?.error { "Model $name: ${ex.message}" }
                } else {
                    logger?.error(ex) { "Property ${prop.name ?: prop.index} reflection failed" }
                }
                prop.name?.let {
                    invalidMembers[it] = ModelInvalidMember(
                        prop.index,
                        defaultMemberValue(prop.type.jvmErasure)
                    )
                }
            }
        }

        val parseMethod = protoClass.getMethod("parseFrom", ByteArray::class.java)
        val builderMethod = protoClass.getMethod("newBuilder")
        val toByteArrayMethod = protoClass.getMethod("toByteArray")

        return ModelInfo(
            modelConstruct = lookup.unreflectConstructor(javaCtor),
            protoParse = lookup.unreflect(parseMethod),
            protoBuilder = lookup.unreflect(builderMethod),
            protoBuild = lookup.unreflect(builderClass.getMethod("build")),
            protoToByteArray = lookup.unreflect(toByteArrayMethod),
            components,
            members,
            invalidMembers,
            oneofs
        )
    }

    fun getProtoClass(name: String): Class<*>? {
        val fullName = GENERATED_PROTO_PREFIX + name + "OuterClass$$name"
        return ProtoRuntimeImpl.loadClass(version, fullName)
    }

    fun resolveModelInfo(clazz: KClass<*>): ModelInfo? {
        modelCache[clazz.simpleName]?.let { return it.getOrNull() }

        val cname = clazz.simpleName ?: error("Model class must have a name")
        val protoName = (mapping?.translation[cname] ?: cname)
        val protoClass = getProtoClass(protoName)
            ?.asSubclass(Message::class.java)
            ?: error("Proto class '$protoName' not found for model")


        val queue = ArrayDeque(listOf(clazz))
        val models = mutableListOf<ModelInfo>()
        val map = object : HashMap<KClass<*>, Class<*>>() {
            override fun put(key: KClass<*>, value: Class<*>): Class<*>? {
                val saved = this.computeIfAbsent(key, {
                    queue.add(key)
                    value
                })
                if (saved != value) {
                    // ignore conflict for now
                    logger?.error {
                        "Conflict mapping for ${key.simpleName}: current ${saved.simpleName}, set ${value.simpleName}"
                    }
                }
                return null
            }
        }
        map[clazz] = protoClass

        while (queue.isNotEmpty()) {
            val model = queue.removeLast()
            val proto = map[model] ?: error("Unreachable")
            val name = model.simpleName ?: error("Unreachable: Model class must have a name")

            if (model.java.isEnum) {
                enumCache.computeIfAbsent(name, {
                    Optional.of(prepareEnumClass(model, proto))
                })
            } else {
                val modelInfo = modelCache.computeIfAbsent(name, {
                    try {
                        val modelInfo = prepareModelClass(map, model, proto)
                        Optional.of(modelInfo)
                    } catch (ex: Exception) {
                        logger?.error(ex) { "Resolve model failed: $name" }
                        Optional.empty()
                    }
                })

                // add only the first, that would be the result
                if (models.isEmpty() && modelInfo.isPresent) {
                    models.add(modelInfo.get())
                }
            }
        }
        return models.first()
    }

    fun <T : Any> decodeFromByteArray(clazz: KClass<T>, byteArray: ByteArray): T? {
        val input = mapping?.mockDecode[clazz.simpleName]?.hexToByteArray() ?: byteArray
        val modelInfo = resolveModelInfo(clazz) ?: return null

        // cannot make use of .invokeExact here
        val proto = modelInfo.protoParse.invoke(input) as Message
        return convertProtoToModel(modelInfo, proto) as T
    }

    fun <T : Any> encodeToByteArray(clazz: KClass<*>, model: T): ByteArray {
        mapping?.mockEncode[clazz.simpleName]?.let {
            return it.hexToByteArray()
        }

        val modelInfo = resolveModelInfo(clazz) ?: return byteArrayOf()
        val builder = convertModelToProto(modelInfo, model)
        val proto = modelInfo.protoBuild.invoke(builder)
        val bytes = modelInfo.protoToByteArray.invoke(proto) as ByteArray
        return bytes
    }

    private fun convertProtoToModel(
        modelInfo: ModelInfo,
        proto: Message,
    ): Any {
        val args = MutableList<Any?>(
            modelInfo.invalidMembers.size + modelInfo.members.size + modelInfo.oneofs.size
        ) { null }

        val processModel: (ModelInfo?, Message) -> Any? = { model, value ->
            model?.let { convertProtoToModel(model, value) }
        }

        val processEnum: (EnumInfo?, Enum<*>) -> Any? = { enum, value ->
            enum?.let { enum.backward[value.name] }
        }

        val processMember: (String, ModelPropertyInfo) -> Any? = { name, member ->
            val modelType = member.modelType
            val model = modelType?.let { modelCache[modelType]?.getOrNull() }
            val enum = modelType?.let { enumCache[modelType]?.getOrNull() }
            val isModelInvalid = modelType != null && model == null && enum == null

            val value = when (member.category) {
                MemberProtoCategory.Repeated -> {
                    val list = member.protoGetter.invoke(proto) as List<*>
                    when {
                        modelType == null -> list
                        isModelInvalid -> emptyList()
                        // TODO: this may not be optimized
                        model != null -> list.map { item ->
                            processModel(model, item as Message)
                        }

                        else -> list.map { item ->
                            processEnum(enum, item as Enum<*>)
                        }
                    }
                }

                MemberProtoCategory.Map -> {
                    val map = member.protoGetter.invoke(proto) as Map<*, *>
                    when {
                        modelType == null -> map
                        isModelInvalid -> emptyMap()
                        model != null -> map.mapValues {
                            processModel(model, it.value as Message)
                        }

                        else -> map.mapValues {
                            processEnum(enum, it.value as Enum<*>)
                        }
                    }
                }

                MemberProtoCategory.Embedded -> {
                    when {
                        isModelInvalid -> null
                        else -> {
                            member.protoHas ?: error("has method not found for field $name")
                            val exist = member.protoHas.invoke(proto) as Boolean
                            when (exist) {
                                false -> null
                                else -> processModel(model, member.protoGetter(proto) as Message)
                            }
                        }
                    }
                }

                MemberProtoCategory.Enum -> {
                    when {
                        isModelInvalid -> TODO("handle enum invalid, though unlikely")
                        else -> {
                            val value = member.protoGetter.invoke(proto)// as Enum<*>
                            val name = when (value) {
                                // apply encryption before getting the value
                                is Int -> member.encrypt.applyDecryption(value).let {
                                    enum?.numeric[it]?.name ?: "UNRECOGNIZED"
                                }
                                is Enum<*> -> value.name
                                else -> error("Unreachable: should be int or an enum")
                            }
                            enum?.backward[name]
                        }
                    }
                }

                MemberProtoCategory.Bytes -> {
                    val array = member.protoGetter.invoke(proto) as ByteString
                    array.toByteArray()
                }

                MemberProtoCategory.Normal ->
                    member.protoGetter.invoke(proto)
            }

            value
        }

        for ((name, member) in modelInfo.members) {
            val value = processMember(name, member)
            args[member.index] = value?.let { member.encrypt.applyDecryption(it) }
        }

        for ((_, oneof) in modelInfo.oneofs) {
            val case = oneof.protoCase.invoke(proto) as Enum<*>
            val name = case.name.lowercase().toPascalCase().replaceFirstChar { it.lowercase() }
            val member = oneof.properties[name]
            args[oneof.index] = member?.let {
                val constr = oneof.constructors[name] ?: error("Constructor expected")
                constr.invoke(processMember(name, member))
            }
        }

        for ((name, member) in modelInfo.invalidMembers) {
            args[member.index] = member.defaultValue
        }

        return modelInfo.modelConstruct.invokeWithArguments(*args.toTypedArray())
    }

    private fun convertModelToProto(
        modelInfo: ModelInfo,
        model: Any
    ): Any {
        val builder = modelInfo.protoBuilder.invoke()

        val processMember: (ModelPropertyInfo, Any?) -> Unit = processor@{ member, value ->
            val modelType = member.modelType
            val model = modelType?.let { modelCache[modelType]?.getOrNull() }
            val enum = modelType?.let { enumCache[modelType]?.getOrNull() }
            val isModelInvalid = modelType != null && model == null && enum == null

            var converted = when (member.category) {
                MemberProtoCategory.Repeated -> {
                    val list = value as List<*>
                    val items = when {
                        isModelInvalid -> emptyList<Any>()
                        // TODO: these may not be optimized
                        model != null -> list.map { item -> convertModelToProto(model, item as Any) }
                        enum != null -> list.map { item ->
                            // TODO: how about list of enum
                            val e = item as Enum<*>
                            enum.forward[e.name]
                        }

                        else -> list
                    }
                    // Add individual items, then exit processor. TODO: not optimized
                    items.forEach { member.protoSetter.invoke(builder, it) }
                    return@processor
                }

                MemberProtoCategory.Map -> {
                    val map = value as Map<*, *>
                    when {
                        isModelInvalid -> emptyMap<Any, Any>()
                        model != null -> map.mapValues {
                            // builder is not supported, so have to call .build()
                            val value = convertModelToProto(model, it.value as Any)
                            model.protoBuild.invoke(value)
                        }

                        enum != null -> map.mapValues {
                            val e = it.value as Enum<*>
                            enum.forward[e.name]
                        }

                        else -> map
                    }
                }

                MemberProtoCategory.Embedded -> {
                    when {
                        isModelInvalid -> null
                        value == null -> null
                        model != null -> convertModelToProto(model, value)
                        else -> error("Unreachable")
                    }
                }

                MemberProtoCategory.Enum -> {
                    val e = value as Enum<*>
                    if (enum == null) error("Unreachable, should be")
                    val v = enum.forward[e.name]?.takeIf { it.name != "UNRECOGNIZED" }
                    if (member.isEnumCompat && v != null) {
                        enum.values[v]
                    }
                    else {
                        v
                    }
                }

                MemberProtoCategory.Bytes -> {
                    ByteString.copyFrom(value as ByteArray)
                }

                MemberProtoCategory.Normal -> value
            }

            converted = converted?.let {
                member.encrypt.applyEncryption(it)
            }

            // set proto
            if (converted != null) {
                member.protoSetter.invoke(builder, converted)
            }
        }

        for ((name, oneof) in modelInfo.oneofs) {
            val index = oneof.index
            val getter = modelInfo.components[index] ?: error("Missing cached component${index + 1}")
            val wrapper = getter.invoke(model)
            if (wrapper == null) continue   // skip

            // this is kind of slow
            val className = wrapper::class.simpleName ?: error("Expect class name")
            val case = className.toPascalCase().replaceFirstChar { it.lowercase() }
            val member = oneof.properties[case] ?: error("Expect member")
            val value = oneof.unwrap.invoke(wrapper)
            processMember(member, value)
        }

        for ((name, member) in modelInfo.members) {
            val index = member.index
            val getter = modelInfo.components[index] ?: error("Missing cached component${index + 1}")
            val value = getter.invoke(model)
            processMember(member, value)
        }

        return builder
    }
}
