package dynamic

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSType
import common.versionFromAnnotation
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
        file.id(4) += "override val simpleName = \"${qualifiedName(classInfo)}\"\n"
        classInfo.definition.versionFromAnnotation("AddedIn")?.let { file.id(4) += "override val addedIn = \"$it\"\n" }
        classInfo.definition.versionFromAnnotation("RemovedIn")?.let { file.id(4) += "override val removedIn = \"$it\"\n" }

        file.id(4) += "override val properties = listOf<$REGISTRY_PACKAGE.Property>(\n"
        members.forEach { entry ->
            file.id(8) += emitProperty(classInfo, entry) + ",\n"
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

    private fun emitProperty(classInfo: ClassInfo, entry: Map.Entry<String, MemberInfo>): String {
        val member = entry.value
        val type = member.type
        val propName = member.name.getVariableName()
        val altNames = member.names.filter { it != member.name }
        val altStr = "listOf(" + altNames.joinToString(", ") { "\"$it\"" } + ")"
        val sb = StringBuilder()
        // dataIndex omitted: the Property's position in the list is its index (create/read are positional).
        sb.append("$REGISTRY_PACKAGE.Property(name = \"$propName\", altNames = $altStr, ")
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
        member.addedIn?.let { sb.append(", addedIn = \"$it\"") }
        member.removedIn?.let { sb.append(", removedIn = \"$it\"") }
        sb.append(")")
        return sb.toString()
    }

    private fun emitOneOf(classInfo: ClassInfo, oneOf: OneOfData): String {
        val wrapper = "${classInfo.name}.${oneOf.wrapperName}"
        val cases = oneOf.oneOfClassMap.entries.mapNotNull { (nm, ot) ->
            val model = classInfoCache[ot.kSType] ?: return@mapNotNull null
            val wrapped = "${model.packageName}.${model.name}"
            val caseClass = nm.getClassName()
            val version = (ot.addedIn?.let { ", addedIn = \"$it\"" } ?: "") +
                (ot.removedIn?.let { ", removedIn = \"$it\"" } ?: "")
            "$REGISTRY_PACKAGE.OneOfCase(caseName = \"$caseClass\", wrappedTypeName = \"${qualifiedName(model)}\", " +
                "wrap = { v -> $wrapper.$caseClass(v as $wrapped) }$version)"
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
        Type.ENUM, Type.DATA -> classInfoCache[type]?.let { qualifiedName(it) } ?: type.declaration.simpleName.asString()
        else -> null
    }

    /**
     * Registry/TS identity for a type: nested types are `Parent.Child` (matching the proto descriptor's
     * qualified typeName), top-level types are just the simpleName. Disambiguates same-simpleName nested
     * types — e.g. `Achievement.Status` vs `MainCoop.Status` — which would otherwise collide in the flat
     * registry maps (keyed by simpleName) and the flat TS namespace.
     */
    fun qualifiedName(classInfo: ClassInfo): String =
        classInfo.definition.getParentType()?.takeIf { it.isNotBlank() }?.let { "$it.${classInfo.name}" } ?: classInfo.name

    /**
     * Nest a generated type declaration under `export namespace Parent { ... }` so `Parent.Child` resolves
     * as a TS type — merging with the parent message's `interface Parent` via declaration merging. Top-level
     * types (no parent) are returned unwrapped.
     */
    private fun wrapNested(classInfo: ClassInfo, decl: String): String {
        val parent = classInfo.definition.getParentType()?.takeIf { it.isNotBlank() } ?: return decl
        val indented = decl.trimEnd('\n').lines().joinToString("\n") { if (it.isBlank()) it else "  $it" }
        return "export namespace $parent {\n$indented\n}\n"
    }

    // ---- TypeScript interfaces + plain-JS registry (for the pure-JS gi data package) ----
    // Mirrors the Kotlin registration emission above, but as TS types + plain data the runtime adapter
    // (PlainProtoRuntime) ingests. The .d.ts types the plain objects decode/encode produce/consume.

    /** TS type for a member (Long -> bigint, bytes -> Uint8Array, List -> T[], Map -> Record, ref by simpleName). */
    private fun tsType(type: KSType): String = when (Type.byType(type, this)) {
        Type.SIMPLE -> when (type.declaration.simpleName.asString()) {
            "Long", "ULong" -> "bigint"
            "Boolean" -> "boolean"
            "String" -> "string"
            else -> "number"
        }
        Type.BYTE_ARRAY -> "Uint8Array"
        Type.COLLECTION -> (type.arguments.firstOrNull()?.type?.resolve()?.let { tsType(it) } ?: "unknown") + "[]"
        Type.MAP -> {
            val k = type.arguments.getOrNull(0)?.type?.resolve()
            val v = type.arguments.getOrNull(1)?.type?.resolve()
            "Record<${k?.let { tsKeyType(it) } ?: "string"}, ${v?.let { tsType(it) } ?: "unknown"}>"
        }
        Type.ENUM, Type.DATA, Type.MAP_ENTRY -> classInfoCache[type]?.let { qualifiedName(it) } ?: type.declaration.simpleName.asString()
        Type.ONE_OF -> "unknown" // resolved via oneOf data at the call site
    }

    /** Record<> keys must be string|number; map a bigint (Long) key to string (JS object keys are strings). */
    private fun tsKeyType(type: KSType): String = tsType(type).let { if (it == "bigint") "string" else it }

    private fun tsOneOfType(classInfo: ClassInfo, key: String): String {
        val oneOf = classInfo.oneOfs[key] ?: return "unknown"
        // Multiline tagged union for readability: each case object on its own lines, joined by `|`. The 4/2
        // space indents are relative to the 2-space field line in tsModelInterface (wrapNested re-indents
        // nested types uniformly, preserving alignment). Wrapped types go through tsType so primitives map
        // correctly (Long -> bigint, ...) and message-wrapped cases resolve to their interface name.
        // `case` is the camelCase discriminator (getVariableName) — must match jsRegistryOneOf's caseName,
        // the string the runtime stores in the decoded `{ case, value }`.
        val parts = oneOf.oneOfClassMap.entries.map { (nm, ot) ->
            "{\n    case: \"${nm.getVariableName()}\";\n    value: ${tsType(ot.kSType)};\n  }"
        }
        return if (parts.isEmpty()) "unknown" else parts.joinToString(" | ")
    }

    /** `export interface Name { field: tsType; ... }`, with @since JSDoc and proto3-presence optionality. */
    fun tsModelInterface(classInfo: ClassInfo): String {
        val sb = StringBuilder()
        typeVersionDoc(classInfo)?.let { sb.append(it).append("\n") }
        sb.append("export interface ${classInfo.name} {\n")
        classInfo.modelMembers.forEach { (key, member) ->
            val name = member.name.getVariableName()
            val byType = Type.byType(member.type, this)
            val type = if (byType == Type.ONE_OF) tsOneOfType(classInfo, key) else tsType(member.type)
            // proto3 presence: scalars, enums, lists & maps always have a value after decode (0/""/false/[]/{}),
            // so they're required; only nested messages and oneofs can be genuinely absent, so those are `?`.
            val opt = if (byType == Type.DATA || byType == Type.MAP_ENTRY || byType == Type.ONE_OF) "?" else ""
            versionDoc(member)?.let { sb.append("  $it\n") }
            sb.append("  $name$opt: $type;\n")
        }
        sb.append("}\n")
        return wrapNested(classInfo, sb.toString())
    }

    /** One-line JSDoc carrying field version info (@since / removed-in), or null if none. */
    private fun versionDoc(member: MemberInfo): String? =
        versionDoc(member.addedIn, member.removedIn)

    /** One-line JSDoc carrying type-level version info from @AddedIn/@RemovedIn on the model/enum, or null. */
    private fun typeVersionDoc(classInfo: ClassInfo): String? =
        versionDoc(classInfo.definition.versionFromAnnotation("AddedIn"), classInfo.definition.versionFromAnnotation("RemovedIn"))

    // The .d.ts is version-agnostic (one type set spanning all versions), so removal info is emitted as plain
    // text, NOT @deprecated: a field removed in GI_5_4 is still valid when decoding GI_5_3, and @deprecated
    // would strike through every reference unconditionally (a false alarm). @since is purely informational.
    private fun versionDoc(addedIn: String?, removedIn: String?): String? {
        val parts = mutableListOf<String>()
        addedIn?.let { parts.add("@since $it") }
        removedIn?.let { parts.add(if (parts.isEmpty()) "removed in $it" else "(removed in $it)") }
        return if (parts.isEmpty()) null else "/** ${parts.joinToString(" ")} */"
    }

    /** `export enum Name { ENTRY = 0, ..., UNRECOGNISED = N }` — values match the registry entry values. */
    fun tsEnum(classInfo: ClassInfo): String {
        val sb = StringBuilder()
        typeVersionDoc(classInfo)?.let { sb.append(it).append("\n") }
        sb.append("export enum ${classInfo.name} {\n")
        classInfo.declarations.forEachIndexed { i, d -> sb.append("  ${d.simpleName.asString()} = $i,\n") }
        sb.append("  UNRECOGNISED = ${classInfo.declarations.size},\n")
        sb.append("}\n")
        return wrapNested(classInfo, sb.toString())
    }

    /**
     * `, addedIn: "X", removedIn: "Y"` for a registry literal, each part omitted when null (keeping the
     * registry lean, like altNames). Reads @AddedIn/@RemovedIn off any annotated symbol.
     */
    private fun jsVersionSuffix(symbol: KSAnnotated): String =
        (symbol.versionFromAnnotation("AddedIn")?.let { ", addedIn: \"$it\"" } ?: "") +
            (symbol.versionFromAnnotation("RemovedIn")?.let { ", removedIn: \"$it\"" } ?: "")

    /** Plain-JS registry entry for one model, matching the runtime adapter's JsModelDesc shape. */
    fun jsRegistryModel(classInfo: ClassInfo): String {
        val props = classInfo.modelMembers.entries.toList().map { entry -> jsRegistryProperty(classInfo, entry) }
        return "{ simpleName: \"${qualifiedName(classInfo)}\"${jsVersionSuffix(classInfo.definition)}, " +
            "properties: [${props.joinToString(", ")}] }"
    }

    /** Plain-JS registry entry for one enum (entry values = declaration ordinals, matching [tsEnum]). */
    fun jsRegistryEnum(classInfo: ClassInfo): String {
        val unrecognised = classInfo.declarations.size
        val entries = classInfo.declarations.mapIndexed { i, d ->
            "{ name: \"${d.simpleName.asString()}\", value: $i${jsVersionSuffix(d)} }"
        } + "{ name: \"UNRECOGNISED\", value: $unrecognised }"
        return "{ simpleName: \"${qualifiedName(classInfo)}\"${jsVersionSuffix(classInfo.definition)}, " +
            "unrecognised: $unrecognised, entries: [${entries.joinToString(", ")}] }"
    }

    private fun jsRegistryProperty(classInfo: ClassInfo, entry: Map.Entry<String, MemberInfo>): String {
        val member = entry.value
        val type = member.type
        val propName = member.name.getVariableName()
        // dataIndex is omitted: it always equals the property's array position, which the runtime adapter
        // derives. altNames is omitted when empty (the adapter defaults a missing altNames to none).
        val altNames = member.names.filter { it != member.name }
        val sb = StringBuilder("{ name: \"$propName\"")
        if (altNames.isNotEmpty()) sb.append(", altNames: [${altNames.joinToString(", ") { "\"$it\"" }}]")
        when (Type.byType(type, this)) {
            Type.COLLECTION -> {
                val el = type.arguments.firstOrNull()?.type?.resolve()
                sb.append(", kind: \"LIST\"")
                if (el != null) {
                    sb.append(", elementKind: \"${kindOf(el)}\"")
                    refSimpleName(el)?.let { sb.append(", modelTypeName: \"$it\"") }
                }
            }
            Type.MAP -> {
                val k = type.arguments.getOrNull(0)?.type?.resolve()
                val v = type.arguments.getOrNull(1)?.type?.resolve()
                sb.append(", kind: \"MAP\"")
                if (k != null) {
                    sb.append(", keyKind: \"${kindOf(k)}\"")
                    refSimpleName(k)?.let { sb.append(", keyModelTypeName: \"$it\"") }
                }
                if (v != null) {
                    sb.append(", elementKind: \"${kindOf(v)}\"")
                    refSimpleName(v)?.let { sb.append(", modelTypeName: \"$it\"") }
                }
            }
            Type.ONE_OF -> {
                sb.append(", kind: \"ONEOF\"")
                classInfo.oneOfs[entry.key]?.let { sb.append(", oneOf: ${jsRegistryOneOf(it)}") }
            }
            else -> {
                sb.append(", kind: \"${kindOf(type)}\"")
                refSimpleName(type)?.let { sb.append(", modelTypeName: \"$it\"") }
            }
        }
        member.addedIn?.let { sb.append(", addedIn: \"$it\"") }
        member.removedIn?.let { sb.append(", removedIn: \"$it\"") }
        sb.append(" }")
        return sb.toString()
    }

    private fun jsRegistryOneOf(oneOf: OneOfData): String {
        // caseName is the camelCase discriminator the runtime stores in the decoded `{ case, value }` and
        // expects back on encode (it mirrors tsOneOfType's `case`). The engine derives its proto-field match
        // by lower-casing caseName's first char, so camelCase here matches identically to the old PascalCase.
        val cases = oneOf.oneOfClassMap.entries.mapNotNull { (nm, ot) ->
            val model = classInfoCache[ot.kSType] ?: return@mapNotNull null
            val version = (ot.addedIn?.let { ", addedIn: \"$it\"" } ?: "") +
                (ot.removedIn?.let { ", removedIn: \"$it\"" } ?: "")
            "{ caseName: \"${nm.getVariableName()}\", wrappedTypeName: \"${qualifiedName(model)}\"$version }"
        }
        return "{ cases: [${cases.joinToString(", ")}] }"
    }
}
