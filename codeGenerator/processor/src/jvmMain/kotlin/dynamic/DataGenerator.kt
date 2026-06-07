package dynamic

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSType
import org.anime_game_servers.multi_proto.core.interfaces.ProtoModel
import java.io.OutputStream

open class DataGenerator(
    logger: KSPLogger,
    resolver: Resolver,
    classInfoCache: MutableMap<KSType, ClassInfo>
) : BaseGenerator(logger, resolver, classInfoCache) {
    override fun addImports(file: OutputStream, classInfo: ClassInfo) {
        super.addImports(file, classInfo)
        file += "import ${ProtoModel::class.java.canonicalName}\n" +
                "import $PROTO_ONE_OF_ANNOTATION\n"
    }

    override fun addConstructor(file: OutputStream, classInfo: ClassInfo) {
        super.addConstructor(file, classInfo)
        // data class can not be empty, so we use an empty normal class instead
        if (classInfo.modelMembers.isEmpty()) {
            file += "@kotlin.js.JsExport\n"
            file += "class ${classInfo.name} : ${getImplementedModels(classInfo)} {\n"
            return
        }
        file += "@kotlin.js.JsExport\n"
        file += "data class ${classInfo.name} @JvmOverloads constructor (\n"
        classInfo.modelMembers.forEach {
            file.id(4) += "var `${it.value.name.getVariableName()}`: ${getTypeString(it)} = ${
                it.value.getDefaultValueForType(
                    classInfo.oneOfs[it.key]
                )
            },\n"
        }
        file += ") : ${getImplementedModels(classInfo)}{\n"
    }

    open fun getImplementedModels(classInfo: ClassInfo): String {
        return ProtoModel::class.java.simpleName
    }

    override fun addBody(file: OutputStream, classInfo: ClassInfo) {
        classInfo.oneOfs.values.forEach { oneOfData ->
            logger.info("OneOf: $oneOfData")

            // Emit the cases as data classes so oneof values compare structurally; plain classes fall back to
            // identity equality, which breaks equals()/assertEquals on any model containing a oneof. `value` is
            // an abstract property each case overrides; UnknownModel is an object so the (vestigial, never
            // instantiated) unknown case is still a legal data class with a single defaulted parameter.
            file.id(4) += "sealed class ${oneOfData.wrapperName}<T> {\n"
            file.id(8) += "abstract val value: T\n"
            file.id(8) += "object UnknownModel\n"
            file.id(8) += "data class ${oneOfData.unknownName}(override val value: UnknownModel = UnknownModel) : ${oneOfData.wrapperName}<UnknownModel>()\n"
            oneOfData.oneOfClassMap.forEach inner@{ (name, oneOfClass) ->
                val model = classInfoCache[oneOfClass.kSType] ?: return@inner
                val className = name.getClassName()
                file.id(8) += "data class ${className}(override val value:${model.packageName}.${model.name}) : ${oneOfData.wrapperName}<${model.packageName}.${model.name}>()\n"
            }
            file.id(4) += "}\n"
        }
    }

    override fun addEncodeMethods(file: OutputStream, classInfo: ClassInfo) {
        file.id(4) += "override fun encodeToByteArray(version:$VERSION_ENUM_CLASS_NAME) : ByteArray? {\n"
        file.id(8) += "return ProtoModelRegistry.service.encodeToByteArray(\n"
        file.id(12) += "version,\n"
        file.id(12) += "${classInfo.name}::class,\n"
        file.id(12) += "this,\n"
        file.id(8) += ")\n"
        file.id(4) += "}\n"
    }

    override fun addCompanionObject(file: OutputStream, classInfo: ClassInfo) {
        file.id(4) += "companion object {\n"
        file.id(8) += "@JvmStatic\n"
        file.id(8) += "fun decodeFromByteArray(data: ByteArray, version:$VERSION_ENUM_CLASS_NAME): ${classInfo.name} {\n"
        file.id(12) += "val obj: ${classInfo.name}? = ProtoModelRegistry.service.decodeFromByteArray(\n"
        file.id(16) += "version,\n"
        file.id(16) += "${classInfo.name}::class,\n"
        file.id(16) += "data,\n"
        file.id(12) += ")\n"
        file.id(12) += "return obj ?: ${classInfo.name}()\n"
        file.id(8) += "}\n"

        // TODO: remove this function
        file.id(8) += "@JvmStatic\n"
        file.id(8) += "fun parseBy(data: ByteArray, version:$VERSION_ENUM_CLASS_NAME) =\n"
        file.id(12) += "decodeFromByteArray(data, version)\n"
        file.id(4) += "}\n"
    }

    override fun addClosure(file: OutputStream, classInfo: ClassInfo) {
        file += "}"
    }

    // ---- registry (replaces JVM reflection on JS) ----

    override fun addRegistration(file: OutputStream, classInfo: ClassInfo) {
        val name = classInfo.name
        val members = classInfo.modelMembers.entries.toList()

        file += "\nobject ${name}_Registration : $REGISTRY_PACKAGE.ModelRegistration {\n"
        file.id(4) += "override val simpleName = \"$name\"\n"

        file.id(4) += "override val properties = listOf<$REGISTRY_PACKAGE.Property>(\n"
        members.forEachIndexed { index, entry ->
            file.id(8) += emitProperty(classInfo, entry, index) + ",\n"
        }
        file.id(4) += ")\n"

        file.id(4) += "override fun create(values: Array<Any?>): Any {\n"
        file.id(8) += "val m = $name()\n"
        members.forEachIndexed { index, entry ->
            val varName = entry.value.name.getVariableName()
            file.id(8) += "(values[$index] as ${castType(classInfo, entry)}?)?.let { m.`$varName` = it }\n"
        }
        file.id(8) += "return m\n"
        file.id(4) += "}\n"

        file.id(4) += "override fun read(model: Any): Array<Any?> {\n"
        file.id(8) += "model as $name\n"
        if (members.isEmpty()) {
            file.id(8) += "return arrayOf()\n"
        } else {
            file.id(8) += "return arrayOf(\n"
            members.forEach { entry ->
                file.id(12) += "model.`${entry.value.name.getVariableName()}`,\n"
            }
            file.id(8) += ")\n"
        }
        file.id(4) += "}\n"

        file += "}\n"
    }

    private fun emitProperty(classInfo: ClassInfo, entry: Map.Entry<String, MemberInfo>, index: Int): String {
        val member = entry.value
        val type = member.type
        val propName = member.name.getVariableName()
        val altNames = member.names.filter { it != member.name }
        val altStr = "listOf(" + altNames.joinToString(", ") { "\"$it\"" } + ")"
        val sb = StringBuilder()
        sb.append("$REGISTRY_PACKAGE.Property(name = \"$propName\", altNames = $altStr, dataIndex = $index, ")
        when (Type.byType(type, this)) {
            Type.COLLECTION -> {
                val el = type.arguments.firstOrNull()?.type?.resolve()
                sb.append("kind = $REGISTRY_PACKAGE.PropertyKind.LIST")
                if (el != null) {
                    sb.append(", elementKind = $REGISTRY_PACKAGE.PropertyKind.${kindOf(el)}")
                    refSimpleName(el)?.let { sb.append(", modelTypeName = \"$it\"") }
                }
            }
            Type.MAP -> {
                val k = type.arguments.getOrNull(0)?.type?.resolve()
                val v = type.arguments.getOrNull(1)?.type?.resolve()
                sb.append("kind = $REGISTRY_PACKAGE.PropertyKind.MAP")
                if (k != null) {
                    sb.append(", keyKind = $REGISTRY_PACKAGE.PropertyKind.${kindOf(k)}")
                    refSimpleName(k)?.let { sb.append(", keyModelTypeName = \"$it\"") }
                }
                if (v != null) {
                    sb.append(", elementKind = $REGISTRY_PACKAGE.PropertyKind.${kindOf(v)}")
                    refSimpleName(v)?.let { sb.append(", modelTypeName = \"$it\"") }
                }
            }
            Type.ONE_OF -> {
                sb.append("kind = $REGISTRY_PACKAGE.PropertyKind.ONEOF")
                classInfo.oneOfs[entry.key]?.let { sb.append(", oneOf = ${emitOneOf(classInfo, it)}") }
            }
            else -> {
                sb.append("kind = $REGISTRY_PACKAGE.PropertyKind.${kindOf(type)}")
                refSimpleName(type)?.let { sb.append(", modelTypeName = \"$it\"") }
            }
        }
        sb.append(")")
        return sb.toString()
    }

    private fun emitOneOf(classInfo: ClassInfo, oneOf: OneOfData): String {
        val wrapper = "${classInfo.name}.${oneOf.wrapperName}"
        val cases = oneOf.oneOfClassMap.entries.mapNotNull { (nm, ot) ->
            val model = classInfoCache[ot.kSType] ?: return@mapNotNull null
            val wrapped = "${model.packageName}.${model.name}"
            val caseClass = nm.getClassName()
            "$REGISTRY_PACKAGE.OneOfCase(caseName = \"$caseClass\", wrappedTypeName = \"${model.name}\", " +
                "wrap = { v -> $wrapper.$caseClass(v as $wrapped) })"
        }
        return "$REGISTRY_PACKAGE.OneOf(cases = listOf(${cases.joinToString(", ")}), " +
            "caseNameOf = { w -> w::class.simpleName!! }, " +
            "unwrap = { w -> (w as $wrapper<*>).value })"
    }

    private fun castType(classInfo: ClassInfo, entry: Map.Entry<String, MemberInfo>): String {
        return if (Type.byType(entry.value.type, this) == Type.ONE_OF) {
            val oneOf = classInfo.oneOfs[entry.key]
            "${classInfo.name}.${oneOf?.wrapperName ?: entry.value.name.getClassName()}<*>"
        } else {
            getTypeString(entry).removeSuffix("?")
        }
    }

    private fun kindOf(type: KSType): String = when (Type.byType(type, this)) {
        Type.SIMPLE -> when (type.declaration.simpleName.asString()) {
            "Long" -> "LONG"
            "Float" -> "FLOAT"
            "Double" -> "DOUBLE"
            "Boolean" -> "BOOLEAN"
            "String" -> "STRING"
            else -> "INT"
        }
        Type.BYTE_ARRAY -> "BYTE_ARRAY"
        Type.COLLECTION -> "LIST"
        Type.MAP -> "MAP"
        Type.ENUM -> "ENUM"
        Type.ONE_OF -> "ONEOF"
        Type.DATA, Type.MAP_ENTRY -> "DATA"
    }

    private fun refSimpleName(type: KSType): String? = when (Type.byType(type, this)) {
        Type.ENUM, Type.DATA -> type.declaration.simpleName.asString()
        else -> null
    }
}
