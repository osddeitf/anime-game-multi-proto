package dynamic

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.OutputStream

class EnumGenerator(
    logger: KSPLogger,
    resolver: Resolver,
    classInfoCache: MutableMap<KSType, ClassInfo>
) : BaseGenerator(logger, resolver, classInfoCache) {
    override fun addConstructor(file: OutputStream, classInfo: ClassInfo) {
        super.addConstructor(file, classInfo)
        file += "enum class ${classInfo.name}(\n"
        file.id(4) += "var names: Set<String>,\n"
        classInfo.modelMembers.filter { it.value.isPrimaryConstructorMember }.forEach {
            // TODO: rename the constructor member if its name is "names"
            file.id(4) += "var ${it.key}: ${getTypeString(it)} = ${it.value.getDefaultValueForType()},\n"
        }
        file += "){\n"
    }

    override fun addBody(file: OutputStream, classInfo: ClassInfo) {
        classInfo.declarations.forEach {
            file.id(4) += "${it.simpleName.asString()}(setOf(${it.getEnumNames().joinToString(", ") { "\"$it\"" }})),\n"
        }
        file.id(4) += "$UNRECOGNISED_ENUM_NAME(emptySet());\n"
    }

    override fun addEncodeMethods(file: OutputStream, classInfo: ClassInfo) {
        // Do nothing
    }

    private fun KSClassDeclaration.getEnumNames(): List<String> {
        val names = mutableListOf(simpleName.asString())
        annotations.filter { annotation -> annotation.shortName.asString() == "AltName" }
            .forEach { it.arguments.forEach { (it.value as? List<String>)?.let { names.addAll(it) } } }

        return names
    }

    override fun addCompanionObject(file: OutputStream, classInfo: ClassInfo) {
        // Do nothing
    }

    override fun addClosure(file: OutputStream, classInfo: ClassInfo) {
        file += "}"
    }

    override fun addRegistration(file: OutputStream, classInfo: ClassInfo) {
        val name = classInfo.name
        file += "\nobject ${name}_Registration : $REGISTRY_PACKAGE.EnumRegistration {\n"
        file.id(4) += "override val simpleName = \"$name\"\n"
        file.id(4) += "override val entries = listOf<$REGISTRY_PACKAGE.EnumEntry>(\n"
        classInfo.declarations.forEach {
            val en = it.simpleName.asString()
            file.id(8) += "$REGISTRY_PACKAGE.EnumEntry(\"$en\", $name.$en, false),\n"
        }
        file.id(8) += "$REGISTRY_PACKAGE.EnumEntry(\"$UNRECOGNISED_ENUM_NAME\", $name.$UNRECOGNISED_ENUM_NAME, true),\n"
        file.id(4) += ")\n"
        file.id(4) += "override val unrecognised = $name.$UNRECOGNISED_ENUM_NAME\n"
        file += "}\n"
    }
}