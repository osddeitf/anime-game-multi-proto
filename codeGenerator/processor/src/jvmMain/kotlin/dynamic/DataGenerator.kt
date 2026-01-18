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
            file += "class ${classInfo.name} : ${getImplementedModels(classInfo)} {\n"
            return
        }
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

            file.id(4) += "sealed class ${oneOfData.wrapperName}<T>(val value: T) {\n"
            file.id(8) += "class ${oneOfData.unknownName}() : ${oneOfData.wrapperName}<UnknownModel>(UnknownModel())\n"
            file.id(8) += "class UnknownModel\n"
            oneOfData.oneOfClassMap.forEach inner@{ (name, oneOfClass) ->
                val model = classInfoCache[oneOfClass.kSType] ?: return@inner
                val className = name.getClassName()
                file.id(8) += "class ${className}(value:${model.packageName}.${model.name}) : ${oneOfData.wrapperName}<${model.packageName}.${model.name}>(value)\n"
            }
            file.id(4) += "}\n"
        }
    }

    override fun addEncodeMethods(file: OutputStream, classInfo: ClassInfo) {
        file.id(4) += "override fun encodeToByteArray(version:$VERSION_ENUM_CLASS_NAME) : ByteArray? {\n"
        file.id(8) += "return ProtoRuntimeProvider.service.encodeToByteArray(\n"
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
        file.id(12) += "val obj: ${classInfo.name}? = ProtoRuntimeProvider.service.decodeFromByteArray(\n"
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
}
